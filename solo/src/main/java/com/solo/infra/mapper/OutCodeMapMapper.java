package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.share.model.OutCodeMap;
import com.solo.infra.mapper.projection.CodeSeqHighWater;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 暴露码映射持久化（3.9.3 / C16）。
 *
 * <p>序号分配的唯一依据是 {@code MAX(seq_no)}：<b>墓碑行不删</b>，
 * 所以最大值永远覆盖已用过的号，实现"删除不复用"。
 * 同一事务内还要先锁平台行（{@code GbParentPlatformMapper#lockById}），否则两个并发分配会拿到同一个号。</p>
 */
@Mapper
public interface OutCodeMapMapper extends BaseMapper<OutCodeMap> {

    @Select("""
            SELECT * FROM out_code_map
            WHERE platform_id = #{platformId} AND ref_type = #{refType} AND native_code = #{nativeCode}
            LIMIT 1
            """)
    OutCodeMap selectByNative(@Param("platformId") Long platformId,
                              @Param("refType") String refType,
                              @Param("nativeCode") String nativeCode);

    @Select("""
            SELECT * FROM out_code_map
            WHERE platform_id = #{platformId} AND exposed_code = #{exposedCode}
            LIMIT 1
            """)
    OutCodeMap selectByExposed(@Param("platformId") Long platformId,
                               @Param("exposedCode") String exposedCode);

    /**
     * 该平台该类型段已分配到的最大序号（含墓碑）。
     * <p>{@code COALESCE} 保证空表返回 0 而不是 null，调用方直接 {@code +1} 即可。</p>
     */
    @Select("""
            SELECT COALESCE(MAX(seq_no), 0) FROM out_code_map
            WHERE platform_id = #{platformId} AND type_seg = #{typeSeg}
            """)
    long maxSeq(@Param("platformId") Long platformId, @Param("typeSeg") String typeSeg);

    /** 批量反查（一页列表一次取全，避免 N+1） */
    @Select("""
            <script>
            SELECT * FROM out_code_map
            WHERE platform_id = #{platformId} AND ref_type = #{refType}
              AND native_code IN
              <foreach collection="nativeCodes" item="c" open="(" separator="," close=")">#{c}</foreach>
            </script>
            """)
    List<OutCodeMap> selectByNatives(@Param("platformId") Long platformId,
                                     @Param("refType") String refType,
                                     @Param("nativeCodes") List<String> nativeCodes);

    /** ACTIVE ↔ TOMBSTONE；不删行（同一资源恢复时复用自己的墓碑码） */
    @Update("""
            UPDATE out_code_map
            SET state = #{state}, update_time = #{now}
            WHERE id = #{id}
            """)
    int updateState(@Param("id") Long id, @Param("state") String state, @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT COUNT(*) FROM out_code_map
            WHERE platform_id = #{platformId}
            <if test="refType != null and refType != ''"> AND ref_type = #{refType} </if>
            <if test="state != null and state != ''"> AND state = #{state} </if>
            </script>
            """)
    long countByFilter(@Param("platformId") Long platformId,
                       @Param("refType") String refType,
                       @Param("state") String state);

    /** 编码映射页：按暴露码或原始码搜 */
    @Select("""
            <script>
            SELECT * FROM out_code_map
            WHERE platform_id = #{platformId}
            <if test="keyword != null and keyword != ''">
              AND (exposed_code LIKE CONCAT('%', #{keyword}, '%') OR native_code LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY ref_type, seq_no
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<OutCodeMap> pageByPlatform(@Param("platformId") Long platformId,
                                    @Param("keyword") String keyword,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    /** 分片高水位：给前端"下一个可用序号"用，避免用户以为号段连续 */
    @Select("""
            <script>
            SELECT type_seg AS typeSeg, COALESCE(MAX(seq_no), 0) AS seqNo
            FROM out_code_map WHERE platform_id = #{platformId}
            GROUP BY type_seg
            </script>
            """)
    List<CodeSeqHighWater> selectHighWater(@Param("platformId") Long platformId);
}
