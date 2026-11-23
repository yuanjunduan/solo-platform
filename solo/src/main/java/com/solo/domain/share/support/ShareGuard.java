package com.solo.domain.share.support;

/**
 * 四道防环（C20 / 3.10.2）。
 *
 * <p>全部是纯函数：<b>保存共享时</b>与<b>投递前</b>各判一次，命中即拒绝。
 * 保存时拒绝能给明确文案（"该资源来自平台 A，不能再共享回 A"），
 * 等推送失败再报错时用户已经以为共享成功了。</p>
 *
 * <p>四道检查各自拦一类事故，缺一不可：</p>
 * <table>
 *   <tr><td>REVERSE</td><td>把从 A 收来的资源推回 A（最常见、最致命的环）</td></tr>
 *   <tr><td>ORIGIN_CHAIN</td><td>A→B→本平台，本平台又推回 A</td></tr>
 *   <tr><td>HOP_LIMIT</td><td>长链路无限延伸</td></tr>
 *   <tr><td>SELF_DOMAIN</td><td>绕一圈回到自己（编码泄露 / 配置错误）</td></tr>
 * </table>
 */
public final class ShareGuard {

    /** 默认最大跳数（附录 B #8） */
    public static final int DEFAULT_MAX_HOP = 3;

    public enum Violation {
        /** 目标平台 == 资源直接来源 */
        REVERSE,
        /** 目标平台出现在资源的来源链里 */
        ORIGIN_CHAIN,
        /** 跳数超限 */
        HOP_LIMIT,
        /** 资源码属于本平台域，却不是本地资源 */
        SELF_DOMAIN
    }

    /**
     * 资源的来源侧事实。
     *
     * @param sourcePlatformId   直接来源平台 id（{@code platform.id}），0/NULL = 本地
     * @param sourcePlatformCode 直接来源平台的国标码（{@code platform.code}），用于跨表比对
     * @param originChain        平台国标码逗号串（最上游 → 直接来源），可为空；
     *                           旧数据里可能存的是本地 id，比对时两者都认
     * @param hopCount           已发生的级联跳数
     * @param code               资源原始编码
     */
    public record Origin(Long sourcePlatformId, String sourcePlatformCode,
                         String originChain, Integer hopCount, String code) {

        public static Origin local(String code) {
            return new Origin(0L, null, "", 0, code);
        }
    }

    private ShareGuard() {
    }

    /**
     * 判一次共享是否成环。
     *
     * @param targetPlatformId 目标上级平台（{@code gb_parent_platform.id}）
     * @param targetGbCode     目标上级的国标码；与来源平台的 {@code platform.code} 比对，
     *                         因为"上级平台"与"下级来源平台"在本系统是两张表，id 空间不同
     * @param origin           资源来源事实
     * @param localDomain      本平台 SIP 域（10 位）
     * @param maxHop           最大跳数，{@code <= 0} 取默认 3
     * @return 命中的违规项；{@code null} = 放行
     */
    public static Violation check(Long targetPlatformId, String targetGbCode, Origin origin,
                                  String localDomain, int maxHop) {
        if (targetPlatformId == null || origin == null) {
            return null;
        }
        long source = origin.sourcePlatformId() == null ? 0L : origin.sourcePlatformId();
        long hop = origin.hopCount() == null ? 0L : origin.hopCount();

        // ① 反向共享：id 相同，或国标码相同（跨两张表识别同一个物理平台）
        if (source != 0L && (source == targetPlatformId
                || sameCode(origin.sourcePlatformCode(), targetGbCode))) {
            return Violation.REVERSE;
        }
        // ② 来源链
        if (containsPlatform(origin.originChain(), targetPlatformId, targetGbCode)) {
            return Violation.ORIGIN_CHAIN;
        }
        // ③ 跳数：本地资源 hop=0，推给上级后变 1；超过上限就拒绝
        int limit = maxHop <= 0 ? DEFAULT_MAX_HOP : maxHop;
        if (hop + 1 > limit) {
            return Violation.HOP_LIMIT;
        }
        // ④ 自身域：码是本平台域，却不是本地资源 → 绕了一圈回来
        if (source != 0L && ExposedCode.belongsTo(origin.code(), localDomain)) {
            return Violation.SELF_DOMAIN;
        }
        return null;
    }

    /** 只按 id 判定（来源侧没有国标码时的退化入口） */
    public static Violation check(Long targetPlatformId, Origin origin,
                                  String localDomain, int maxHop) {
        return check(targetPlatformId, null, origin, localDomain, maxHop);
    }

    /** 给用户看的文案（4.7：所有异常必须给出"下一步能点什么"） */
    public static String message(Violation violation, String targetName, String sourceName) {
        String target = targetName == null ? "目标上级" : targetName;
        String source = sourceName == null ? "来源平台" : sourceName;
        if (violation == null) {
            return "";
        }
        return switch (violation) {
            case REVERSE -> "该资源来自「" + source + "」，不能再共享回同一个平台「" + target
                    + "」。请换一个上级，或把该资源从来源平台侧重新分配。";
            case ORIGIN_CHAIN -> "该资源的来源链里已经包含「" + target
                    + "」，再共享回去会形成环路。请检查上级的层级配置。";
            case HOP_LIMIT -> "级联跳数已达上限，不能再往「" + target
                    + "」转发。如确需更长链路，请调整 publish.max-hop 后重试。";
            case SELF_DOMAIN -> "该资源的编码属于本平台域，但来源是「" + source
                    + "」，共享给「" + target + "」会被上级当成重复节点。请先核对来源平台配置。";
        };
    }

    private static boolean sameCode(String a, String b) {
        return a != null && b != null && !a.isBlank() && a.trim().equals(b.trim());
    }

    private static boolean containsPlatform(String originChain, Long platformId, String gbCode) {
        if (originChain == null || originChain.isBlank()) {
            return false;
        }
        String targetId = platformId == null ? null : String.valueOf(platformId);
        for (String hop : originChain.split(",")) {
            String v = hop == null ? "" : hop.trim();
            if (v.isEmpty()) {
                continue;
            }
            if (targetId != null && v.equals(targetId)) {
                return true;
            }
            if (sameCode(v, gbCode)) {
                return true;
            }
        }
        return false;
    }
}
