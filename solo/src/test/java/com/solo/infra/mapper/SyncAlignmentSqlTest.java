package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.solo.domain.sync.model.MutableColumn;
import com.solo.domain.sync.model.SyncColumns;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.mapping.MappedStatement;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集合 SQL 的方言单测（C9 验收的一半：方言 / 列集同源）。
 *
 * <p>不连库，只让 MyBatis 把注解里的动态 SQL 解析成最终文本，然后断言三件事：</p>
 * <ol>
 *   <li><b>方言正确</b>：PG 用 {@code IS DISTINCT FROM} + {@code UPDATE ... FROM}；
 *       MySQL 用 {@code (a <=> b) = 0} + {@code UPDATE ... JOIN}。写反了在真库里就是语法错误。</li>
 *   <li><b>列集同源</b>：{@code SET} 的列数、{@code WHERE} 差异条件的个数，都必须等于
 *       {@link SyncColumns} 里那一份清单的长度。任何一处少一个，就是"改了一边漏了另一边"。</li>
 *   <li><b>列名一致</b>：{@code SET} 用到的本地列，必须与 {@code WHERE} 用到的本地列集合相同。</li>
 * </ol>
 *
 * <p>它拦不住的是"SQL 语法本身在真库能不能跑"，那要靠联调；但它能拦住本轮最容易犯的错——
 * 手改了一个列名却没改另一个。</p>
 */
class SyncAlignmentSqlTest {

    private static final String ORG_UPDATE = "com.solo.infra.mapper.SyncOrgAlignmentMapper.updateDiff";
    private static final String GROUP_UPDATE = "com.solo.infra.mapper.SyncGroupAlignmentMapper.updateDiff";
    private static final String DEVICE_UPDATE = "com.solo.infra.mapper.SyncDeviceAlignmentMapper.updateDiff";
    private static final String CHANNEL_UPDATE = "com.solo.infra.mapper.SyncChannelAlignmentMapper.updateDiff";

    @Test
    void postgresDiffSqlKeepsSameColumnSource() {
        assertDiff("postgresql", ORG_UPDATE, "res_org", SyncColumns.ORG);
        assertDiff("postgresql", GROUP_UPDATE, "res_group", SyncColumns.GROUP);
        assertDiff("postgresql", DEVICE_UPDATE, "res_device", SyncColumns.DEVICE);
        assertDiff("postgresql", CHANNEL_UPDATE, "res_channel", SyncColumns.CHANNEL);
    }

    @Test
    void mysqlDiffSqlKeepsSameColumnSource() {
        assertDiff("mysql", ORG_UPDATE, "res_org", SyncColumns.ORG);
        assertDiff("mysql", GROUP_UPDATE, "res_group", SyncColumns.GROUP);
        assertDiff("mysql", DEVICE_UPDATE, "res_device", SyncColumns.DEVICE);
        assertDiff("mysql", CHANNEL_UPDATE, "res_channel", SyncColumns.CHANNEL);
    }

    /**
     * 新增语句不分支方言，但必须保留各自的闸：业务键去重（id 稳定）、通道载体内连接、
     * 组织/分组按层插入（{@code path} 来自父行）。
     */
    @Test
    void insertStatementsKeepIdempotencyAndCarrierGuard() {
        String device = sql("mysql", "com.solo.infra.mapper.SyncDeviceAlignmentMapper.insertNew",
                params(SyncColumns.DEVICE));
        assertThat(device).contains("NOT EXISTS");
        assertThat(device).contains("device_org_binding");
        assertThat(device).contains("COALESCE");
        assertThat(device).contains("unclassified = 1");

        String channel = sql("mysql", "com.solo.infra.mapper.SyncChannelAlignmentMapper.insertNew",
                params(SyncColumns.CHANNEL));
        assertThat(channel).contains("NOT EXISTS");
        assertThat(channel).contains("JOIN res_device dv");
        assertThat(channel).doesNotContain("LEFT JOIN res_device");

        for (String mapper : List.of("SyncOrgAlignmentMapper", "SyncGroupAlignmentMapper")) {
            String insert = sql("postgresql", "com.solo.infra.mapper." + mapper + ".insertReady",
                    params(SyncColumns.ORG));
            assertThat(insert).as("%s 必须逐层插入", mapper)
                    .contains("NOT EXISTS")
                    .contains("COALESCE(p.path, '/')")
                    .contains("COALESCE(p.level, 0) + 1");
        }
    }

