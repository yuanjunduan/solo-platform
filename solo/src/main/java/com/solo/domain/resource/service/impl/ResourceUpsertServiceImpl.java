package com.solo.domain.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.solo.common.ServiceException;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.resource.model.ResOrg;
import com.solo.domain.resource.service.ResourceUpsertService;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import com.solo.infra.mapper.ResOrgMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务键原地写入实现。
 *
 * <p>三个方法结构一致，故意不抽泛型工具：每张表的业务键列名、需要保住的结构字段
 * （org 的 path、channel 的 canonical_id）都不同，抽象成 {@code <T>} 后每次调用
 * 都要传四五个函数式参数，反而更难核对。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceUpsertServiceImpl implements ResourceUpsertService {

    private static final String LOG_TAG = "资源写入";

    private final ResOrgMapper orgMapper;
    private final ResDeviceMapper deviceMapper;
    private final ResChannelMapper channelMapper;

    @Override
    @Transactional
    public Long upsertOrg(ResOrg incoming) {
        requireBizKey(incoming.getSourceType(), incoming.getSourcePlatformId(), incoming.getCode());

        ResOrg existing = orgMapper.selectByBizKey(
                incoming.getSourceType(), incoming.getSourcePlatformId(), incoming.getCode());
        if (existing == null) {
            if (incoming.getId() == null) {
                incoming.setId(IdWorker.getId());
            }
            orgMapper.insert(incoming);
            log.debug("[{}] 新建组织 id={} code={}", LOG_TAG, incoming.getId(), incoming.getCode());
            return incoming.getId();
        }

        // 沿用旧 id 与首建时间：id 是外部引用的锚点，createTime 是"何时第一次出现"的事实
        incoming.setId(existing.getId());
        incoming.setCreateTime(null);
        orgMapper.updateById(incoming);
        log.debug("[{}] 原地更新组织 id={} code={}", LOG_TAG, existing.getId(), incoming.getCode());
        return existing.getId();
    }

    @Override
    @Transactional
    public Long upsertDevice(ResDevice incoming) {
        requireBizKey(incoming.getSourceType(), incoming.getSourcePlatformId(), incoming.getCode());

        ResDevice existing = deviceMapper.selectByBizKey(
                incoming.getSourceType(), incoming.getSourcePlatformId(), incoming.getCode());
        if (existing == null) {
            if (incoming.getId() == null) {
                incoming.setId(IdWorker.getId());
            }
            deviceMapper.insert(incoming);
            log.debug("[{}] 新建设备 id={} code={}", LOG_TAG, incoming.getId(), incoming.getCode());
            return incoming.getId();
        }

        incoming.setId(existing.getId());
        incoming.setCreateTime(null);
        deviceMapper.updateById(incoming);
        log.debug("[{}] 原地更新设备 id={} code={}", LOG_TAG, existing.getId(), incoming.getCode());
        return existing.getId();
    }

    @Override
    @Transactional
    public Long upsertChannel(ResChannel incoming) {
        requireBizKey(incoming.getSourceType(), incoming.getSourcePlatformId(), incoming.getCode());

        ResChannel existing = channelMapper.selectByBizKey(
                incoming.getSourceType(), incoming.getSourcePlatformId(), incoming.getCode());
        if (existing == null) {
            if (incoming.getId() == null) {
                incoming.setId(IdWorker.getId());
            }
            if (incoming.getCanonicalId() == null) {
                incoming.setCanonicalId(0L);
            }
            channelMapper.insert(incoming);
            log.debug("[{}] 新建通道 id={} code={}", LOG_TAG, incoming.getId(), incoming.getCode());
            return incoming.getId();
        }

        incoming.setId(existing.getId());
        incoming.setCreateTime(null);
        // 实体组主记录是跨来源逻辑关联的锚点，同步链路不传时不能被 updateById 抹掉（它是 null 就跳过，这里只是明确意图）
        if (incoming.getCanonicalId() == null) {
            incoming.setCanonicalId(existing.getCanonicalId());
        }
        channelMapper.updateById(incoming);
        log.debug("[{}] 原地更新通道 id={} code={}", LOG_TAG, existing.getId(), incoming.getCode());
        return existing.getId();
    }

    /**
     * 业务键三段必须齐：缺任何一段都会退化成"按类型全表扫描"，且唯一键根本拦不住重复。
     * <p>这是编程错误而非用户输入错误，所以用 500 而不是 400。</p>
     */
    private static void requireBizKey(String sourceType, Long sourcePlatformId, String code) {
        if (sourceType == null || sourceType.isBlank()) {
            throw new ServiceException(500, "写入资源缺少 sourceType");
        }
        if (sourcePlatformId == null) {
            throw new ServiceException(500, "写入资源缺少 sourcePlatformId（本地资源固定为 0）");
        }
        if (code == null || code.isBlank()) {
            throw new ServiceException(500, "写入资源缺少 code");
        }
    }
}
