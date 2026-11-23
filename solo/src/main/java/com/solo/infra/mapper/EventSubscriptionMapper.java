package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.event.model.EventSubscription;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 对外订阅（3.5.3 表 14）。游标只前进不后退，靠 {@code last_event_id < #{seq}} 兜住并发。 */
@Mapper
public interface EventSubscriptionMapper extends BaseMapper<EventSubscription> {

    @Select("""
            SELECT * FROM event_subscription
            WHERE app_id = #{appId} AND topic = #{topic}
            LIMIT 1
            """)
    EventSubscription selectByAppAndTopic(@Param("appId") Long appId, @Param("topic") String topic);

    /**
     * 推进服务端游标。
     * <p>带 {@code last_event_id < #{seq}} 是必要的：多实例同时推送同一订阅时，
     * 小的那个不能把大游标改回去（否则重连会重复回放）。</p>
     */
    @Update("""
            UPDATE event_subscription
            SET last_event_id = #{seq}, update_time = #{now}
            WHERE id = #{id} AND last_event_id < #{seq}
            """)
    int advanceCursor(@Param("id") Long id, @Param("seq") long seq, @Param("now") LocalDateTime now);

    /**
     * 管理端：游标清零（{@code last_event_id = 0} 等价于"从当前最大游标重新开始"，
     * 见 {@code EventStreamService} 的首连规则）。改完 scope 想立刻生效时用它。
     */
    @Update("""
            UPDATE event_subscription
            SET last_event_id = 0, update_time = #{now}
            WHERE id = #{id}
            """)
    int resetCursor(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 管理端编辑：显式 SET 而不是 {@code updateById}。
     * <p>原因：MP 的 {@code updateById} 默认跳过 null 字段，想"清空平台 scope"
     * 就永远清不掉——而清空恰恰是最需要能生效的操作（从单平台放宽到全部）。</p>
     */
    @Update("""
            UPDATE event_subscription
            SET platform_id = #{platformId}, org_path = #{orgPath}, status = #{status},
                remark = #{remark}, update_time = #{now}
            WHERE id = #{id}
            """)
    int updateScope(@Param("id") Long id,
                    @Param("platformId") Long platformId,
                    @Param("orgPath") String orgPath,
                    @Param("status") Integer status,
                    @Param("remark") String remark,
                    @Param("now") LocalDateTime now);

    /** 管理端列表：带应用名与平台名，免得前端再各自去查 */
    @Select("""
            <script>
            SELECT s.*, a.app_name AS app_name, p.name AS platform_name
            FROM event_subscription s
            LEFT JOIN open_app a ON a.id = s.app_id
            LEFT JOIN platform p ON p.id = s.platform_id
            WHERE 1 = 1
            <if test="appKey != null and appKey != ''"> AND s.app_key LIKE CONCAT('%', #{appKey}, '%') </if>
            <if test="status != null"> AND s.status = #{status} </if>
            ORDER BY s.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<EventSubscription> pageWithApp(@Param("appKey") String appKey,
                                        @Param("status") Integer status,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM event_subscription s
            WHERE 1 = 1
            <if test="appKey != null and appKey != ''"> AND s.app_key LIKE CONCAT('%', #{appKey}, '%') </if>
            <if test="status != null"> AND s.status = #{status} </if>
            </script>
            """)
    long countByFilter(@Param("appKey") String appKey, @Param("status") Integer status);
}
