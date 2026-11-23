package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.share.model.PublishRound;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 推送轮次台账持久化（C17）。
 *
 * <p>一轮一行、<b>不可重写</b>：只允许从 RUNNING 收尾到 DONE/FAILED/SKIPPED 一次。
 * 台账是"两周后回答上一轮为什么没补齐"的唯一依据，允许改写就等于没有台账。</p>
 */
@Mapper
public interface PublishRoundMapper extends BaseMapper<PublishRound> {

    @Select("""
            <script>
            SELECT * FROM publish_round
            WHERE 1 = 1
            <if test="platformId != null"> AND platform_id = #{platformId} </if>
            <if test="status != null and status != ''"> AND status = #{status} </if>
            ORDER BY begin_time DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<PublishRound> pageByFilter(@Param("platformId") Long platformId,
                                    @Param("status") String status,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM publish_round
            WHERE 1 = 1
            <if test="platformId != null"> AND platform_id = #{platformId} </if>
            <if test="status != null and status != ''"> AND status = #{status} </if>
            </script>
            """)
    long countByFilter(@Param("platformId") Long platformId, @Param("status") String status);

    /** 该平台最近一轮（页面上"上次推送：3 分钟前，成功 12 条"） */
    @Select("""
            SELECT * FROM publish_round
            WHERE platform_id = #{platformId}
            ORDER BY begin_time DESC, id DESC
            LIMIT 1
            """)
    PublishRound selectLatest(@Param("platformId") Long platformId);

    /**
     * 该平台是否有正在跑的轮次 —— 平台级互斥（C24）。
     * <p>锁放 DB：多实例部署时另一个节点也看得见；进程内标志位会让每个实例都以为自己空闲。</p>
     */
    @Select("""
            SELECT COUNT(*) FROM publish_round WHERE platform_id = #{platformId} AND status = 'RUNNING'
            """)
    long countRunning(@Param("platformId") Long platformId);

    /**
     * 收尾：只允许从 RUNNING 迁出（{@code AND status = 'RUNNING'}）。
     * <p>第二个收尾请求影响 0 行，而不是把已完成的数字改掉。</p>
     */
    @Update("""
            UPDATE publish_round
            SET status = #{status}, sent = #{sent}, failed = #{failed}, degraded = #{degraded},
                threshold = #{threshold}, error_code = #{errorCode}, error_msg = #{errorMsg},
                summary = #{summary}, end_time = #{endTime}, update_time = #{endTime}
            WHERE id = #{id} AND status = 'RUNNING'
            """)
    int finish(@Param("id") Long id,
               @Param("status") String status,
               @Param("sent") int sent,
               @Param("failed") int failed,
               @Param("degraded") int degraded,
               @Param("threshold") int threshold,
               @Param("errorCode") String errorCode,
               @Param("errorMsg") String errorMsg,
               @Param("summary") String summary,
               @Param("endTime") LocalDateTime endTime);

    /** 僵尸轮次回收：进程被杀留下的 RUNNING，超过阈值时间一律置 FAILED */
    @Update("""
            UPDATE publish_round
            SET status = 'FAILED', error_code = 'ROUND_TIMEOUT',
                error_msg = '轮次未正常收尾，判为超时', end_time = #{now}, update_time = #{now}
            WHERE status = 'RUNNING' AND begin_time < #{before}
            """)
    int failStaleRunning(@Param("before") LocalDateTime before, @Param("now") LocalDateTime now);
}
