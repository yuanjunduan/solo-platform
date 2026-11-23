package com.solo.gb28181.inbound;

import com.solo.domain.platform.model.Platform;
import com.solo.domain.sync.model.CatalogItem;
import com.solo.domain.sync.model.CatalogItemType;
import com.solo.domain.sync.spi.CatalogBatch;
import com.solo.domain.sync.spi.CatalogSink;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.infra.mapper.PlatformMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 目录项分类与投递口语义的单测。
 *
 * <p>覆盖两件最容易错的事：</p>
 * <ol>
 *   <li><b>分类</b>：215/216 是分组（落 {@code res_group}）、200 是设备、≤8 位行政码是组织、
 *       Parental=0 才是通道。分错一次，整批数据会落到错误的表里，而且已存在业务键就再也纠正不回来。</li>
 *   <li><b>批次边界</b>：{@code onComplete} 必须只在收齐时触发一次；暂存写失败必须走
 *       {@code onError} 而不是被吞掉（C14）。</li>
 * </ol>
 *
 * <p>另有两条全局身份（3.9.3）的落链断言：上游给了身份要透传并延长链，没给只能诚实退化到
 * "直接来源"。</p>
 */
class Gb28181CatalogTapTest {

    private static final String DOMAIN = "3402000000";

    /** 最上游平台反查入口：默认查不到，走"上游没给身份"的降级路径 */
    private final PlatformMapper platformMapper = mock(PlatformMapper.class);

    private Gb28181CatalogTap newTap() {
        return new Gb28181CatalogTap(platformMapper);
    }

    private static String gbCode(String typeCode, String serial) {
        return DOMAIN + typeCode + serial;
    }

    @Test
    void classifiesDirectoryItemsByTypeCode() {
        assertType(gbCode("215", "0000001"), 1, CatalogItemType.GROUP_BUSINESS);
        assertType(gbCode("216", "0000002"), 1, CatalogItemType.GROUP_VIRTUAL);
        assertType(gbCode("200", "0000003"), 0, CatalogItemType.DEVICE);
        assertType(gbCode("132", "0000004"), 0, CatalogItemType.CHANNEL);
        assertType(gbCode("201", "0000005"), 1, CatalogItemType.ORG);
        // 6-8 位行政码：无论 Parental 怎么填都算组织
        assertType("340200", 0, CatalogItemType.ORG);
    }

    @Test
    void channelKeepsDeviceHintsAndDirectoryDoesNot() {
        GbDeviceChannel channel = item(gbCode("132", "0000006"), 0);
        channel.setIpAddress("10.0.0.9");
        channel.setManufacturer("HIKVISION");
        channel.setModel("DS-2CD");
        channel.setPtzType(1);

        CatalogItem converted = Gb28181CatalogTap.convert("34020000001320000001", channel);
        assertThat(converted.getType()).isEqualTo(CatalogItemType.CHANNEL);
        assertThat(converted.getDeviceIp()).isEqualTo("10.0.0.9");
        assertThat(converted.getVendor()).isEqualTo("HIKVISION");
        assertThat(converted.getCapability()).isEqualTo("PTZ");

        CatalogItem org = Gb28181CatalogTap.convert("34020000001320000001",
                item(gbCode("215", "0000007"), 1));
        assertThat(org.getType()).isEqualTo(CatalogItemType.GROUP_BUSINESS);
        assertThat(org.groupType()).isEqualTo("215");
        assertThat(org.getDeviceIp()).isNull();
    }

    @Test
    void deliversBatchThenCompletesOnce() {
        RecordingSink sink = new RecordingSink();
        Gb28181CatalogTap tap = newTap();
        assertThat(tap.register("34020000001320000001", 9L, sink)).isTrue();
        // 同设备第二个轮次必须被拒（C24 同平台互斥）
        assertThat(tap.register("34020000001320000001", 10L, new RecordingSink())).isFalse();

        tap.onBatch("34020000001320000001", 2, 2,
                List.of(item(gbCode("132", "0000008"), 0), item(gbCode("132", "0000009"), 0)));

        assertThat(sink.batches).hasSize(1);
        assertThat(sink.batches.get(0).expectedTotal()).isEqualTo(2);
        assertThat(sink.batches.get(0).items()).hasSize(2);
        assertThat(sink.complete).isTrue();
        assertThat(sink.errorCode).isNull();
        assertThat(tap.isActive("34020000001320000001")).isFalse();

        // 收齐后继续来的批次必须被忽略（会话已摘）
        tap.onBatch("34020000001320000001", 2, 2, List.of(item(gbCode("132", "0000010"), 0)));
        assertThat(sink.batches).hasSize(1);
    }

