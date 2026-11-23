package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.share.model.OutboxEvent;
import com.solo.infra.mapper.projection.OutboxStatusCount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出站事件队列持久化（3.9.5 / C15）。
 *
 * <p><b>seq_no 只增不减</b>：分配用 {@code MAX(seq_no)+1}，回收时调用方必须把当前最大值传进
 * {@link #deleteSentBefore}，只删更小的号——否则号段回退，上级会以为收到旧包而丢弃。</p>
 *
 * <p><b>投递按平台分片</b>：{@link #selectDispatchable} 永远带 {@code platform_id}，
 * 一个上级的积压不能拖住另一个上级。</p>
 */
@Mapper
public interface OutboxMapper extends BaseMapper<OutboxEvent> {

    /**
     * 批量插入一页事件。
     * <p>不用 MP 的逐条 {@code insert}：一次共享 100 条就是 100 次往返，
     * 而它们本来就在同一事务里、序号也已经被同一把平台锁排好。</p>
     */
    @Insert("""
            <script>
            INSERT INTO outbox (id, tenant_id, platform_id, seq_no, event_type, ref_type, ref_id,
                                ref_code, exposed_code, payload, version, status, retry_count,
                                next_retry_at, last_error, round_id, create_time, update_time)
            VALUES
            <foreach collection="rows" item="r" separator=",">
                (#{r.id}, #{r.tenantId}, #{r.platformId}, #{r.seqNo}, #{r.eventType}, #{r.refType},
                 #{r.refId}, #{r.refCode}, #{r.exposedCode}, #{r.payload}, #{r.version}, #{r.status},
                 #{r.retryCount}, #{r.nextRetryAt}, #{r.lastError}, #{r.roundId},
                 #{r.createTime}, #{r.updateTime})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<OutboxEvent> rows);

    /** 平台内最大序号（含已发/已死），空表返回 0 */
    @Select("""
            SELECT COALESCE(MAX(seq_no), 0) FROM outbox WHERE platform_id = #{platformId}
            """)
    long maxSeq(@Param("platformId") Long platformId);

    /**
     * 取一页可投递事件：PENDING/FAILED 且到了重试时间。
     * <p>按 {@code seq_no} 升序 = 同一资源的 ADD 一定先于 UPDATE 发出。</p>
     */
    @Select("""
            SELECT * FROM outbox
            WHERE platform_id = #{platformId}
              AND status IN ('PENDING', 'FAILED')
              AND (next_retry_at IS NULL OR next_retry_at <= #{now})
            ORDER BY seq_no
            LIMIT #{limit}
            """)
    List<OutboxEvent> selectDispatchable(@Param("platformId") Long platformId,
                                         @Param("now") LocalDateTime now,
                                         @Param("limit") int limit);

    /** 待推条数（含未到重试时间）：超过阈值就降级为 REFRESH（C18） */
    @Select("""
            SELECT COUNT(*) FROM outbox
            WHERE platform_id = #{platformId} AND status IN ('PENDING', 'FAILED')
            """)
    long countPending(@Param("platformId") Long platformId);

    /**
     * 有活干的平台（调度器分片入口），按平台轮转。
     *
     * <p><b>必须带上 {@code PAUSED}</b>：离线期间挂起的队列只能靠调度器发现"平台又在线了"才恢复。
     * 漏掉它，积压就要等到下一个新事件到来才会动——那时已经落后几小时了。</p>
     */
    @Select("""
            SELECT DISTINCT platform_id FROM outbox
            WHERE status IN ('PENDING', 'FAILED', 'PAUSED')
              AND (status = 'PAUSED' OR next_retry_at IS NULL OR next_retry_at <= #{now})
            ORDER BY platform_id
            LIMIT #{limit}
            """)
    List<Long> selectPendingPlatformIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 出站队列里出现过的平台（保留期回收按平台推进，号段不回退） */
    @Select("SELECT DISTINCT platform_id FROM outbox ORDER BY platform_id")
    List<Long> selectPlatformIds();

    @Update("""
            UPDATE outbox
            SET status = 'SENT', last_error = NULL, round_id = #{roundId}, update_time = #{now}
            WHERE id = #{id}
            """)
    int markSent(@Param("id") Long id, @Param("roundId") Long roundId, @Param("now") LocalDateTime now);

    /** 失败：retry_count + 1，写下次重试时间；到上限由调用方改走 {@link #markDead} */
    @Update("""
            UPDATE outbox
            SET status = 'FAILED', retry_count = #{retryCount},
                next_retry_at = #{nextRetryAt}, last_error = #{lastError},
                round_id = #{roundId}, update_time = #{now}
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") Long id,
                   @Param("retryCount") int retryCount,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("lastError") String lastError,
                   @Param("roundId") Long roundId,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE outbox
            SET status = 'DEAD', retry_count = retry_count + 1, last_error = #{lastError},
                round_id = #{roundId}, update_time = #{now}
            WHERE id = #{id}
            """)
    int markDead(@Param("id") Long id,
                 @Param("lastError") String lastError,
                 @Param("roundId") Long roundId,
                 @Param("now") LocalDateTime now);

    /**
     * 平台离线：把待推与重试中的事件挂起（PAUSED）。
     * <p>不是删除——恢复后 {@link #resumeByPlatform} 会把它们放回队列，序号不变。</p>
     */
    @Update("""
            UPDATE outbox
            SET status = 'PAUSED', update_time = #{now}
            WHERE platform_id = #{platformId} AND status IN ('PENDING', 'FAILED')
            """)
    int pauseByPlatform(@Param("platformId") Long platformId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE outbox
            SET status = 'PENDING', next_retry_at = NULL, update_time = #{now}
            WHERE platform_id = #{platformId} AND status = 'PAUSED'
            """)
    int resumeByPlatform(@Param("platformId") Long platformId, @Param("now") LocalDateTime now);

    /**
     * 降级收口：一次全量快照已经覆盖了号段内的增量变更，把这些行直接置 SENT。
     *
     * <p><b>只置非 DELETE</b>：快照是 upsert 语义，上级不会因为节点"没出现"就删除它，
     * 所以删除必须逐条发；把 DELETE 一起置 SENT 等于丢掉撤销。</p>
     *
     * <p><b>带上 {@code maxSeq} 上界</b>：快照组装完成之后新入队的事件必须留给下一轮，
     * 否则降级窗口内的变更会被永久吞掉（对账能补，但没必要制造偏差）。</p>
     */
    @Update("""
            UPDATE outbox
            SET status = 'SENT', last_error = NULL, round_id = #{roundId}, update_time = #{now}
            WHERE platform_id = #{platformId} AND status IN ('PENDING', 'FAILED')
              AND event_type <> 'DELETE' AND seq_no <= #{maxSeq}
            """)
    int markBacklogSent(@Param("platformId") Long platformId,
                        @Param("maxSeq") long maxSeq,
                        @Param("roundId") Long roundId,
                        @Param("now") LocalDateTime now);

    /** 撤销后把残留的 PENDING 标记为 SKIPPED 语义：直接置 SENT，避免重连后补发已撤销的节点 */
    @Update("""
            UPDATE outbox
            SET status = 'SENT', last_error = NULL, update_time = #{now}
            WHERE platform_id = #{platformId} AND status IN ('PENDING', 'FAILED') AND event_type = 'ADD'
            """)
    int dropPendingAdds(@Param("platformId") Long platformId, @Param("now") LocalDateTime now);

    /** 某资源在队列里的事件（资源再次变更时判断是否需要合并，或撤销后清理） */
    @Select("""
            SELECT * FROM outbox
            WHERE platform_id = #{platformId} AND ref_type = #{refType} AND ref_code = #{refCode}
              AND status IN ('PENDING', 'FAILED', 'PAUSED')
            ORDER BY seq_no
            """)
    List<OutboxEvent> selectInFlight(@Param("platformId") Long platformId,
                                     @Param("refType") String refType,
                                     @Param("refCode") String refCode);

    /**
     * 人工重试：把 FAILED/DEAD 事件放回待推队列（4.2 推送记录页的"重试"）。
     *
     * <p><b>只动失败与死亡</b>：PENDING 本来就会自动发，把它一起重置只会把
     * {@code retry_count} 清零，让指数退避从头再来；PAUSED 属于平台离线期间的状态，
     * 由调度器在平台恢复在线时统一放回，人工重试不该绕过那条判定。</p>
     *
     * <p>序号不变：重发的是同一个 seq，上级按号判新旧不会把它当新事件。</p>
     */
    @Update("""
            <script>
            UPDATE outbox
            SET status = 'PENDING', retry_count = 0, next_retry_at = NULL,
                last_error = NULL, update_time = #{now}
            WHERE status IN ('FAILED', 'DEAD')
              AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int resetForRetry(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    // ------------------------------------------------------------------
    // 投递记录页
    // ------------------------------------------------------------------

    @Select("""
            <script>
            SELECT o.*, p.name AS platform_name
            FROM outbox o
            LEFT JOIN gb_parent_platform p ON p.id = o.platform_id
            WHERE 1 = 1
            <if test="platformId != null"> AND o.platform_id = #{platformId} </if>
            <if test="status != null and status != ''"> AND o.status = #{status} </if>
            <if test="eventType != null and eventType != ''"> AND o.event_type = #{eventType} </if>
            <if test="keyword != null and keyword != ''">
              AND (o.ref_code LIKE CONCAT('%', #{keyword}, '%')
                   OR o.exposed_code LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY o.platform_id, o.seq_no DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<OutboxEvent> pageWithPlatform(@Param("platformId") Long platformId,
                                       @Param("status") String status,
                                       @Param("eventType") String eventType,
                                       @Param("keyword") String keyword,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM outbox o
            WHERE 1 = 1
            <if test="platformId != null"> AND o.platform_id = #{platformId} </if>
            <if test="status != null and status != ''"> AND o.status = #{status} </if>
            <if test="eventType != null and eventType != ''"> AND o.event_type = #{eventType} </if>
            <if test="keyword != null and keyword != ''">
              AND (o.ref_code LIKE CONCAT('%', #{keyword}, '%')
                   OR o.exposed_code LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            </script>
            """)
    long countByFilter(@Param("platformId") Long platformId,
                       @Param("status") String status,
                       @Param("eventType") String eventType,
                       @Param("keyword") String keyword);

    /** 统计各状态条数（首页/方舟页的四个数字一次取回） */
    @Select("""
            <script>
            SELECT status, COUNT(*) AS total FROM outbox
            <if test="platformId != null"> WHERE platform_id = #{platformId} </if>
            GROUP BY status
            </script>
            """)
    List<OutboxStatusCount> countGroupByStatus(@Param("platformId") Long platformId);

    /**
     * 回收已投递事件：只删更小的号，<b>保留平台内最大号那一行</b>——号段不回退。
     *
     * @param keepAboveSeq 调用方先取 {@link #maxSeq} 再传进来，避免同表子查询在 MySQL 上被禁
     */
    @Update("""
            DELETE FROM outbox
            WHERE platform_id = #{platformId} AND status = 'SENT'
              AND seq_no < #{keepAboveSeq} AND update_time < #{before}
            """)
    int deleteSentBefore(@Param("platformId") Long platformId,
                         @Param("keepAboveSeq") long keepAboveSeq,
                         @Param("before") LocalDateTime before);
}
