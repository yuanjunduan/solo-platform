package com.solo.domain.share.support;

import com.solo.domain.resource.support.ResourcePath;

import java.util.Collection;

/**
 * 共享范围解析（3.9.2 的 SUBTREE 命中判定）。
 *
 * <p>只做一件事：判断"某个资源所在的组织路径"是否落在"某条规则的组织路径"之下。
 * 抽成纯函数是为了让"派生哪些资源"这条最容易被改错的逻辑有单测可依，
 * 而不是在 Service 里拼 {@code startsWith}。</p>
 *
 * <p>路径一律先规范化（首尾斜杠）：{@code /1/5/} 不能吞掉 {@code /1/50/}，
 * 这个误匹配在组织 id 跨量级时一定会出现。</p>
 */
public final class ShareScopeResolver {

    private ShareScopeResolver() {
    }

    /** 规则：{@code rulePath} 是否覆盖 {@code candidatePath}（含自身；规则为根则覆盖全部） */
    public static boolean covers(String rulePath, String candidatePath) {
        if (candidatePath == null || candidatePath.isBlank()) {
            return false;
        }
        String rule = ResourcePath.normalize(rulePath);
        if ("/".equals(rule)) {
            // 根规则 = 整棵树；没有任何组织路径的资源由调用方决定要不要收
            return true;
        }
        String candidate = ResourcePath.normalize(candidatePath);
        return candidate.startsWith(rule);
    }

    /** 多条规则任一命中即可（剔除行在调用方另行判断，优先级高于规则） */
    public static boolean coversAny(Collection<String> rulePaths, String candidatePath) {
        if (rulePaths == null || rulePaths.isEmpty()) {
            return false;
        }
        for (String rule : rulePaths) {
            if (covers(rule, candidatePath)) {
                return true;
            }
        }
        return false;
    }
}
