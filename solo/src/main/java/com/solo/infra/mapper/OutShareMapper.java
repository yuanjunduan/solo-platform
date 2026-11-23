package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.share.model.OutShare;
import com.solo.infra.mapper.projection.OutShareCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 共享关系持久化（3.9.1 / 3.9.2）。
 *
 * <p>两条纪律写进 SQL 而不是注释里：</p>
 * <ul>
 *   <li><b>撤销不改行数</b>：{@code markRefDead} 只置 {@code ref_alive=0}，删行会丢掉"曾共享给谁"；</li>
 *   <li><b>剔除不新增行</b>：优先把已有资源行 {@code enabled=0}；只有在行还不存在时才插抑制行。</li>
 * </ul>
 */
@Mapper
public interface OutShareMapper extends BaseMapper<OutShare> {

    /**
     * 管理页列表：一次联表带出平台名/组名/资源名。
     * <p>资源名按 {@code ref_type} 三路 LEFT JOIN，避免前端拿到一串 id 再各自回查。</p>
     */
    @Select("""
            <script>
            SELECT s.*, p.name AS platform_name, g.name AS group_name,
                   COALESCE(c.name, d.name, o.name) AS ref_name
            FROM out_share s
            LEFT JOIN gb_parent_platform p ON p.id = s.platform_id
            LEFT JOIN out_group g ON g.id = s.group_id
            LEFT JOIN res_channel c ON s.ref_type = 'CHANNEL' AND c.id = s.ref_id
            LEFT JOIN res_device d ON s.ref_type = 'DEVICE' AND d.id = s.ref_id
            LEFT JOIN res_org o ON s.ref_type = 'ORG' AND o.id = s.ref_id
            WHERE 1 = 1
            <if test="platformId != null"> AND s.platform_id = #{platformId} </if>
            <if test="refType != null and refType != ''"> AND s.ref_type = #{refType} </if>
            <if test="scopeMode != null and scopeMode != ''"> AND s.scope_mode = #{scopeMode} </if>
            <if test="status != null and status != ''"> AND s.status = #{status} </if>
            <if test="enabled != null"> AND s.enabled = #{enabled} </if>
            <if test="refAlive != null"> AND s.ref_alive = #{refAlive} </if>
            <if test="keyword != null and keyword != ''">
              AND (s.ref_code LIKE CONCAT('%', #{keyword}, '%') OR s.org_path LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY s.platform_id, s.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<OutShare> pageWithRef(@Param("platformId") Long platformId,
                               @Param("refType") String refType,
                               @Param("scopeMode") String scopeMode,
                               @Param("status") String status,
                               @Param("enabled") Integer enabled,
                               @Param("refAlive") Integer refAlive,
                               @Param("keyword") String keyword,
                               @Param("offset") int offset,
                               @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM out_share s
            WHERE 1 = 1
            <if test="platformId != null"> AND s.platform_id = #{platformId} </if>
            <if test="refType != null and refType != ''"> AND s.ref_type = #{refType} </if>
            <if test="scopeMode != null and scopeMode != ''"> AND s.scope_mode = #{scopeMode} </if>
            <if test="status != null and status != ''"> AND s.status = #{status} </if>
            <if test="enabled != null"> AND s.enabled = #{enabled} </if>
            <if test="refAlive != null"> AND s.ref_alive = #{refAlive} </if>
            <if test="keyword != null and keyword != ''">
              AND (s.ref_code LIKE CONCAT('%', #{keyword}, '%') OR s.org_path LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            </script>
            """)
    long countByFilter(@Param("platformId") Long platformId,
                       @Param("refType") String refType,
                       @Param("scopeMode") String scopeMode,
                       @Param("status") String status,
                       @Param("enabled") Integer enabled,
                       @Param("refAlive") Integer refAlive,
                       @Param("keyword") String keyword);

    /** 该上级下的全部共享行（含已剔除/已撤销），派生重建时一次取全 */
    @Select("""
            SELECT * FROM out_share WHERE platform_id = #{platformId} ORDER BY id
            """)
    List<OutShare> selectByPlatform(@Param("platformId") Long platformId);

