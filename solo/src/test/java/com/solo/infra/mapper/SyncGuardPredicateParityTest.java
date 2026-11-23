package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.mapping.MappedStatement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸门谓词一致性（C11 的"影子对比"里不需要数据库的那一半）。
 *
 * <p>G2 的分子必须与删除语句是<b>同一个谓词</b>：分子少一个条件（比如漏了"排除平台根组织"）
 * 就会反复跳过删除；分子多一个条件就会误删。两份 SQL 分散写在两个注解里，
 * 靠人眼的"注释里写了同一谓词"守不住，这里把两段 WHERE 抠出来逐字比。</p>
 *
 * <p>顺带守住 XML 实体泄漏：{@code &lt;&gt;} 只在不包 {@code <script>} 的注解里是字面量，
 * 真跑起来就是语法错误，方言单测（只解析 updateDiff）拦不住。</p>
 */
class SyncGuardPredicateParityTest {

    private static final long TASK_ID = 1L;
    private static final long PLATFORM_ID = 9L;
    private static final String PLATFORM_CODE = "34020000002000000001";

    @Test
    @DisplayName("四类集合：countWouldStale 与 markMissing 的 WHERE 逐字一致")
    void numeratorSharesDeletePredicate() {
        assertParity("SyncOrgAlignmentMapper", "ORG");
        assertParity("SyncGroupAlignmentMapper", "GROUP");
        assertParity("SyncDeviceAlignmentMapper", "DEVICE");
        assertParity("SyncChannelAlignmentMapper", "CHANNEL");
    }

    @Test
    @DisplayName("消失标记只动 ACTIVE 行，绝不动 REMOVED / 已 STALE 的行")
    void markMissingOnlyTouchesActiveRows() {
        for (String mapper : List.of("SyncOrgAlignmentMapper", "SyncGroupAlignmentMapper",
                "SyncDeviceAlignmentMapper", "SyncChannelAlignmentMapper")) {
            String mark = predicate(sql(mapper + ".markMissing"));
            assertTrue(mark.contains("d.status = 'ACTIVE'"), mapper + " 必须只标 ACTIVE 行：" + mark);
            assertFalse(mark.contains("REMOVED"), mapper + " 消失标记不该再筛 REMOVED：" + mark);
        }
    }

    @Test
    @DisplayName("设备与组织的消失标记排除平台根码，分组与通道没有这一层")
    void platformCodeExclusionOnlyWhereNeeded() {
        assertTrue(predicate(sql("SyncOrgAlignmentMapper.markMissing")).contains("d.code <> ?"));
        assertTrue(predicate(sql("SyncDeviceAlignmentMapper.markMissing")).contains("d.code <> ?"));
        assertFalse(predicate(sql("SyncGroupAlignmentMapper.markMissing")).contains("platformCode"));
        assertFalse(predicate(sql("SyncChannelAlignmentMapper.markMissing")).contains("platformCode"));
    }

    @Test
    @DisplayName("注解里不出现未解码的 XML 实体（&lt; / &gt;），否则真库直接语法错误")
    void annotationsDoNotLeakXmlEntities() {
        for (String id : List.of(
                "SyncOrgAlignmentMapper.markMissing", "SyncOrgAlignmentMapper.countWouldStale",
                "SyncGroupAlignmentMapper.markMissing", "SyncGroupAlignmentMapper.countWouldStale",
                "SyncDeviceAlignmentMapper.markMissing", "SyncDeviceAlignmentMapper.countWouldStale",
                "SyncChannelAlignmentMapper.markMissing", "SyncChannelAlignmentMapper.countWouldStale")) {
            String raw = sql(id);
            assertFalse(raw.contains("&lt;"), id + " 泄漏了 XML 实体：" + raw);
            assertFalse(raw.contains("&gt;"), id + " 泄漏了 XML 实体：" + raw);
            assertFalse(raw.contains("&amp;"), id + " 泄漏了 XML 实体：" + raw);
        }
    }

    private static void assertParity(String mapper, String itemType) {
        String mark = predicate(sql(mapper + ".markMissing"));
        String count = predicate(sql(mapper + ".countWouldStale"));

        assertTrue(mark.contains("s.item_type = '" + itemType + "'"), mapper + " 消失标记的 item_type 不匹配");
        assertTrue(count.contains("s.item_type = '" + itemType + "'"), mapper + " 分子统计的 item_type 不匹配");
        assertEquals(mark, count, mapper + " 的 G2 分子与删除谓词已漂移");
    }

    /** 抠出外层 WHERE 之后的全部条件并归一化空白，用于逐字比较 */
    private static String predicate(String sql) {
        int index = sql.indexOf("WHERE");
        if (index < 0) {
            throw new AssertionError("未在 SQL 中找到 WHERE：\n" + sql);
        }
        return sql.substring(index).replaceAll("\\s+", " ").trim();
    }

    private static String sql(String statementId) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setDatabaseId("mysql");
        configuration.addMapper(SyncOrgAlignmentMapper.class);
        configuration.addMapper(SyncGroupAlignmentMapper.class);
        configuration.addMapper(SyncDeviceAlignmentMapper.class);
        configuration.addMapper(SyncChannelAlignmentMapper.class);

        MappedStatement statement = configuration.getMappedStatement(
                "com.solo.infra.mapper." + statementId);
        Map<String, Object> params = new HashMap<>();
        params.put("taskId", TASK_ID);
        params.put("platformId", PLATFORM_ID);
        params.put("platformCode", PLATFORM_CODE);
        params.put("now", LocalDateTime.of(2026, 11, 23, 0, 0));
        return statement.getBoundSql(params).getSql();
    }
}
