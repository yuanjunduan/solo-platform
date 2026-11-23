package com.solo.domain.share.service.impl;

import com.solo.common.ServiceException;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.SourceType;
import com.solo.domain.share.model.OutCodeMap;
import com.solo.domain.share.model.ShareRefType;
import com.solo.domain.share.service.OutCodeService;
import com.solo.domain.share.support.ExposedCode;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.mapper.GbParentPlatformMapper;
import com.solo.infra.mapper.OutCodeMapMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 暴露码分配实现（3.9.3 / C16 / S4）。
 *
 * <p><b>为什么每次都先锁平台行</b>：序号来自 {@code MAX(seq_no)+1}，两个并发分配不锁就会拿到同一个号，
 * 而 {@code uk_out_code_exposed} 一旦撞上，外层事务只能整体回滚重来。锁的是
 * {@code gb_parent_platform} 那一行（C24：互斥放 DB，不放进程内变量）。</p>
 *
 * <p><b>为什么本地资源也写一行映射</b>：透传时代码相同，看似多余，但反向解析
 * （上级 INVITE → 原始码）和"资源恢复复用原码"都依赖这一行；不写就得在两处各做一次例外判断，
 * 而例外最容易在 M5 转发时漏掉。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutCodeServiceImpl implements OutCodeService {

    /** 单租户平台默认租户（与事件流同口径） */
    private static final long DEFAULT_TENANT_ID = 1L;

    /** 撞码重试上限：序号已用尽或连续撞 32 次说明数据异常，交人工而不是死循环 */
    private static final int MAX_COLLISION_RETRY = 32;

    /** 透传行的序号占位：0 表示"未消耗号段" */
    private static final long PASS_THROUGH_SEQ = 0L;

    private final OutCodeMapMapper codeMapMapper;
    private final GbParentPlatformMapper parentPlatformMapper;
    private final Gb28181Properties gbProperties;

    @Override
    public String localDomain() {
        String domain = gbProperties.getDomain();
        if (!StringUtils.hasText(domain) || domain.trim().length() != 10) {
            throw new ServiceException("未配置本平台 SIP 域（gb28181.domain 需为 10 位），无法分配上级暴露码");
        }
        return domain.trim();
    }

    @Override
    @Transactional
    public String ensureGroupCode(Long platformId, String groupType, String nativeKey) {
        if (platformId == null) {
            throw new ServiceException("目标上级平台不能为空");
        }
        String key = nativeKey == null ? "" : nativeKey.trim();
        if (!StringUtils.hasText(key)) {
            throw new ServiceException("虚拟组织节点缺少稳定标识，无法分配暴露码");
        }
        lockPlatform(platformId);
        // 幂等：同一节点反复"一键生成"必须拿到同一个码，否则每次都会新建映射并撞唯一键
        OutCodeMap row = codeMapMapper.selectByNative(platformId, ShareRefType.GROUP, key);
        if (row != null) {
            if (row.isTombstone()) {
                codeMapMapper.updateState(row.getId(), OutCodeMap.STATE_ACTIVE, LocalDateTime.now());
            }
            return row.getExposedCode();
        }
        String seg = ExposedCode.isValidSeg(groupType) ? groupType : ExposedCode.SEG_VIRTUAL_ORG;
        return allocate(platformId, ShareRefType.GROUP, key, seg).getExposedCode();
    }

    @Override
    @Transactional
    public String ensureChannelCode(Long platformId, ResChannel channel) {
        if (channel == null || !StringUtils.hasText(channel.getCode())) {
            throw new ServiceException("通道编码为空，无法分配上级暴露码");
        }
        lockPlatform(platformId);
        return ensureLocked(platformId, channel, preload(platformId, List.of(channel)));
    }

    @Override
    @Transactional
    public Map<Long, String> ensureChannelCodes(Long platformId, List<ResChannel> channels) {
        Map<Long, String> out = new HashMap<>();
        if (channels == null || channels.isEmpty()) {
            return out;
        }
        lockPlatform(platformId);
        Map<String, OutCodeMap> existing = preload(platformId, channels);
        for (ResChannel channel : channels) {
            if (channel == null || !StringUtils.hasText(channel.getCode())) {
                continue;
            }
            out.put(channel.getId(), ensureLocked(platformId, channel, existing));
        }
        return out;
    }

    @Override
    @Transactional
    public void tombstone(Long platformId, String refType, String nativeCode) {
        OutCodeMap row = codeMapMapper.selectByNative(platformId, refType, nativeCode);
        if (row == null || row.isTombstone()) {
            return;
        }
        codeMapMapper.updateState(row.getId(), OutCodeMap.STATE_TOMBSTONE, LocalDateTime.now());
        log.info("[上级共享] 暴露码置墓碑 platform={} type={} native={} exposed={}",
                platformId, refType, nativeCode, row.getExposedCode());
    }

    @Override
    public String exposedCodeOf(Long platformId, String refType, String nativeCode) {
        if (platformId == null || !StringUtils.hasText(nativeCode)) {
            return null;
        }
        OutCodeMap row = codeMapMapper.selectByNative(platformId, refType, nativeCode.trim());
        return row == null ? null : row.getExposedCode();
    }

    @Override
    public Map<String, String> exposedCodesOf(Long platformId, String refType, Collection<String> nativeCodes) {
        Map<String, String> out = new HashMap<>();
        if (platformId == null || nativeCodes == null || nativeCodes.isEmpty()) {
            return out;
        }
        List<String> codes = new ArrayList<>(nativeCodes.size());
        for (String code : nativeCodes) {
            if (StringUtils.hasText(code)) {
                codes.add(code.trim());
            }
        }
        if (codes.isEmpty()) {
            return out;
        }
        for (OutCodeMap row : codeMapMapper.selectByNatives(platformId, refType, codes)) {
            out.put(row.getNativeCode(), row.getExposedCode());
        }
        return out;
    }

    @Override
    @Transactional
    public void restore(Long platformId, String refType, String nativeCode) {
        OutCodeMap row = codeMapMapper.selectByNative(platformId, refType, nativeCode);
        if (row == null || !row.isTombstone()) {
            return;
        }
        codeMapMapper.updateState(row.getId(), OutCodeMap.STATE_ACTIVE, LocalDateTime.now());
        log.info("[上级共享] 暴露码恢复 platform={} type={} native={} exposed={}",
                platformId, refType, nativeCode, row.getExposedCode());
    }

    @Override
    public OutCodeMap resolve(Long platformId, String exposedCode) {
        if (platformId == null || !StringUtils.hasText(exposedCode)) {
            return null;
        }
        return codeMapMapper.selectByExposed(platformId, exposedCode.trim());
    }

    // ------------------------------------------------------------------

    /**
     * 已锁平台行之后的单条分配。
     *
     * @param existing 预加载的 {@code nativeCode → 映射}（批量场景一次查全）
     */
    private String ensureLocked(Long platformId, ResChannel channel, Map<String, OutCodeMap> existing) {
        String nativeCode = channel.getCode().trim();
        OutCodeMap row = existing.remove(nativeCode);
        if (row == null) {
            row = codeMapMapper.selectByNative(platformId, ShareRefType.CHANNEL, nativeCode);
        }
        if (row != null) {
            if (row.isTombstone()) {
                // 资源恢复：复用自己原来的码（绝不复用别的资源的墓碑码）
                codeMapMapper.updateState(row.getId(), OutCodeMap.STATE_ACTIVE, LocalDateTime.now());
                log.info("[上级共享] 通道恢复复用墓碑码 platform={} native={} exposed={}",
                        platformId, nativeCode, row.getExposedCode());
            }
            return row.getExposedCode();
        }

        String domain = localDomain();
        String nativeSeg = ExposedCode.segOfCode(nativeCode);
        String seg = ExposedCode.isValidSeg(nativeSeg) ? nativeSeg : ExposedCode.SEG_CHANNEL;

        // 本地资源透传：码已在本平台域内，上级可直接使用
        if (SourceType.isNative(channel.getSourceType())
                && ExposedCode.belongsTo(nativeCode, domain)
                && codeMapMapper.selectByExposed(platformId, nativeCode) == null) {
            OutCodeMap pass = newRow(platformId, ShareRefType.CHANNEL, nativeCode,
                    nativeCode, seg, PASS_THROUGH_SEQ);
            codeMapMapper.insert(pass);
            return nativeCode;
        }

        // 入站资源 / 本地但码不在本域：重编号
        OutCodeMap allocated = allocate(platformId, ShareRefType.CHANNEL, nativeCode, seg);
        return allocated.getExposedCode();
    }

    /** 预加载一页通道已有的映射（避免逐条查库） */
    private Map<String, OutCodeMap> preload(Long platformId, List<ResChannel> channels) {
        List<String> codes = new ArrayList<>(channels.size());
        for (ResChannel channel : channels) {
            if (channel != null && StringUtils.hasText(channel.getCode())) {
                codes.add(channel.getCode().trim());
            }
        }
        if (codes.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, OutCodeMap> out = new HashMap<>(codes.size() * 2);
        for (OutCodeMap row : codeMapMapper.selectByNatives(platformId, ShareRefType.CHANNEL, codes)) {
            out.put(row.getNativeCode(), row);
        }
        return out;
    }

    /**
     * 从 {@code MAX(seq_no)+1} 起找一个本平台未使用的暴露码。
     * <p>墓碑行不删，所以 {@code MAX} 天然覆盖已用过的号——"删除不复用"不需要额外的号段表。</p>
     */
    private OutCodeMap allocate(Long platformId, String refType, String nativeCode, String typeSeg) {
        String domain = localDomain();
        long seq = codeMapMapper.maxSeq(platformId, typeSeg) + 1;
        for (int i = 0; i < MAX_COLLISION_RETRY; i++, seq++) {
            String exposed;
            try {
                exposed = ExposedCode.of(domain, typeSeg, seq);
            } catch (IllegalArgumentException e) {
                throw new ServiceException("暴露码分配失败：平台 " + platformId + " 的 " + typeSeg
                        + " 号段已用尽，请走平台级编码重置");
            }
            if (codeMapMapper.selectByExposed(platformId, exposed) != null) {
                continue;
            }
            OutCodeMap row = newRow(platformId, refType, nativeCode, exposed, typeSeg, seq);
            codeMapMapper.insert(row);
            log.info("[上级共享] 分配暴露码 platform={} type={} native={} exposed={}",
                    platformId, refType, nativeCode, exposed);
            return row;
        }
        throw new ServiceException("暴露码分配失败：平台 " + platformId + " 连续撞码 "
                + MAX_COLLISION_RETRY + " 次，请检查 out_code_map 数据");
    }

    private OutCodeMap newRow(Long platformId, String refType, String nativeCode,
                              String exposedCode, String typeSeg, long seqNo) {
        OutCodeMap row = new OutCodeMap();
        row.setTenantId(DEFAULT_TENANT_ID);
        row.setPlatformId(platformId);
        row.setRefType(refType);
        row.setNativeCode(nativeCode);
        row.setExposedCode(exposedCode);
        row.setTypeSeg(typeSeg);
        row.setSeqNo(seqNo);
        row.setState(OutCodeMap.STATE_ACTIVE);
        return row;
    }

    private void lockPlatform(Long platformId) {
        Long locked = parentPlatformMapper.lockById(platformId);
        if (locked == null) {
            throw new ServiceException("上级平台不存在：" + platformId);
        }
    }
}
