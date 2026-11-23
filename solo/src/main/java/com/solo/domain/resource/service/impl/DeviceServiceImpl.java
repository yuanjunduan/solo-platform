package com.solo.domain.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.api.resource.dto.DeviceSaveRequest;
import com.solo.api.resource.dto.ResourcePageRequest;
import com.solo.common.ServiceException;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.resource.model.ResOrg;
import com.solo.domain.resource.model.ResourceStatus;
import com.solo.domain.resource.model.SourceType;
import com.solo.domain.resource.service.DeviceService;
import com.solo.domain.resource.support.ReadOnlyGuard;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import com.solo.infra.mapper.ResOrgMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 设备管理实现。
 *
 * <p>{@code org_path} 是设备所属组织的路径快照，写入时算一次；换组时由本类重算，
 * 组织换父时由 {@code OrgServiceImpl} 按前缀统一重写。两条路径都不靠 JOIN 取位置。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private static final String LOG_TAG = "资源设备";
    private static final Long NATIVE_PLATFORM_ID = 0L;

    private final ResDeviceMapper deviceMapper;
    private final ResOrgMapper orgMapper;
    private final ResChannelMapper channelMapper;
    private final ReadOnlyGuard readOnlyGuard;

    @Override
    public Page<ResDevice> pageQuery(ResourcePageRequest req) {
        long pageNo = req.getPage() == null || req.getPage() < 1 ? 1L : req.getPage();
        long pageSize = req.getPageSize() == null || req.getPageSize() < 1 ? 20L : req.getPageSize();

        LambdaQueryWrapper<ResDevice> w = new LambdaQueryWrapper<>();
        String keyword = trimToNull(req.getKeyword());
        if (keyword != null) {
            w.and(q -> q.like(ResDevice::getCode, keyword)
                    .or().like(ResDevice::getName, keyword)
                    .or().like(ResDevice::getDeviceIp, keyword));
        }
        if (req.getOrgId() != null) {
            ResOrg org = orgMapper.selectById(req.getOrgId());
            if (org == null) {
                throw new ServiceException("组织不存在");
            }
            if (req.isIncludeSubtree()) {
                // 前缀匹配天然含自身，且不会误命中兄弟 /1/50/
                w.likeRight(ResDevice::getOrgPath, org.getPath());
            } else {
                w.eq(ResDevice::getOrgId, org.getId());
            }
        }
        if (trimToNull(req.getSourceType()) != null) {
            w.eq(ResDevice::getSourceType, req.getSourceType().trim());
        }
        if (trimToNull(req.getStatus()) != null) {
            w.eq(ResDevice::getStatus, req.getStatus().trim());
        } else {
            w.ne(ResDevice::getStatus, ResourceStatus.REMOVED);
        }
        w.orderByAsc(ResDevice::getOrgPath).orderByAsc(ResDevice::getCode);

        return deviceMapper.selectPage(new Page<>(pageNo, pageSize), w);
    }

    @Override
    public ResDevice detail(Long id) {
        return requireDevice(id);
    }

    @Override
    @Transactional
    public ResDevice create(DeviceSaveRequest req) {
        String code = req.getCode().trim();
        if (deviceMapper.selectByBizKey(SourceType.NATIVE, NATIVE_PLATFORM_ID, code) != null) {
            throw new ServiceException(400, "设备编码已存在：" + code);
        }
        ResOrg org = requireWritableOrg(req.getOrgId(), "在该组织下新增设备");

        ResDevice device = new ResDevice();
        device.setId(IdWorker.getId());
        device.setCode(code);
        device.setName(req.getName().trim());
        device.setLocalName(trimToNull(req.getLocalName()));
        device.setOrgId(org.getId());
        device.setOrgPath(org.getPath());
        device.setDeviceIp(trimToNull(req.getDeviceIp()));
        device.setVendor(trimToNull(req.getVendor()));
        device.setModel(trimToNull(req.getModel()));
        device.setChannelCount(0);
        device.setSourceType(SourceType.NATIVE);
        device.setSourcePlatformId(NATIVE_PLATFORM_ID);
        device.setOriginPlatformId(NATIVE_PLATFORM_ID);
        device.setOriginCode(code);
        device.setOriginChain("");
        device.setHopCount(0);
        device.setStatus(ResourceStatus.ACTIVE);
        deviceMapper.insert(device);

        log.info("[{}] 新建设备 id={} code={} name={} orgId={}",
                LOG_TAG, device.getId(), code, device.getName(), org.getId());
        return device;
    }

    @Override
    @Transactional
    public void update(Long id, DeviceSaveRequest req) {
        ResDevice existing = requireDevice(id);
        readOnlyGuard.requireWritable(existing.getSourceType(), "修改设备");

        String code = req.getCode().trim();
        if (!code.equals(existing.getCode())) {
            throw new ServiceException(400, "设备编码建立后不可修改");
        }
        ResOrg org = requireWritableOrg(req.getOrgId(), "把设备挂到该组织");
        boolean orgChanged = !Objects.equals(existing.getOrgId(), org.getId());

        deviceMapper.update(null, new LambdaUpdateWrapper<ResDevice>()
                .eq(ResDevice::getId, id)
                .set(ResDevice::getName, req.getName().trim())
                .set(ResDevice::getLocalName, trimToNull(req.getLocalName()))
                .set(ResDevice::getDeviceIp, trimToNull(req.getDeviceIp()))
                .set(ResDevice::getVendor, trimToNull(req.getVendor()))
                .set(ResDevice::getModel, trimToNull(req.getModel()))
                .set(ResDevice::getOrgId, org.getId())
                .set(ResDevice::getOrgPath, org.getPath())
                .set(ResDevice::getUpdateTime, LocalDateTime.now()));

        if (orgChanged) {
            // 通道的位置是独立硬约束，不跟着设备走；这里只提示，不静默改用户数据
            log.info("[{}] 设备 id={} 换组 {} -> {}（通道归属不自动跟随）",
                    LOG_TAG, id, existing.getOrgId(), org.getId());
        }
        log.info("[{}] 编辑设备 id={} name={} orgId={}", LOG_TAG, id, req.getName(), org.getId());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ResDevice device = requireDevice(id);
        readOnlyGuard.requireWritable(device.getSourceType(), "删除设备");

        long channels = channelMapper.countByDeviceId(id);
        if (channels > 0) {
            throw new ServiceException(400, "该设备下还有 " + channels + " 路通道，请先删除通道");
        }
        deviceMapper.deleteById(id);
        log.info("[{}] 删除设备 id={} code={}", LOG_TAG, id, device.getCode());
    }

    private ResDevice requireDevice(Long id) {
        ResDevice device = deviceMapper.selectById(id);
        if (device == null) {
            throw new ServiceException("设备不存在");
        }
        return device;
    }

    /** 组织必须存在且来源可写：本地设备不能挂进外部平台的镜像组织 */
    private ResOrg requireWritableOrg(Long orgId, String scene) {
        if (orgId == null) {
            throw new ServiceException(400, "所属组织不能为空");
        }
        ResOrg org = orgMapper.selectById(orgId);
        if (org == null) {
            throw new ServiceException(400, "所属组织不存在");
        }
        readOnlyGuard.requireWritable(org.getSourceType(), scene);
        return org;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