    /**
     * 所有 sync 组件的注解 SQL 都必须先把 XML 实体解码成 {@code <>} 再发给数据库。
     *
     * <p>踩过的坑：{@code SyncOrgPendingMapper.selectDuePlatforms} 把 {@code &lt;=}
     * 写在普通 {@code @Select} 里（没有 {@code <script>} 包裹），MyBatis 不解码，
     * MySQL 收到的是字面量 {@code &lt;}，语法错误最后落在 {@code = ?} 上。
     * 这里直接拿最终 SQL 断言"没有残留实体"，比逐条看源码可靠。</p>
     */
    @Test
    void annotationSqlNeverLeaksXmlEntities() {
        MybatisConfiguration configuration = configuration("mysql");
        List<Class<?>> mappers = List.of(SyncOrgAlignmentMapper.class, SyncGroupAlignmentMapper.class,
                SyncDeviceAlignmentMapper.class, SyncChannelAlignmentMapper.class, SyncOrgPendingMapper.class);
        int checked = 0;
        for (Class<?> mapper : mappers) {
            // 只看源码里手写的注解语句：MP 注入的 BaseMapper 方法由框架生成，不归这里管
            for (Method method : mapper.getDeclaredMethods()) {
                if (!hasSqlAnnotation(method)) {
                    continue;
                }
                String id = mapper.getName() + "." + method.getName();
                String sql = configuration.getMappedStatement(id)
                        .getBoundSql(params(SyncColumns.DEVICE)).getSql();
                assertThat(sql).as("%s 的注解 SQL 残留了 XML 实体，数据库会把它当字面量", id)
                        .doesNotContain("&lt;")
                        .doesNotContain("&gt;")
                        .doesNotContain("&amp;");
                checked++;
            }
        }
        assertThat(checked).as("sync 组件的注解语句都应被扫描到").isGreaterThanOrEqualTo(20);
    }

    private static boolean hasSqlAnnotation(Method method) {
        return method.isAnnotationPresent(Select.class)
                || method.isAnnotationPresent(Update.class)
                || method.isAnnotationPresent(Insert.class)
                || method.isAnnotationPresent(Delete.class);
    }

    private static void assertDiff(String databaseId, String statementId, String table,
                                   List<MutableColumn> columns) {
        String sql = sql(databaseId, statementId, params(columns));
        boolean postgres = "postgresql".equals(databaseId);
        String compare = postgres ? "IS DISTINCT FROM" : "<=>";

        assertThat(sql).as("%s @ %s 必须用本方言的空值安全比较", table, databaseId)
                .contains(compare);
        assertThat(sql).as("%s @ %s 混入了另一方言写法", table, databaseId)
                .doesNotContain(postgres ? "<=>" : "IS DISTINCT FROM");
        assertThat(sql).contains("UPDATE " + table + " d");
        if (postgres) {
            assertThat(sql).contains("FROM sync_staging s");
        } else {
            assertThat(sql).contains("JOIN sync_staging s");
        }

        String setSection = setSection(sql);
        int setCount = count(setSection, Pattern.compile("d\\.\\w+ = s\\.\\w+"));
        assertThat(setCount)
                .as("%s SET 段列数必须等于 SyncColumns 清单长度，SET 段为:%n%s", table, setSection)
                .isEqualTo(columns.size());
        assertThat(setSection).as("%s SET 段要顺带刷新 update_time", table).contains("d.update_time = ?");

        int whereCount = count(sql, Pattern.compile("d\\.\\w+ " + Pattern.quote(compare) + " s\\.\\w+"));
        assertThat(whereCount)
                .as("%s WHERE 差异条件个数必须等于清单长度（同源生成）", table)
                .isEqualTo(columns.size());

        for (MutableColumn column : columns) {
            assertThat(setSection).as("%s 列 %s 必须出现在 SET 段", table, column.resColumn())
                    .contains("d." + column.resColumn() + " = s." + column.stagingColumn());
            assertThat(sql).as("%s 列 %s 必须出现在 WHERE 差异段", table, column.resColumn())
                    .contains("d." + column.resColumn() + " " + compare + " s." + column.stagingColumn());
        }
    }

    /** 截取 {@code SET ...} 到 {@code FROM}/{@code WHERE} 之间的赋值段（两库分支写法不同，终点两者都覆盖） */
    private static String setSection(String sql) {
        Matcher matcher = Pattern.compile("(?s)\\bSET\\b(.*?)(?:\\bFROM\\b|\\bWHERE\\b)").matcher(sql);
        if (!matcher.find()) {
            throw new AssertionError("未在 SQL 中找到 SET 段，实际 SQL:\n" + sql);
        }
        return matcher.group(1);
    }

    private static int count(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        int n = 0;
        while (matcher.find()) {
            n++;
        }
        return n;
    }

    private static Map<String, Object> params(List<MutableColumn> columns) {
        Map<String, Object> params = new HashMap<>();
        params.put("columns", columns);
        params.put("taskId", 1L);
        params.put("tenantId", 1L);
        params.put("platformId", 9L);
        params.put("platformCode", "34020000001320000001");
        params.put("threshold", LocalDateTime.of(2026, 11, 23, 0, 0));
        params.put("now", LocalDateTime.of(2026, 11, 23, 0, 0));
        return params;
    }

    private static MybatisConfiguration configuration(String databaseId) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setDatabaseId(databaseId);
        configuration.addMapper(SyncOrgAlignmentMapper.class);
        configuration.addMapper(SyncGroupAlignmentMapper.class);
        configuration.addMapper(SyncDeviceAlignmentMapper.class);
        configuration.addMapper(SyncChannelAlignmentMapper.class);
        configuration.addMapper(SyncOrgPendingMapper.class);
        return configuration;
    }

    private static String sql(String databaseId, String statementId, Object param) {
        MappedStatement statement = configuration(databaseId).getMappedStatement(statementId);
        return statement.getBoundSql(param).getSql();
    }
}