    /** 配过共享的上级（对账按平台逐个跑：一个平台失败不拖住其它平台） */
    @Select("SELECT DISTINCT platform_id FROM out_share ORDER BY platform_id")
    List<Long> selectPlatformIds();

    /** 该上级下所有生效的子树规则（派生用） */
    @Select("""
            SELECT * FROM out_share
            WHERE platform_id = #{platformId} AND scope_mode = 'SUBTREE'
              AND ref_type = 'ORG' AND enabled = 1 AND ref_alive = 1
            ORDER BY id
            """)
    List<OutShare> selectRules(@Param("platformId") Long platformId);

    /** 全部生效行（组装目录、对账取"应在清单"用） */
    @Select("""
            SELECT * FROM out_share
            WHERE platform_id = #{platformId} AND enabled = 1 AND ref_alive = 1
            ORDER BY ref_type, id
            """)
    List<OutShare> selectUsable(@Param("platformId") Long platformId);

    @Select("""
            SELECT COUNT(*) FROM out_share
            WHERE platform_id = #{platformId} AND enabled = 1 AND ref_alive = 1
            """)
    long countUsable(@Param("platformId") Long platformId);

    /** 幂等键（uk_out_share）反查 */
    @Select("""
            SELECT * FROM out_share
            WHERE platform_id = #{platformId}
              AND ref_source_type = #{refSourceType}
              AND ref_source_platform_id = #{refSourcePlatformId}
              AND ref_code = #{refCode}
            LIMIT 1
            """)
    OutShare selectByUnique(@Param("platformId") Long platformId,
                            @Param("refSourceType") String refSourceType,
                            @Param("refSourcePlatformId") Long refSourcePlatformId,
                            @Param("refCode") String refCode);

    /** 某资源在指定上级下的共享行（含已剔除/已撤销，决策要看得到它们） */
    @Select("""
            SELECT * FROM out_share
            WHERE platform_id = #{platformId} AND ref_type = #{refType} AND ref_id = #{refId}
            ORDER BY id
            """)
    List<OutShare> selectByRefInPlatform(@Param("platformId") Long platformId,
                                         @Param("refType") String refType,
                                         @Param("refId") Long refId);

    /**
     * 一页资源的共享行（编排页"选择资源"一次回填勾选态与剔除态）。
     * <p>逐条 {@link #selectByRefInPlatform} 会变成 N+1：一页 50 条通道就是 50 次往返。</p>
     */
    @Select("""
            <script>
            SELECT * FROM out_share
            WHERE platform_id = #{platformId} AND ref_type = #{refType}
              AND ref_id IN
              <foreach collection="refIds" item="refId" open="(" separator="," close=")">#{refId}</foreach>
            ORDER BY id
            </script>
            """)
    List<OutShare> selectByRefIds(@Param("platformId") Long platformId,
                                  @Param("refType") String refType,
                                  @Param("refIds") List<Long> refIds);

    /**
     * 平台汇总卡片的四个数字一次取回（3.9.6 / 4.2 共享总览）。
     * <p>用 {@code COUNT(CASE WHEN ...)} 而不是 {@code SUM}：SUM 在 MySQL 返回 DECIMAL、
     * 空表返回 NULL，映射成 long 要额外兜底；COUNT 两个方言都返回整数、空表返回 0。</p>
     */
    @Select("""
            SELECT
              COUNT(CASE WHEN ref_type = 'CHANNEL' AND enabled = 1 AND ref_alive = 1 THEN 1 END) AS channel_count,
              COUNT(CASE WHEN ref_type = 'ORG' AND scope_mode = 'SUBTREE' AND enabled = 1 AND ref_alive = 1 THEN 1 END) AS rule_count,
              COUNT(CASE WHEN ref_type = 'CHANNEL' AND enabled = 0 AND ref_alive = 1 THEN 1 END) AS excluded_count
            FROM out_share
            WHERE platform_id = #{platformId}
            """)
    OutShareCount countByPlatform(@Param("platformId") Long platformId);

