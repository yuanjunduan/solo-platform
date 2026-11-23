package com.solo.infra.interceptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 持久层只读判定（3.6.5 第三层）。
 *
 * <p>这一层是"后来人兜底"的门禁，判定错了等于装了只会刷日志的假门——所以
 * 四种 MyBatis 写法（updateById / Wrapper / 注解 SQL / XML）造成的两类违规都要覆盖。</p>
 */
class ResourceWriteInspectorTest {

    @Test
    @DisplayName("UPDATE res_* 未带 source_type 条件 → 违规")
    void flagsUpdateWithoutSourceCondition() {
        String violation = ResourceWriteInspector.inspect("com.solo.infra.mapper.ResOrgMapper.updateById",
                "UPDATE res_org SET name=?, update_time=? WHERE id=?");
        assertNotNull(violation);
    }

    @Test
    @DisplayName("UPDATE res_* 带 source_type='NATIVE' → 合规")
    void allowsUpdateWithSourceCondition() {
        assertNull(ResourceWriteInspector.inspect("com.solo.infra.mapper.ResOrgMapper.updateById",
                "UPDATE res_org SET name=? WHERE id=? AND source_type = 'NATIVE'"));
    }

    @Test
    @DisplayName("同步侧按 INBOUND 收敛的 UPDATE → 合规（第三个 'NATIVE' 之外，同步必须能写 INBOUND）")
    void allowsSyncUpdateWithInboundCondition() {
        assertNull(ResourceWriteInspector.inspect("com.solo.infra.mapper.SyncChannelAlignmentMapper.updateDiff",
                """
                UPDATE res_channel d
                   SET d.name = s.name, d.update_time = ?
                  FROM sync_staging s
                 WHERE d.source_type = 'INBOUND'
                   AND d.source_platform_id = s.platform_id
                   AND d.code = s.code
                """));
    }

    @Test
    @DisplayName("同一段 SQL 去掉 source_type 条件后就要被拦（说明这条判定不是摆设）")
    void flagsSameSqlWithoutSourceCondition() {
        assertNotNull(ResourceWriteInspector.inspect("com.solo.infra.mapper.SyncChannelAlignmentMapper.updateDiff",
                """
                UPDATE res_channel d
                   SET d.name = s.name, d.update_time = ?
                  FROM sync_staging s
                 WHERE d.source_platform_id = s.platform_id
                   AND d.code = s.code
                """));
    }

    @Test
    @DisplayName("DELETE FROM res_* → 违规（要求一律软删）")
    void flagsHardDelete() {
        assertNotNull(ResourceWriteInspector.inspect("com.solo.infra.mapper.ResDeviceMapper.deleteById",
                "DELETE FROM res_device WHERE id=?"));
    }

    @Test
    @DisplayName("带库名前缀 / 反引号 / 多空白也认得出目标表")
    void toleratesQuotingAndWhitespace() {
        assertNotNull(ResourceWriteInspector.inspect("x",
                "delete   FROM   `solo_db`.`res_channel`\nWHERE id = ?"));
        // 非资源表不受管
        assertNull(ResourceWriteInspector.inspect("x", "DELETE FROM gb_device WHERE id=?"));
    }

    @Test
    @DisplayName("INSERT / SELECT 不管：新增行自带来源列")
    void ignoresNonMutatingStatements() {
        assertNull(ResourceWriteInspector.inspect("x",
                "INSERT INTO res_org (id, code, source_type) VALUES (?, ?, ?)"));
        assertNull(ResourceWriteInspector.inspect("x",
                "SELECT * FROM res_org WHERE id = ?"));
        assertNull(ResourceWriteInspector.inspect("x", null));
        assertNull(ResourceWriteInspector.inspect("x", "   "));
    }

    @Test
    @DisplayName("改非资源表（如 sync_staging / platform）不告警")
    void ignoresOtherTables() {
        assertNull(ResourceWriteInspector.inspect("x",
                "UPDATE sync_staging SET name = ? WHERE task_id = ?"));
    }
}