    @Test
    void stagingFailureIsReportedNotSwallowed() {
        RecordingSink sink = new RecordingSink();
        sink.failOnBatch = true;
        Gb28181CatalogTap tap = newTap();
        tap.register("34020000001320000001", 9L, sink);

        tap.onBatch("34020000001320000001", 5, 2,
                List.of(item(gbCode("132", "0000011"), 0)));

        assertThat(sink.errorCode).isEqualTo("STAGING_WRITE_FAILED");
        assertThat(sink.complete).isFalse();
        assertThat(tap.isActive("34020000001320000001")).isFalse();
    }

    @Test
    void emptyCatalogCompletes() {
        RecordingSink sink = new RecordingSink();
        Gb28181CatalogTap tap = newTap();
        tap.register("34020000001320000001", 9L, sink);

        tap.onEmptyCatalog("34020000001320000001");

        assertThat(sink.complete).isTrue();
        assertThat(sink.batches).isEmpty();
    }

    /** 上游没给全局身份：只能填"直接来源 + 自身码"，链长 1，跳数 1（3.10.3 的降级口径） */
    @Test
    void originFallsBackToDirectSourceWhenUpstreamGivesNothing() {
        GbDeviceChannel channel = item(gbCode("132", "0000020"), 0);
        RecordingSink sink = new RecordingSink();
        Gb28181CatalogTap tap = newTap();
        tap.register("34020000001320000001", 9L, sink);

        tap.onBatch("34020000001320000001", 1, 1, List.of(channel));

        CatalogItem delivered = sink.batches.get(0).items().get(0);
        assertThat(delivered.getOriginPlatformId()).isEqualTo(9L);
        assertThat(delivered.getOriginCode()).isEqualTo(channel.getChannelId());
        assertThat(delivered.getOriginChain()).isEqualTo("34020000001320000001");
        assertThat(delivered.getHopCount()).isEqualTo(1);
    }

    /** 上游给了身份：透传最上游码与原始码，链尾补上直接来源，跳数 +1（不覆盖、只延长） */
    @Test
    void originIsPassedThroughAndChainExtended() {
        Platform originPlatform = new Platform();
        originPlatform.setId(77L);
        originPlatform.setCode("34020000001110000001");
        when(platformMapper.selectList(any())).thenReturn(List.of(originPlatform));

        GbDeviceChannel channel = item(gbCode("132", "0000021"), 0);
        channel.setOriginPlatformCode("34020000001110000001");
        channel.setOriginCode("34020000001110000999");
        channel.setOriginChain("34020000001110000001");
        channel.setHopCount(1);

        RecordingSink sink = new RecordingSink();
        Gb28181CatalogTap tap = newTap();
        tap.register("34020000001320000001", 9L, sink);

        tap.onBatch("34020000001320000001", 1, 1, List.of(channel));

        CatalogItem delivered = sink.batches.get(0).items().get(0);
        assertThat(delivered.getOriginPlatformId()).isEqualTo(77L);
        assertThat(delivered.getOriginCode()).isEqualTo("34020000001110000999");
        assertThat(delivered.getOriginChain())
                .isEqualTo("34020000001110000001,34020000001320000001");
        assertThat(delivered.getHopCount()).isEqualTo(2);
    }

    private static void assertType(String code, int parental, CatalogItemType expected) {
        GbDeviceChannel channel = item(code, parental);
        CatalogItem converted = Gb28181CatalogTap.convert("34020000001320000001", channel);
        assertThat(converted).as("code=%s", code).isNotNull();
        assertThat(converted.getType()).as("code=%s", code).isEqualTo(expected);
        assertThat(converted.itemType()).isEqualTo(expected.itemType());
    }

    private static GbDeviceChannel item(String code, int parental) {
        GbDeviceChannel channel = new GbDeviceChannel();
        channel.setChannelId(code);
        channel.setName("n-" + code);
        channel.setParentId(DOMAIN + "215" + "0000000");
        channel.setParental(parental);
        channel.setStatus("ON");
        return channel;
    }

    private static final class RecordingSink implements CatalogSink {
        private final List<CatalogBatch> batches = new ArrayList<>();
        private boolean complete;
        private String errorCode;
        private boolean failOnBatch;

        @Override
        public void onBatch(CatalogBatch batch) {
            if (failOnBatch) {
                throw new IllegalStateException("staging down");
            }
            batches.add(batch);
        }

        @Override
        public void onComplete() {
            complete = true;
        }

        @Override
        public void onError(String code, String message) {
            errorCode = code;
        }
    }
}
