package com.solo.infra.interceptor;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 持久层只读检查的判定逻辑（3.6.5 第三层），刻意写成纯函数。
 *
 * <p>这一层是"给后来人兜底"的：任何一条 UPDATE 只要没带来源条件，就可能一次改掉
 * 别人平台的镜像。判定逻辑本身要是错的，等于装了个只会刷日志的假门禁，所以单测比别处更重要。</p>
 *
 * <p><b>两类违规</b>：</p>
 * <ol>
 *   <li><b>硬删</b>：{@code DELETE FROM res_*} —— 要求一律软删（{@code UPDATE status}），
 *       真实删除会让 {@code out_share}、{@code out_code_map}、播放会话的引用悬空；</li>
 *   <li><b>无来源条件的写</b>：SQL 里没出现 {@code source_type} —— 意味着这条语句
 *       可能跨来源改行（包括改掉 INBOUND 行的 identity 字段）。</li>
 * </ol>
 *
 * <p>用"文本里有没有 source_type"而不是解析 SQL 语法：四种写法（MyBatis-Plus 的
 * updateById / LambdaUpdateWrapper / 注解 SQL / XML）最终都落到同一段 JDBC SQL 上，
 * 文本判断覆盖得住，而且零依赖。</p>
 */
public final class ResourceWriteInspector {

    /** 受保护的四张资源表 */
    private static final List<String> PROTECTED_TABLES = List.of("res_org", "res_group", "res_device", "res_channel");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ResourceWriteInspector() {
    }

    /**
     * @param mappedStatementId MyBatis 语句 id（用于日志定位到具体 Mapper 方法）
     * @param rawSql            即将执行的 SQL
     * @return 违规说明；合规时返回 {@code null}
     */
    public static String inspect(String mappedStatementId, String rawSql) {
        if (rawSql == null) {
            return null;
        }
        String sql = WHITESPACE.matcher(rawSql).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
        if (sql.isEmpty()) {
            return null;
        }

        boolean isDelete = sql.startsWith("delete from ");
        boolean isUpdate = sql.startsWith("update ");
        if (!isDelete && !isUpdate) {
            // INSERT 不管：新增行自带来源列；UPSERT 的冲突分支改的是同一业务键
            return null;
        }

        String table = targetTable(sql, isDelete);
        if (table == null || !PROTECTED_TABLES.contains(table)) {
            return null;
        }

        if (isDelete) {
            return "对 " + table + " 执行了硬删（要求改为软删 UPDATE status） statement=" + mappedStatementId;
        }
        if (!sql.contains("source_type")) {
            return "写 " + table + " 的 UPDATE 未带 source_type 条件，可能跨来源改行 statement=" + mappedStatementId;
        }
        return null;
    }

    /** 从 "update res_org a set ..." / "delete from res_org where ..." 里取表名 */
    private static String targetTable(String sql, boolean isDelete) {
        int from = isDelete ? "delete from ".length() : "update ".length();
        int end = from;
        while (end < sql.length() && !Character.isWhitespace(sql.charAt(end))) {
            end++;
        }
        if (end <= from) {
            return null;
        }
        String token = sql.substring(from, end);
        // 去掉可能带的反引号/双引号与库名前缀
        token = token.replace("`", "").replace("\"", "");
        int dot = token.lastIndexOf('.');
        return dot >= 0 ? token.substring(dot + 1) : token;
    }
}
