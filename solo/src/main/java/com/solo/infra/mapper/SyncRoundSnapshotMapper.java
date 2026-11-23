package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.sync.model.SyncRoundSnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 原始报文快照（3.13.2）。只在 {@code sync.snapshot.enabled=true} 时被调用。
 *
 * <p>清理必须<b>分批</b>：100 万行一条 DELETE 会拉长事务、锁住整张表。
 * MySQL 用 {@code DELETE ... LIMIT}，PG 没有该语法，用 {@code id IN (SELECT ... LIMIT)}。</p>
 */
@Mapper
public interface SyncRoundSnapshotMapper extends BaseMapper<SyncRoundSnapshot> {

    /** 分批写入（与 staging 同一批行；唯一键保证重发批次只覆盖不重复） */
    @Insert("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                INSERT INTO sync_round_snapshot
                    (id, tenant_id, task_id, platform_id, item_type, code, parent_code, name,
                     online_status, device_code, stream_type, capability, device_ip,
                     vendor, model, group_type, create_time)
                VALUES
                <foreach collection="list" item="it" separator=",">
                    (#{it.id}, #{it.tenantId}, #{it.taskId}, #{it.platformId}, #{it.itemType}, #{it.code},
                     #{it.parentCode}, #{it.name}, #{it.onlineStatus}, #{it.deviceCode}, #{it.streamType},
                     #{it.capability}, #{it.deviceIp}, #{it.vendor}, #{it.model}, #{it.groupType},
                     #{it.createTime})
                </foreach>
                ON CONFLICT (task_id, item_type, code) DO UPDATE SET
                    parent_code = EXCLUDED.parent_code, name = EXCLUDED.name,
                    online_status = EXCLUDED.online_status, device_code = EXCLUDED.device_code,
                    stream_type = EXCLUDED.stream_type, capability = EXCLUDED.capability,
                    device_ip = EXCLUDED.device_ip, vendor = EXCLUDED.vendor,
                    model = EXCLUDED.model, group_type = EXCLUDED.group_type
              </when>
              <otherwise>
                INSERT INTO sync_round_snapshot
                    (id, tenant_id, task_id, platform_id, item_type, code, parent_code, name,
                     online_status, device_code, stream_type, capability, device_ip,
                     vendor, model, group_type, create_time)
                VALUES
                <foreach collection="list" item="it" separator=",">
                    (#{it.id}, #{it.tenantId}, #{it.taskId}, #{it.platformId}, #{it.itemType}, #{it.code},
                     #{it.parentCode}, #{it.name}, #{it.onlineStatus}, #{it.deviceCode}, #{it.streamType},
                     #{it.capability}, #{it.deviceIp}, #{it.vendor}, #{it.model}, #{it.groupType},
                     #{it.createTime})
                </foreach>
                ON DUPLICATE KEY UPDATE
                    parent_code = VALUES(parent_code), name = VALUES(name),
                    online_status = VALUES(online_status), device_code = VALUES(device_code),
                    stream_type = VALUES(stream_type), capability = VALUES(capability),
                    device_ip = VALUES(device_ip), vendor = VALUES(vendor),
                    model = VALUES(model), group_type = VALUES(group_type)
              </otherwise>
            </choose>
            </script>
            """)
    int insertBatch(@Param("list") List<SyncRoundSnapshot> list);

    /**
     * 该平台最近 N 轮的 task_id（按快照行的最早创建时间倒序）。
     * <p>清理口径是"保留最近 N 轮"，不是"保留最近 N 天"——按轮次算才能保证
     * 一个跑了整天的大轮次不会被提前清掉。</p>
     */
    @Select("""
            SELECT task_id FROM sync_round_snapshot
            WHERE platform_id = #{platformId}
            GROUP BY task_id
            ORDER BY MIN(create_time) DESC
            LIMIT #{keep}
            """)
    List<Long> selectRecentTaskIds(@Param("platformId") Long platformId, @Param("keep") int keep);

    @Delete("""
            DELETE FROM sync_round_snapshot
            WHERE platform_id = #{platformId} AND task_id = #{taskId}
            """)
    int deleteByTask(@Param("platformId") Long platformId, @Param("taskId") Long taskId);

    /**
     * 分批删除该平台除 {@code keepTaskIds} 之外的快照。
     * <p>MySQL / PG 的"带 LIMIT 的 DELETE"写法不同，所以分两条语句。
     * 调用方循环到返回 0 为止，每批 5 万行，避免长事务。</p>
     */
    @Delete("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                DELETE FROM sync_round_snapshot
                WHERE id IN (
                    SELECT id FROM sync_round_snapshot
                    WHERE platform_id = #{platformId}
                      AND task_id NOT IN
                      <foreach collection="keepTaskIds" item="tid" open="(" separator="," close=")">
                          #{tid}
                      </foreach>
                    LIMIT #{batchSize}
                )
              </when>
              <otherwise>
                DELETE FROM sync_round_snapshot
                WHERE platform_id = #{platformId}
                  AND task_id NOT IN
                  <foreach collection="keepTaskIds" item="tid" open="(" separator="," close=")">
                      #{tid}
                  </foreach>
                ORDER BY id
                LIMIT #{batchSize}
              </otherwise>
            </choose>
            </script>
            """)
    int deleteExcessBatch(@Param("platformId") Long platformId,
                          @Param("keepTaskIds") List<Long> keepTaskIds,
                          @Param("batchSize") int batchSize);

    @Select("SELECT COUNT(*) FROM sync_round_snapshot WHERE platform_id = #{platformId}")
    long countByPlatform(@Param("platformId") Long platformId);
}