    /**
     * 某资源被共享到了哪些上级（跨平台）。
     * <p>资源 REMOVED 时按它逐平台发 DELETE（C12 第二步），所以这里不能带平台过滤。</p>
     */
    @Select("""
            SELECT * FROM out_share
            WHERE ref_type = #{refType} AND ref_id = #{refId} AND ref_alive = 1
            ORDER BY platform_id
            """)
    List<OutShare> selectAliveByRef(@Param("refType") String refType, @Param("refId") Long refId);

    /** 子树内的生效行（组织 REMOVED 时按路径前缀圈定） */
    @Select("""
            SELECT * FROM out_share
            WHERE ref_alive = 1 AND org_path LIKE CONCAT(#{pathPrefix}, '%')
            ORDER BY platform_id, id
            """)
    List<OutShare> selectAliveByPath(@Param("pathPrefix") String pathPrefix);

    // ------------------------------------------------------------------
    // 写操作：全部显式 SET，不用 updateById（MP 会跳过 null，
    // 而"清空 last_error"恰恰是把 null 写进去）
    // ------------------------------------------------------------------

    @Update("""
            UPDATE out_share
            SET enabled = #{enabled}, operator = #{operator}, update_time = #{now}
            WHERE id = #{id}
            """)
    int updateEnabled(@Param("id") Long id,
                      @Param("enabled") Integer enabled,
                      @Param("operator") String operator,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE out_share
            SET group_id = #{groupId}, update_time = #{now}
            WHERE id = #{id}
            """)
    int updateGroup(@Param("id") Long id, @Param("groupId") Long groupId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE out_share
            SET status = #{status}, last_error = #{lastError}, update_time = #{now}
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("lastError") String lastError,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE out_share
            SET status = #{status}, last_error = NULL, last_pushed_at = #{now}, update_time = #{now}
            WHERE id = #{id}
            """)
    int markPushed(@Param("id") Long id, @Param("status") String status, @Param("now") LocalDateTime now);

    /**
     * 单条撤销：只置 {@code ref_alive=0} 并把状态改为 {@code REVOKING}。
     * <p>按 id 定位是刻意的：一条通道在规则取消后可能同时被"派生行撤销"与"用户撤销"命中，
     * 用业务键批量置位会把两条路径的计数搅在一起。</p>
     */
    @Update("""
            UPDATE out_share
            SET ref_alive = 0, status = #{status}, operator = #{operator}, update_time = #{now}
            WHERE id = #{id}
            """)
    int markRefDeadById(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("operator") String operator,
                        @Param("now") LocalDateTime now);

    /** 整平台撤销时清掉挂载关系：树随后被删，留着 group_id 只会变成悬空引用 */
    @Update("""
            UPDATE out_share
            SET group_id = NULL, update_time = #{now}
            WHERE platform_id = #{platformId}
            """)
    int clearGroupIdByPlatform(@Param("platformId") Long platformId, @Param("now") LocalDateTime now);

    /** 组织子树消失：按路径前缀一次性置位 */
    @Update("""
            UPDATE out_share
            SET ref_alive = 0, update_time = #{now}
            WHERE ref_alive = 1 AND org_path LIKE CONCAT(#{pathPrefix}, '%')
            """)
    int markRefDeadByPath(@Param("pathPrefix") String pathPrefix, @Param("now") LocalDateTime now);

    /** 撤销整平台共享：全部置 ref_alive=0 与 REVOKING，等投递完成后由对账收尾 */
    @Update("""
            UPDATE out_share
            SET ref_alive = 0, status = #{status}, update_time = #{now}
            WHERE platform_id = #{platformId} AND ref_alive = 1
            """)
    int revokeByPlatform(@Param("platformId") Long platformId,
                         @Param("status") String status,
                         @Param("now") LocalDateTime now);

    /**
     * 平台汇总状态用：该上级下所有共享项的状态。
     * <p>排除 {@code ORG} 规则行：规则只是"声明"，本身没有可投递的节点，
     * 让它参与"取最差"会让每个平台永远显示最差状态。</p>
     */
    @Select("""
            SELECT status FROM out_share
            WHERE platform_id = #{platformId} AND ref_alive = 1 AND ref_type <> 'ORG'
            """)
    List<String> selectStatuses(@Param("platformId") Long platformId);

    // ------------------------------------------------------------------
    // C12 消失联动 / 对账 / 投递结果回写
    // ------------------------------------------------------------------

