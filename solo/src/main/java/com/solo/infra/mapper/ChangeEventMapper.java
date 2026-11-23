package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.event.model.ChangeEvent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 变更事件流（3.11.7）。游标就是自增主键 {@code seq}。
 *
 * <p><b>为什么批量插入要把列写死</b>：{@code seq} 是自增列，实体里的 id 必须让库分配；
 * 走 MP 的 {@code insert()} 单条循环在 500 条明细时是 500 次往返，不值得。</p>
 */
@Mapper
public interface ChangeEventMapper extends BaseMapper<ChangeEvent> {

    @Insert("""
            INSERT INTO change_event
                (tenant_id, source_type, platform_id, org_path, item_type, code,
                 change_type, shape, payload, task_id, create_time)
            VALUES
            <foreach collection="list" item="it" separator=",">
                (#{it.tenantId}, #{it.sourceType}, #{it.platformId}, #{it.orgPath}, #{it.itemType}, #{it.code},
                 #{it.changeType}, #{it.shape}, #{it.payload}, #{it.taskId}, #{it.createTime})
            </foreach>
            """)
    int insertBatch(@Param("list") List<ChangeEvent> list);

    /**
     * 按游标续传：拉 {@code seq > cursor} 的事件。
     * <p>scope 过滤放在服务层做（事件量小、组织路径前缀比较在 SQL 里代价更高），
     * 这里只保证顺序与上限。</p>
     */
    @Select("""
            SELECT * FROM change_event
            WHERE tenant_id = #{tenantId} AND seq > #{cursor}
            ORDER BY seq
            LIMIT #{limit}
            """)
    List<ChangeEvent> selectAfter(@Param("tenantId") Long tenantId,
                                  @Param("cursor") long cursor,
                                  @Param("limit") int limit);

    /** 窗口下界：客户端游标小于它说明事件已被保留期清掉，只能回 409 + 快照地址 */
    @Select("SELECT COALESCE(MIN(seq), 0) FROM change_event WHERE tenant_id = #{tenantId}")
    long selectMinSeq(@Param("tenantId") Long tenantId);

    @Select("SELECT COALESCE(MAX(seq), 0) FROM change_event WHERE tenant_id = #{tenantId}")
    long selectMaxSeq(@Param("tenantId") Long tenantId);

    /**
     * 保留期回收。事件量按"日 1 万条"估算（5.2.1），单条 DELETE 够用；
     * 真长成百万级再按批删（对齐 sync_round_snapshot 的做法）。
     */
    @Delete("DELETE FROM change_event WHERE create_time < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);
}
