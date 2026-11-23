package com.solo.domain.resource.service;

import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.resource.model.ResOrg;
import com.solo.domain.resource.service.impl.ResourceUpsertServiceImpl;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import com.solo.infra.mapper.ResOrgMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务键原地写入：命中即 UPDATE 同一行，绝不删了重建（C3 / 3.6.6）。
 *
 * <p>这组用例守的是"id 稳定"这一个不变式——它是共享关系与暴露码的全部前提。</p>
 */
@ExtendWith(MockitoExtension.class)
class ResourceUpsertServiceImplTest {

    @Mock
    private ResOrgMapper orgMapper;
    @Mock
    private ResDeviceMapper deviceMapper;
    @Mock
    private ResChannelMapper channelMapper;
    @InjectMocks
    private ResourceUpsertServiceImpl service;

    @Test
    void firstWriteInsertsAndAssignsId() {
        ResOrg incoming = org("INBOUND", 9L, "org-0001");
        when(orgMapper.selectByBizKey("INBOUND", 9L, "org-0001")).thenReturn(null);

        Long id = service.upsertOrg(incoming);

        assertThat(id).isNotNull();
        assertThat(incoming.getId()).isEqualTo(id);
        verify(orgMapper).insert(incoming);
        verify(orgMapper, never()).updateById(any(ResOrg.class));
    }

    @Test
    void secondWriteUpdatesInPlaceAndKeepsSameId() {
        long stableId = 42L;
        LocalDateTime firstSeen = LocalDateTime.of(2026, 1, 1, 0, 0);
        ResOrg existing = org("INBOUND", 9L, "org-0001");
        existing.setId(stableId);
        existing.setCreateTime(firstSeen);
        when(orgMapper.selectByBizKey("INBOUND", 9L, "org-0001")).thenReturn(existing);

        ResOrg incoming = org("INBOUND", 9L, "org-0001");
        incoming.setId(999L);
        incoming.setCreateTime(LocalDateTime.now());

        Long id = service.upsertOrg(incoming);

        assertThat(id).isEqualTo(stableId);
        // 调用方传进来的 id 必须被覆盖成命中的那一行，否则 updateById 会更新到不存在的行
        assertThat(incoming.getId()).isEqualTo(stableId);
        // createTime 必须清空：updateById 跳过 null，等于"保留首建时间"
        assertThat(incoming.getCreateTime()).isNull();
        verify(orgMapper).updateById(incoming);
        verify(orgMapper, never()).insert(any(ResOrg.class));
    }

    @Test
    void deviceUpsertKeepsIdStableAcrossRepeatedSyncs() {
        long stableId = 77L;
        ResDevice existing = new ResDevice();
        existing.setId(stableId);
        when(deviceMapper.selectByBizKey("INBOUND", 9L, "dev-1")).thenReturn(null, existing);

        ResDevice first = new ResDevice();
        first.setSourceType("INBOUND");
        first.setSourcePlatformId(9L);
        first.setCode("dev-1");
        Long idOfFirst = service.upsertDevice(first);

        ResDevice second = new ResDevice();
        second.setSourceType("INBOUND");
        second.setSourcePlatformId(9L);
        second.setCode("dev-1");
        Long idOfSecond = service.upsertDevice(second);

        assertThat(idOfFirst).isNotNull();
        assertThat(idOfSecond).isEqualTo(stableId);
        verify(deviceMapper).insert(first);
        verify(deviceMapper).updateById(second);
    }

    @Test
    void channelUpsertDoesNotDropCanonicalIdWhenCallerOmitsIt() {
        ResChannel existing = new ResChannel();
        existing.setId(5L);
        existing.setCanonicalId(100L);
        when(channelMapper.selectByBizKey("INBOUND", 9L, "ch-1")).thenReturn(existing);

        ResChannel incoming = new ResChannel();
        incoming.setSourceType("INBOUND");
        incoming.setSourcePlatformId(9L);
        incoming.setCode("ch-1");

        service.upsertChannel(incoming);

        // 实体组主记录是跨来源关联的锚点，同步链路不传时不能被抹掉
        assertThat(incoming.getCanonicalId()).isEqualTo(100L);
        verify(channelMapper).updateById(incoming);
    }

    @Test
    void missingBizKeyIsRejectedBeforeTouchingDatabase() {
        ResOrg noCode = org("INBOUND", 9L, "  ");

        org.junit.jupiter.api.Assertions.assertThrows(
                com.solo.common.ServiceException.class, () -> service.upsertOrg(noCode));
        verify(orgMapper, never()).insert(any(ResOrg.class));
        verify(orgMapper, never()).updateById(any(ResOrg.class));
    }

    private static ResOrg org(String sourceType, Long sourcePlatformId, String code) {
        ResOrg org = new ResOrg();
        org.setSourceType(sourceType);
        org.setSourcePlatformId(sourcePlatformId);
        org.setCode(code);
        org.setName("测试组织");
        return org;
    }
}
