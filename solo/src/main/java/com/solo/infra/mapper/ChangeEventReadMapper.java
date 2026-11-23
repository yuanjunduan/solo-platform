package com.solo.infra.mapper;

import com.solo.infra.mapper.projection.ChangedRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 变更事件明细的回读（3.11.7 实现细节 2：写库后、同一事务内回读）。
 *
 * <p><b>为什么靠 {@code update_time = #{now}} 选行</b>：插入、差异更新、消失标记、
 * 归属纠正四条路径都把 {@code update_time} 写成收尾时的同一个 {@code now}，
 * 于是它天然就是"本轮影响行"的集合，且是写库之后才成立的最终值
 * （暴露码/组织 id/能力集都已是终值）。</p>
 *
 * <p>每类都带 {@code LIMIT}：回读只是给订阅方看的摘要，
 * 超过阈值时上层根本不会调到这里（{@link com.solo.domain.event.support.EventShapeDecider} 已降级）。</p>
 */
@Mapper
public interface ChangeEventReadMapper {

    @Select("""
            <script>
            SELECT code, name, path AS org_path, status, create_time
            FROM res_org
            WHERE source_type = 'INBOUND'
              AND source_platform_id = #{platformId}
              AND update_time = #{now}
              AND code &lt;&gt; #{platformCode}
            ORDER BY code
            LIMIT #{limit}
            </script>
            """)
    List<ChangedRow> readOrgChanges(@Param("platformId") Long platformId,
                                    @Param("now") LocalDateTime now,
                                    @Param("platformCode") String platformCode,
                                    @Param("limit") int limit);

    @Select("""
            <script>
            SELECT code, name, org_path, status, create_time
            FROM res_device
            WHERE source_type = 'INBOUND'
              AND source_platform_id = #{platformId}
              AND update_time = #{now}
              AND code &lt;&gt; #{platformCode}
            ORDER BY code
            LIMIT #{limit}
            </script>
            """)
    List<ChangedRow> readDeviceChanges(@Param("platformId") Long platformId,
                                       @Param("now") LocalDateTime now,
                                       @Param("platformCode") String platformCode,
                                       @Param("limit") int limit);

    @Select("""
            <script>
            SELECT code, name, org_path, status, online_status, create_time
            FROM res_channel
            WHERE source_type = 'INBOUND'
              AND source_platform_id = #{platformId}
              AND update_time = #{now}
            ORDER BY code
            LIMIT #{limit}
            </script>
            """)
    List<ChangedRow> readChannelChanges(@Param("platformId") Long platformId,
                                        @Param("now") LocalDateTime now,
                                        @Param("limit") int limit);
}
