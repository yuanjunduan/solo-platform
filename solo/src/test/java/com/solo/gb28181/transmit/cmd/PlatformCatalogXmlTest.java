package com.solo.gb28181.transmit.cmd;

import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.entity.GbParentPlatform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站目录报文的全局身份（3.9.3）断言。
 *
 * <p>这里只钉住三件会跨系统出错的事：</p>
 * <ol>
 *   <li><b>恒带</b>：本地资源的 {@code OriginPlatformId} 必须是本平台国标码、{@code OriginCode}
 *       必须是自身编码——缺了它，上级只能把资源当"无来源"，跨级去重与防环全部失效。</li>
 *   <li><b>只增不改</b>：入站/转发资源的四项必须原样透传，尤其 {@code OriginChain}/{@code HopCount}，
 *       覆盖一次就再也拼不回来。</li>
 *   <li><b>不越权</b>：{@code Origin*} 放在 {@code <Info>} 内且不能改 {@code <DeviceID>}——
 *       对外暴露码是上级视角的编码，与来源身份是两回事。</li>
 * </ol>
 */
class PlatformCatalogXmlTest {

    private static final String LOCAL_ID = "34020000002000000001";

    @Test
    void localChannelCarriesLocalIdentity() {
        String xml = PlatformCatalogXml.buildResponse(platform(), LOCAL_ID, "1", 1,
                List.of(channel("34020000001320000001")));

        assertThat(xml).contains("<OriginPlatformId>" + LOCAL_ID + "</OriginPlatformId>");
        assertThat(xml).contains("<OriginCode>34020000001320000001</OriginCode>");
        // 本地资源没有链与跳数：空标签会被上级当成非法节点，必须整段省略
        assertThat(xml).doesNotContain("<OriginChain>");
        assertThat(xml).doesNotContain("<HopCount>");
    }

    @Test
    void inboundChannelPassesOriginThrough() {
        GbDeviceChannel item = channel("34020000001320000999");
        item.setExposedCode("34020000001320000888");
        item.setOriginPlatformCode("34020000001110000001");
        item.setOriginCode("34020000001110000999");
        item.setOriginChain("34020000001110000001,34020000001320000001");
        item.setHopCount(2);

        String xml = PlatformCatalogXml.buildResponse(platform(), LOCAL_ID, "1", 1, List.of(item));

        assertThat(xml).contains("<OriginPlatformId>34020000001110000001</OriginPlatformId>");
        assertThat(xml).contains("<OriginCode>34020000001110000999</OriginCode>");
        assertThat(xml).contains("<OriginChain>34020000001110000001,34020000001320000001</OriginChain>");
        assertThat(xml).contains("<HopCount>2</HopCount>");
        // 对外暴露码仍是 DeviceID：来源身份不能覆盖上级视角的编码
        assertThat(xml).contains("<DeviceID>34020000001320000888</DeviceID>");
    }

    @Test
    void groupItemHasNoInfoSection() {
        GbDeviceChannel group = new GbDeviceChannel();
        group.setChannelId("34020000002150000001");
        group.setName("业务分组");
        group.setParental(1);

        String xml = PlatformCatalogXml.buildResponse(platform(), LOCAL_ID, "1", 1, List.of(group));

        assertThat(xml).doesNotContain("<Info>");
        assertThat(xml).doesNotContain("<OriginPlatformId>");
    }

    private static GbDeviceChannel channel(String code) {
        GbDeviceChannel item = new GbDeviceChannel();
        item.setChannelId(code);
        item.setName("通道-" + code);
        item.setParental(0);
        item.setStatus("ON");
        item.setPtzType(1);
        return item;
    }

    private static GbParentPlatform platform() {
        return new GbParentPlatform();
    }
}