    /**
     * 上游已 REMOVED、但共享仍标记存活的通道行（C12 第 2 步的输入）。
     * <p>按<b>来源平台</b>圈定：一次同步只影响它自己推来的资源，不会误伤本地自建与其它来源。</p>
     */
    @Select("""
            SELECT s.* FROM out_share s
            JOIN res_channel c ON c.id = s.ref_id
            WHERE s.ref_type = 'CHANNEL' AND s.ref_alive = 1
              AND s.ref_source_platform_id = #{sourcePlatformId}
              AND c.status = 'REMOVED'
            ORDER BY s.platform_id, s.id
            """)
    List<OutShare> selectBrokenBySource(@Param("sourcePlatformId") Long sourcePlatformId);

    /**
     * 上游资源重新出现、且撤销是"系统因资源消失而做"的行（恢复路径）。
     * <p>靠 {@code operator} 区分：用户主动撤销的行资源本来就在（ACTIVE），
     * 不加这层过滤会把用户撤销的共享自动复活。</p>
     */
    @Select("""
            SELECT s.* FROM out_share s
            JOIN res_channel c ON c.id = s.ref_id
            WHERE s.ref_type = 'CHANNEL' AND s.ref_alive = 0
              AND s.ref_source_platform_id = #{sourcePlatformId}
              AND s.operator = #{operator}
              AND c.status = 'ACTIVE'
            ORDER BY s.platform_id, s.id
            """)
    List<OutShare> selectRestorableBySource(@Param("sourcePlatformId") Long sourcePlatformId,
                                            @Param("operator") String operator);

    /** 规则行：来源组织已 REMOVED（规则随之失效，由派生重算清理其派生物） */
    @Select("""
            SELECT s.* FROM out_share s
            JOIN res_org o ON o.id = s.ref_id
            WHERE s.ref_type = 'ORG' AND s.ref_alive = 1 AND s.enabled = 1
              AND s.ref_source_platform_id = #{sourcePlatformId}
              AND o.status = 'REMOVED'
            ORDER BY s.platform_id, s.id
            """)
    List<OutShare> selectDeadRulesBySource(@Param("sourcePlatformId") Long sourcePlatformId);

