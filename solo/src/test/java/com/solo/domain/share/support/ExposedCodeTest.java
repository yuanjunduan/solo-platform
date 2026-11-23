package com.solo.domain.share.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 暴露码契约（C16）。
 *
 * <p>这里锁的是三件最容易改坏的事：长度必须恒为 20 位、序号段不复用（墓碑靠它）、
 * 解析失败一律返回 -1 而不是抛异常——调用方在投递路径上拿到 -1 会转人工，
 * 抛异常会把一条坏数据放大成整轮推送失败。</p>
 */
class ExposedCodeTest {

    private static final String DOMAIN = "3402000000";

    @Test
    void buildsTwentyDigitCodeAndParsesBack() {
        String code = ExposedCode.of(DOMAIN, ExposedCode.SEG_CHANNEL, 42L);

        assertThat(code).isEqualTo("34020000001310000042");
        assertThat(code).hasSize(20);
        assertThat(ExposedCode.domainOf(code)).isEqualTo(DOMAIN);
        assertThat(ExposedCode.segOfCode(code)).isEqualTo("131");
        assertThat(ExposedCode.seqOf(code)).isEqualTo(42L);
        assertThat(ExposedCode.belongsTo(code, DOMAIN)).isTrue();
    }

    @Test
    void seqIsPaddedToSevenDigits() {
        assertThat(ExposedCode.of(DOMAIN, ExposedCode.SEG_DEVICE, 1L)).endsWith("0000001");
        assertThat(ExposedCode.of(DOMAIN, ExposedCode.SEG_VIRTUAL_ORG, ExposedCode.SEQ_MAX))
                .endsWith("9999999");
    }

    @Test
    void rejectsOutOfRangeSequenceAndBadDomain() {
        assertThatThrownBy(() -> ExposedCode.of(DOMAIN, ExposedCode.SEG_CHANNEL, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExposedCode.of(DOMAIN, ExposedCode.SEG_CHANNEL, ExposedCode.SEQ_MAX + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExposedCode.of("340200000", ExposedCode.SEG_CHANNEL, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExposedCode.of("34020000AB", ExposedCode.SEG_CHANNEL, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTypeSegmentThatIsNotThreeDigits() {
        assertThatThrownBy(() -> ExposedCode.of(DOMAIN, "13", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExposedCode.of(DOMAIN, "1A1", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ExposedCode.isValidSeg("131")).isTrue();
        assertThat(ExposedCode.isValidSeg(null)).isFalse();
    }

    @Test
    void mapsRefTypeToSegmentAndRejectsOthers() {
        assertThat(ExposedCode.segOf("CHANNEL")).isEqualTo(ExposedCode.SEG_CHANNEL);
        assertThat(ExposedCode.segOf("DEVICE")).isEqualTo(ExposedCode.SEG_DEVICE);
        assertThat(ExposedCode.segOf("GROUP")).isEqualTo(ExposedCode.SEG_VIRTUAL_ORG);
        // ORG 只作为规则行出现，不是暴露节点
        assertThatThrownBy(() -> ExposedCode.segOf("ORG"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExposedCode.segOf(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsingIsLenientAndReturnsMinusOneInsteadOfThrowing() {
        assertThat(ExposedCode.seqOf(null)).isEqualTo(-1L);
        assertThat(ExposedCode.seqOf("3402000000131000004")).isEqualTo(-1L);
        assertThat(ExposedCode.seqOf("3402000000131000ABCD")).isEqualTo(-1L);
        assertThat(ExposedCode.seqOf("34020000001310000000")).isEqualTo(-1L);
        assertThat(ExposedCode.domainOf("3402")).isNull();
        assertThat(ExposedCode.segOfCode("3402000000131")).isEqualTo("131");
        assertThat(ExposedCode.segOfCode("340200000013")).isNull();
    }

    @Test
    void belongsToOnlyMatchesSameDomainPrefix() {
        String code = ExposedCode.of(DOMAIN, ExposedCode.SEG_CHANNEL, 7L);
        assertThat(ExposedCode.belongsTo(code, DOMAIN)).isTrue();
        assertThat(ExposedCode.belongsTo(code, "3402000001")).isFalse();
        assertThat(ExposedCode.belongsTo(null, DOMAIN)).isFalse();
        assertThat(ExposedCode.belongsTo(code, null)).isFalse();
    }
}
