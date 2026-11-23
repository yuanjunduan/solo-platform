package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.sync.model.SyncRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 同步明细留痕（3.8.6）。
 *
 * <p>写入方式是 {@code INSERT ... SELECT}，不把被标记的行拉进内存再逐条 insert：
 * 消失集合可能上万行，逐条往返会让收尾事务从秒级掉到分钟级。</p>
 *
 * <p><b>为什么跟着 UPDATE 的 {@code update_time} 选行</b>：标记语句先执行、紧接着取行，
 * 同一平台同一状态同一秒内不存在第二轮（平台互斥保证），所以
 * {@code status = ? AND update_time = ?} 精确等于"刚被这一条语句改过的行"。
 * 若放到 {@code refreshChannelCount} 之后再取，就会把当轮正常更新的行也算进来——
 * 所以每个类型的留痕必须紧贴它自己的标记语句。</p>
 *
 * <p>四张表各写一条，不用 {@code ${table}} 拼接：表名拼接是 SQL 注入面，
 * 即使调用方只传常量也不留这个口子（与 {@code SyncStagingMapper} 同一原则）。</p>
 */
@Mapper
public interface SyncRecordMapper extends BaseMapper<SyncRecord> {

    @Insert("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                INSERT INTO sync_record
                    (id, tenant_id, task_id, platform_id, item_type, code, change_type,
                     before_summary, after_summary, create_time)
                SELECT nextval('seq_sync_record'), d.tenant_id, #{taskId}, d.source_platform_id,
                       'ORG', d.code, #{changeType}, d.name, NULL, #{now}
                FROM res_org d
                WHERE d.source_type = 'INBOUND' AND d.source_platform_id = #{platformId}
                  AND d.status = #{status} AND d.update_time = #{now}
              </when>
              <otherwise>
                INSERT INTO sync_record
                    (id, tenant_id, task_id, platform_id, item_type, code, change_type,
                     before_summary, after_summary, create_time)
                SELECT UUID_SHORT(), d.tenant_id, #{taskId}, d.source_platform_id,
                       'ORG', d.code, #{changeType}, d.name, NULL, #{now}
                FROM res_org d
                WHERE d.source_type = 'INBOUND' AND d.source_platform_id = #{platformId}
                  AND d.status = #{status} AND d.update_time = #{now}
              </otherwise>
            </choose>
            </script>
            """)
    int recordOrg(@Param("taskId") Long taskId,
                  @Param("platformId") Long platformId,
                  @Param("status") String status,
                  @Param("changeType") String changeType,
                  @Param("now") LocalDateTime now);

    @Insert("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                INSERT INTO sync_record
                    (id, tenant_id, task_id, platform_id, item_type, code, change_type,
                     before_summary, after_summary, create_time)
                SELECT nextval('seq_sync_record'), d.tenant_id, #{taskId}, d.source_platform_id,
                       'GROUP', d.code, #{changeType}, d.name, NULL, #{now}
                FROM res_group d
                WHERE d.source_type = 'INBOUND' AND d.source_platform_id = #{platformId}
                  AND d.status = #{status} AND d.update_time = #{now}
              </when>
              <otherwise>
                INSERT INTO sync_record
                    (id, tenant_id, task_id, platform_id, item_type, code, change_type,
                     before_summary, after_summary, create_time)
                SELECT UUID_SHORT(), d.tenant_id, #{taskId}, d.source_platform_id,
                       'GROUP', d.code, #{changeType}, d.name, NULL, #{now}
                FROM res_group d
                WHERE d.source_type = 'INBOUND' AND d.source_platform_id = #{platformId}
                  AND d.status = #{status} AND d.update_time = #{now}
              </otherwise>
            </choose>
            </script>
            """)
    int recordGroup(@Param("taskId") Long taskId,
                    @Param("platformId") Long platformId,
                    @Param("status") String status,
                    @Param("changeType") String changeType,
                    @Param("now") LocalDateTime now);

    @Insert("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                INSERT INTO sync_record
                    (id, tenant_id, task_id, platform_id, item_type, code, change_type,
                     before_summary, after_summary, create_time)
                SELECT nextval('seq_sync_record'), d.tenant_id, #{taskId}, d.source_platform_id,
                       'DEVICE', d.code, #{changeType}, d.name, NULL, #{now}
                FROM res_device d
                WHERE d.source_type = 'INBOUND' AND d.source_platform_id = #{platformId}
                  AND d.status = #{status} AND d.update_time = #{now}
              </when>
              <otherwise>
                INSERT INTO sync_record
                    (id, tenant_id, task_id, platform_id, item_type, code, change_type,
                     before_summary, after_summary, create_time)
                SELECT UUID_SHORT(), d.tenant_id, #{taskId}, d.source_platform_id,
                       'DEVICE', d.code, #{changeType}, d.name, NULL, #{now}
                FROM res_device d
                WHERE d.source_type = 'INBOUND' AND d.source_platform_id = #{platformId}
                  AND d.status = #{status} AND d.update_time = #{now}
              </otherwise>
            </choose>
            </script>
            """)
    int recordDevice(@Param("taskId") Long taskId,
                     @Param("platformId") Long platformId,
                     @Param("status") String status,
                     @Param("changeType") String changeType,
                     @Param("now") LocalDateTime now);

    @Insert("""
            <script>
            <choose>
              <when test="_databaseId == 'postgresql'">
                INSERT INTO sync_record
                    (id, tenant_id, task_id, platform_id, item_type, code, change_type,
                     before_summary, after_summary, create_time)
                SELECT nextval('seq_sync_record'), d.tenant_id, #{taskId}, d.source_platform_id,
                       'CHANNEL', d.code, #{changeType}, d.name, NULL, #{now}
                FROM res_channel d
                WHERE d.source_type = 'INBOUND' AND d.source_platform_id = #{platformId}
                  AND d.status = #{status} AND d.update_time = #{now}
              </when>
              <otherwise>
                INSERT INTO sync_record
                    (id, tenant_id, task_id, platform_id, item_type, code, change_type,
                     before_summary, after_summary, create_time)
                SELECT UUID_SHORT(), d.tenant_id, #{taskId}, d.source_platform_id,
                       'CHANNEL', d.code, #{changeType}, d.name, NULL, #{now}
                FROM res_channel d
                WHERE d.source_type = 'INBOUND' AND d.source_platform_id = #{platformId}
                  AND d.status = #{status} AND d.update_time = #{now}
              </otherwise>
            </choose>
            </script>
            """)
    int recordChannel(@Param("taskId") Long taskId,
                      @Param("platformId") Long platformId,
                      @Param("status") String status,
                      @Param("changeType") String changeType,
                      @Param("now") LocalDateTime now);

    /** 保留期分批删除（M7，5.2）；只由 RetentionJob 调用 */
    @Delete("""
            DELETE FROM sync_record WHERE id IN (
                SELECT id FROM (
                    SELECT id FROM sync_record WHERE create_time < #{before} LIMIT #{limit}
                ) t
            )
            """)
    int deleteBeforeBatch(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
