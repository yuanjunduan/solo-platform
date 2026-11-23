package com.solo.domain.share.model;

/**
 * 共享范围的两种模式（3.9.2）。
 *
 * <p>纯快照满足不了"自动跟随变化"，纯规则满足不了"精细选择"，所以两种并存：
 * 声明式（SUBTREE 规则）+ 覆盖式（enabled=0 的排除行）。</p>
 */
public final class ShareScopeMode {

    /** 用户逐个勾选的资源 */
    public static final String EXPLICIT = "EXPLICIT";

    /** 记一条"组织 X（含子树）"，新资源命中前缀自动派生 */
    public static final String SUBTREE = "SUBTREE";

    private ShareScopeMode() {
    }
}