    /** 资源恢复：只置 ref_alive 与状态，不改 enabled（用户剔除过就继续剔除） */
    @Update("""
            UPDATE out_share
            SET ref_alive = 1, status = 'PENDING', operator = NULL, update_time = #{now}
            WHERE id = #{id}
            """)
    int markRestored(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 规则行恢复：来源组织重新出现（不再是 REMOVED），且撤销是系统做的。
     * <p>恢复规则后由 {@code rebuildDerived} 把它派生的通道一并放回来——
     * 派生行不需要在这里逐条恢复，否则同一条通道会被两条路径各推一次 ADD。</p>
     */
    @Select("""
            SELECT s.* FROM out_share s
            JOIN res_org o ON o.id = s.ref_id
            WHERE s.ref_type = 'ORG' AND s.ref_alive = 0
              AND s.ref_source_platform_id = #{sourcePlatformId}
              AND s.operator = #{operator}
              AND o.status <> 'REMOVED'
            ORDER BY s.platform_id, s.id
            """)
    List<OutShare> selectRestorableRulesBySource(@Param("sourcePlatformId") Long sourcePlatformId,
                                                 @Param("operator") String operator);

    /**
     * 可见通道行：暴露码反查的最后一跳（只认 {@code enabled=1 AND ref_alive=1}）。
     * <p>撤销后、以及 DELETE 推送成功前，反查必须查不到——上级此时点播要被拒绝（C12）。</p>
     */
    @Select("""
            SELECT * FROM out_share
            WHERE platform_id = #{platformId} AND ref_type = 'CHANNEL'
              AND ref_code = #{refCode} AND enabled = 1 AND ref_alive = 1
            LIMIT 1
            """)
    OutShare selectVisibleByCode(@Param("platformId") Long platformId,
                                 @Param("refCode") String refCode);

    /** 撤销中的通道行（REFRESH 前要补发 DEL：快照是 upsert，删不掉已消失的节点） */
    @Select("""
            SELECT * FROM out_share
            WHERE platform_id = #{platformId} AND ref_alive = 0
              AND status = 'REVOKING' AND ref_type = 'CHANNEL'
            ORDER BY id
            """)
    List<OutShare> selectRevoking(@Param("platformId") Long platformId);

    /** REFRESH 投递成功：该平台的可见通道行全部视为已同步 */
    @Update("""
            UPDATE out_share
            SET status = 'SYNCED', last_error = NULL, last_pushed_at = #{now}, update_time = #{now}
            WHERE platform_id = #{platformId} AND ref_alive = 1 AND enabled = 1 AND ref_type <> 'ORG'
            """)
    int markAllPushed(@Param("platformId") Long platformId, @Param("now") LocalDateTime now);

    /**
     * 单条撤销投递成功（批量 DELETE 里逐个节点确认用）。
     * <p>不做"整平台收尾"：一个批量事件只覆盖它 payload 里的节点，
     * 把别的"仍在队列里等删除"的行一起标成 SYNCED，等于把它们的 DELETE 吞掉。</p>
     */
    @Update("""
            UPDATE out_share
            SET status = 'SYNCED', last_error = NULL, last_pushed_at = #{now}, update_time = #{now}
            WHERE platform_id = #{platformId} AND ref_type = 'CHANNEL'
              AND ref_alive = 0 AND status = 'REVOKING' AND ref_code = #{refCode}
            """)
    int markRevokedDeliveredByCode(@Param("platformId") Long platformId,
                                   @Param("refCode") String refCode,
                                   @Param("now") LocalDateTime now);

    /** 投递失败：整平台可见行与撤销行统一记错误（状态由失败次数决定，DEAD 由调用方单独置） */
    @Update("""
            UPDATE out_share
            SET status = #{status}, last_error = #{lastError}, update_time = #{now}
            WHERE platform_id = #{platformId} AND status <> #{status}
              AND (ref_alive = 0 OR (ref_alive = 1 AND enabled = 1))
              AND ref_type <> 'ORG'
            """)
    int markAllStatus(@Param("platformId") Long platformId,
                      @Param("status") String status,
                      @Param("lastError") String lastError,
                      @Param("now") LocalDateTime now);

    // ------------------------------------------------------------------
    // 状态跟随（6.6：上游离线 → 上级收到 OFF）
    // ------------------------------------------------------------------

    /**
     * 在线状态与"上次推给上级的值"不一致的共享通道。
     *
     * <p>{@code stored_status} 与 {@code ref_online_status} 同时带出：前者是库里存的上次值，
     * 后者是当前的 {@code res_channel.online_status}。只有 {@code stored_status} 非空才是真变化；
     * 为空的（含刚升级上来的历史行）只落基线，不补发事件——否则一上库就会给上级发一轮 OFF。</p>
     *
     * <p>排除 {@code status <> ACTIVE}（STALE 是"本轮暂未出现"，不该跟着抖）与已剔除/已撤销的行。</p>
     */
    @Select("""
            SELECT s.*, s.ref_online_status AS stored_status, c.online_status AS ref_online_status
            FROM out_share s
            JOIN res_channel c ON c.id = s.ref_id
            WHERE s.ref_type = 'CHANNEL' AND s.ref_alive = 1 AND s.enabled = 1
              AND s.ref_source_platform_id = #{sourcePlatformId}
              AND c.status = 'ACTIVE'
              AND (s.ref_online_status IS NULL OR s.ref_online_status <> COALESCE(c.online_status, 'ON'))
            ORDER BY s.platform_id, s.id
            LIMIT #{limit}
            """)
    List<OutShare> selectStatusChangedBySource(@Param("sourcePlatformId") Long sourcePlatformId,
                                               @Param("limit") int limit);

    /** 落基线 / 推进上次状态；同时置 PENDING，让总览页看得到"有一条状态事件在排队" */
    @Update("""
            UPDATE out_share
            SET ref_online_status = #{onlineStatus}, status = 'PENDING', update_time = #{now}
            WHERE id = #{id}
            """)
    int updateRefOnlineStatus(@Param("id") Long id,
                              @Param("onlineStatus") String onlineStatus,
                              @Param("now") LocalDateTime now);
}

