package com.solo.domain.resource.support;

/**
 * 物化路径工具（纯函数，无 Spring 依赖）。
 *
 * <p>约定：路径**首尾都带斜杠**，且包含自身，例如 {@code /1/5/9/}。
 * 这样 {@code path LIKE '/1/5/%'} 既能命中子树，又不会误命中兄弟 {@code /1/50/}
 * （少了尾部斜杠就会出现这个经典误匹配）。</p>
 *
 * <p>层级与路径强绑定：{@code level} 由路径段数推导，不单独维护两套真值。</p>
 */
public final class ResourcePath {

    private ResourcePath() {
    }

    /** 根节点路径：{@code /selfId/} */
    public static String ofRoot(Long selfId) {
        return "/" + selfId + "/";
    }

    /** 子节点路径：父路径 + 自身 id + "/"（父路径必须已规范） */
    public static String ofChild(String parentPath, Long selfId) {
        return normalize(parentPath) + selfId + "/";
    }

    /**
     * 规范化：补首尾斜杠，去重连续斜杠。
     * <p>历史脏数据里出现过 {@code 1/5} 与 {@code /1/5} 两种写法，
     * 统一在入口规范一次，后续所有前缀比较才有意义。</p>
     */
    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String trimmed = path.trim().replaceAll("/{2,}", "/");
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        if (!trimmed.endsWith("/")) {
            trimmed = trimmed + "/";
        }
        return trimmed;
    }

    /** 层级 = 路径里的 id 段数；{@code /1/} = 1，{@code /1/5/} = 2，空路径 = 0 */
    public static int levelOf(String path) {
        String normalized = normalize(path);
        if ("/".equals(normalized)) {
            return 0;
        }
        return (int) normalized.chars().filter(ch -> ch == '/').count() - 1;
    }

    /** {@code candidate} 是否在 {@code ancestor} 之下（不含自身） */
    public static boolean isUnder(String candidate, String ancestor) {
        String c = normalize(candidate);
        String a = normalize(ancestor);
        return c.startsWith(a) && !c.equals(a);
    }

    /** 取父路径：{@code /1/5/9/} → {@code /1/5/}；根节点返回 {@code /} */
    public static String parentOf(String path) {
        String normalized = normalize(path);
        if ("/".equals(normalized)) {
            return "/";
        }
        String withoutTrailing = normalized.substring(0, normalized.length() - 1);
        int idx = withoutTrailing.lastIndexOf('/');
        return idx <= 0 ? "/" : withoutTrailing.substring(0, idx + 1);
    }
}
