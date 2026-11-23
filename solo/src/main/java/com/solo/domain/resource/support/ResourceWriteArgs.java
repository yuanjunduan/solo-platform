package com.solo.domain.resource.support;

/**
 * 从"参数名 + 参数值"里挑出目标资源 id。
 *
 * <p>抽成纯函数是为了能单测：切面里这段逻辑一旦挑错参数，
 * 守卫会去查另一条资源的 {@code source_type}，表现为"有时拦、有时不拦"，
 * 靠人工点页面几乎发现不了。</p>
 */
public final class ResourceWriteArgs {

    private ResourceWriteArgs() {
    }

    /**
     * @param paramNames 方法形参名（编译时保留 -parameters 才有值）
     * @param args       实参
     * @param target     要找的形参名
     * @return 匹配到的正整数 id；找不到 / 值不是数字 / 非正数时返回 {@code null}
     */
    public static Long pickId(String[] paramNames, Object[] args, String target) {
        if (paramNames == null || args == null || target == null) {
            return null;
        }
        for (int i = 0; i < paramNames.length && i < args.length; i++) {
            if (!target.equals(paramNames[i])) {
                continue;
            }
            return toPositiveLong(args[i]);
        }
        return null;
    }

    static Long toPositiveLong(Object value) {
        Long id = switch (value) {
            case null -> null;
            case Number number -> number.longValue();
            case String text -> parse(text);
            default -> null;
        };
        return id == null || id <= 0 ? null : id;
    }

    private static Long parse(String text) {
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
