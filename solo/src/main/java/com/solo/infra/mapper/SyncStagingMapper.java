package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.sync.model.SyncStaging;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本轮远端目录暂存。
 *
 * <p><b>写失败必须往外抛</b>：它的定位是主链路而不是旁路（C14），
 * 所以这里没有任何降级/吞异常的分支，调用方也不许 try-catch 掉。</p>
 *
 * <p>批量插入不走 MyBatis-Plus 的逐条循环：一页 500 行逐条 insert 在网络往返上就吃掉几秒，
 * 百万级目录会直接不可用。调用方负责填好 {@code id / tenantId / createTime}——
 * 注解 SQL 不经过 {@code MetaObjectHandler}，留空会撞 NOT NULL。</p>
 */
@Mapper
public interface SyncStagingMapper extends BaseMapper<SyncStaging> {

    /**
     * 分批写入（一页一次）。一页 500 行 × 22 列 ≈ 11000 个参数，远低于 PG 的 65535 上限；
     * 但不要一次传几千行，否则会撞参数上限。
     *
     * <p>必须容忍同键重发：GB28181 的分片有重传，同一 {@code (task_id, item_type, code)}
     * 再来一次不能整批失败——按"后到的覆盖先到的"处理，与对端重发时机无关。</p>
     */
    @Insert("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                INSERT INTO sync_staging
                    (id, tenant_id, task_id, platform_id, item_type, code, parent_code, name,
                     online_status, device_code, stream_type, capability, media_hint, device_ip,
                     vendor, model, group_type, origin_platform_id, origin_code, origin_chain,
                     hop_count, create_time)
                VALUES
                <foreach collection="list" item="it" separator=",">
                    (#{it.id}, #{it.tenantId}, #{it.taskId}, #{it.platformId}, #{it.itemType}, #{it.code},
                     #{it.parentCode}, #{it.name}, #{it.onlineStatus}, #{it.deviceCode}, #{it.streamType},
                     #{it.capability}, #{it.mediaHint}, #{it.deviceIp}, #{it.vendor}, #{it.model},
                     #{it.groupType}, #{it.originPlatformId}, #{it.originCode}, #{it.originChain},
                     #{it.hopCount}, #{it.createTime})
                </foreach>
                ON CONFLICT (task_id, item_type, code) DO UPDATE SET
                    parent_code = EXCLUDED.parent_code, name = EXCLUDED.name,
                    online_status = EXCLUDED.online_status, device_code = EXCLUDED.device_code,
                    stream_type = EXCLUDED.stream_type, capability = EXCLUDED.capability,
                    media_hint = EXCLUDED.media_hint, device_ip = EXCLUDED.device_ip,
                    vendor = EXCLUDED.vendor, model = EXCLUDED.model,
                    group_type = EXCLUDED.group_type,
                    origin_platform_id = EXCLUDED.origin_platform_id,
                    origin_code = EXCLUDED.origin_code, origin_chain = EXCLUDED.origin_chain,
                    hop_count = EXCLUDED.hop_count
              </when>
              <otherwise>
                INSERT INTO sync_staging
                    (id, tenant_id, task_id, platform_id, item_type, code, parent_code, name,
                     online_status, device_code, stream_type, capability, media_hint, device_ip,
                     vendor, model, group_type, origin_platform_id, origin_code, origin_chain,
                     hop_count, create_time)
                VALUES
                <foreach collection="list" item="it" separator=",">
                    (#{it.id}, #{it.tenantId}, #{it.taskId}, #{it.platformId}, #{it.itemType}, #{it.code},
                     #{it.parentCode}, #{it.name}, #{it.onlineStatus}, #{it.deviceCode}, #{it.streamType},
                     #{it.capability}, #{it.mediaHint}, #{it.deviceIp}, #{it.vendor}, #{it.model},
                     #{it.groupType}, #{it.originPlatformId}, #{it.originCode}, #{it.originChain},
                     #{it.hopCount}, #{it.createTime})
                </foreach>
                ON DUPLICATE KEY UPDATE
                    parent_code = VALUES(parent_code), name = VALUES(name),
                    online_status = VALUES(online_status), device_code = VALUES(device_code),
                    stream_type = VALUES(stream_type), capability = VALUES(capability),
                    media_hint = VALUES(media_hint), device_ip = VALUES(device_ip),
                    vendor = VALUES(vendor), model = VALUES(model),
                    group_type = VALUES(group_type),
                    origin_platform_id = VALUES(origin_platform_id),
                    origin_code = VALUES(origin_code), origin_chain = VALUES(origin_chain),
                    hop_count = VALUES(hop_count)
              </otherwise>
            </choose>
            </script>
            """)
    int insertBatch(@Param("list") List<SyncStaging> list);

    /**
     * 本轮目录指纹：对 {@code code} 做 32 位哈希再求和。
     * <p>求和与顺序无关，所以不必真按 code 排序（省掉百万行排序）。
     * 哈希碰撞会让指纹"误判未变"，因此 M2a <b>只用它记录与告警，不用它跳过合并</b>。</p>
     */
    @Select("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                SELECT COALESCE(SUM(hashtext(item_type || code)), 0) FROM sync_staging WHERE task_id = #{taskId}
              </when>
              <otherwise>
                SELECT COALESCE(SUM(CRC32(CONCAT(item_type, code))), 0) FROM sync_staging WHERE task_id = #{taskId}
              </otherwise>
            </choose>
            </script>
            """)
    long fingerprintSum(@Param("taskId") Long taskId);

    @Delete("DELETE FROM sync_staging WHERE task_id = #{taskId}")
    int deleteByTask(@Param("taskId") Long taskId);

    @Delete("DELETE FROM sync_staging WHERE platform_id = #{platformId}")
    int deleteByPlatform(@Param("platformId") Long platformId);

    /**
     * 失败收尾：清同域历史残留，<b>保留本轮</b>（3.13.4）。
     *
     * <p>失败轮的数据是最有价值的现场——"为什么这一轮把设备全标成消失"要靠它复盘。
     * 同域历史残留（更早的失败轮）才清：两轮同域现场并排放，反而分不清哪行属于哪轮。</p>
     */
    @Delete("DELETE FROM sync_staging WHERE platform_id = #{platformId} AND task_id != #{keepTaskId}")
    int deleteHistory(@Param("platformId") Long platformId,
                      @Param("keepTaskId") Long keepTaskId);

    /**
     * 过保留期的残留（3.13.4 运行期周期清理）。
     * <p>分批删：百万级一轮的遗留一次性删会长时间持锁，把正在收批次的其他平台挡住。</p>
     */
    @Delete("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                DELETE FROM sync_staging
                WHERE id IN (SELECT id FROM sync_staging
                             WHERE create_time &lt; #{before}
                             LIMIT #{batchSize})
              </when>
              <otherwise>
                DELETE FROM sync_staging
                WHERE create_time &lt; #{before}
                ORDER BY create_time
                LIMIT #{batchSize}
              </otherwise>
            </choose>
            </script>
            """)
    int deleteExpiredBatch(@Param("before") LocalDateTime before,
                           @Param("batchSize") int batchSize);

    @Select("SELECT COUNT(*) FROM sync_staging WHERE task_id = #{taskId}")
    long countByTask(@Param("taskId") Long taskId);

    @Select("SELECT COUNT(*) FROM sync_staging WHERE task_id = #{taskId} AND item_type = #{itemType}")
    long countByTaskAndType(@Param("taskId") Long taskId, @Param("itemType") String itemType);

    /**
     * 本地该平台的行数（G2 的分母：本地行数 × guard_ratio）。
     * <p>刻意写成一张表一个方法，不用 {@code ${table}} 拼接：表名拼接在 MyBatis 里是
     * SQL 注入面，即使调用方只传常量也不该留这个口子。</p>
     */
    @Select("SELECT COUNT(*) FROM res_org WHERE source_type = 'INBOUND' AND source_platform_id = #{platformId}")
    long countInboundOrg(@Param("platformId") Long platformId);

    @Select("SELECT COUNT(*) FROM res_group WHERE source_type = 'INBOUND' AND source_platform_id = #{platformId}")
    long countInboundGroup(@Param("platformId") Long platformId);

    @Select("SELECT COUNT(*) FROM res_device WHERE source_type = 'INBOUND' AND source_platform_id = #{platformId}")
    long countInboundDevice(@Param("platformId") Long platformId);

    @Select("SELECT COUNT(*) FROM res_channel WHERE source_type = 'INBOUND' AND source_platform_id = #{platformId}")
    long countInboundChannel(@Param("platformId") Long platformId);

    /**
     * 清掉不属于任何存活任务的残留暂存行（3.13.4 残留清理）。
     * <p>任务被删或长期失败时 staging 会留下孤儿行，它们不会被集合 SQL 读到，
     * 但会持续占空间并把"这张表多大"算错。</p>
     */
    @Delete("""
            DELETE FROM sync_staging
            WHERE NOT EXISTS (SELECT 1 FROM sync_task t
                              WHERE t.id = sync_staging.task_id
                                AND t.status IN ('PENDING', 'RUNNING', 'SYNCHING'))
            """)
    int deleteOrphans();

    default SyncStaging selectOneByTaskAndCode(Long taskId, String itemType, String code) {
        List<SyncStaging> list = selectList(new LambdaQueryWrapper<SyncStaging>()
                .eq(SyncStaging::getTaskId, taskId)
                .eq(SyncStaging::getItemType, itemType)
                .eq(SyncStaging::getCode, code)
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }
}
