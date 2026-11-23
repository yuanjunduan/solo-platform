package com.solo.gb28181.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.solo.gb28181.constant.GbCascadeDirection;
import com.solo.gb28181.entity.GbDevice;
import com.solo.gb28181.entity.GbPlatformRegistry;
import com.solo.gb28181.mapper.GbPlatformRegistryMapper;
import com.solo.gb28181.service.GbPlatformRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GbPlatformRegistryServiceImpl
        extends ServiceImpl<GbPlatformRegistryMapper, GbPlatformRegistry>
        implements GbPlatformRegistryService {

    @Override
    public void upsertOnRegister(GbDevice device) {
        if (device == null || device.getDeviceId() == null || device.getDeviceId().isBlank()) {
            return;
        }
        String gbId = device.getDeviceId();
        try {
            GbPlatformRegistry exist = lambdaQuery()
                    .eq(GbPlatformRegistry::getDirection, GbCascadeDirection.DOWNSTREAM)
                    .eq(GbPlatformRegistry::getPlatformGbId, gbId)
                    .one();
            if (exist == null) {
                GbPlatformRegistry row = new GbPlatformRegistry();
                row.setPlatformGbId(gbId);
                row.setDirection(GbCascadeDirection.DOWNSTREAM);
                row.setKind(GbCascadeDirection.kindOf(gbId));
                row.setName(displayName(device));
                row.setSipIp(device.getIp());
                row.setSipPort(device.getPort());
                row.setOnlineStatus(1);
                row.setChannelCount(0);
                row.setGroupCount(0);
                row.setEnabled(1);
                save(row);
                log.info("[平台登记] 新增下级登记 gbId={} kind={} name={}",
                        gbId, row.getKind(), row.getName());
                return;
            }
            // 只刷新运行期字段：名称留空才回填，避免覆盖人工改过的展示名
            exist.setOnlineStatus(1);
            exist.setSipIp(device.getIp());
            exist.setSipPort(device.getPort());
            if (exist.getName() == null || exist.getName().isBlank()) {
                exist.setName(displayName(device));
            }
            updateById(exist);
        } catch (Exception e) {
            // 登记失败不能影响设备注册主流程：左树缺一行是降级，注册被打断是故障
            log.warn("[平台登记] 注册登记失败 gbId={} err={}", gbId, e.getMessage());
        }
    }

    @Override
    public void markOffline(String platformGbId) {
        if (platformGbId == null || platformGbId.isBlank()) {
            return;
        }
        try {
            lambdaUpdate()
                    .eq(GbPlatformRegistry::getDirection, GbCascadeDirection.DOWNSTREAM)
                    .eq(GbPlatformRegistry::getPlatformGbId, platformGbId)
                    .set(GbPlatformRegistry::getOnlineStatus, 0)
                    .update();
        } catch (Exception e) {
            log.warn("[平台登记] 置离线失败 gbId={} err={}", platformGbId, e.getMessage());
        }
    }

    @Override
    public void refreshCatalogStats(String platformGbId) {
        if (platformGbId == null || platformGbId.isBlank()) {
            return;
        }
        try {
            int rows = baseMapper.refreshCatalogStats(platformGbId);
            if (rows == 0) {
                // 目录先于注册到达（或登记行被手工删除）：补一行，保证左树能看到该来源
                GbPlatformRegistry row = new GbPlatformRegistry();
                row.setPlatformGbId(platformGbId);
                row.setDirection(GbCascadeDirection.DOWNSTREAM);
                row.setKind(GbCascadeDirection.kindOf(platformGbId));
                row.setName(platformGbId);
                row.setOnlineStatus(0);
                row.setEnabled(1);
                save(row);
                baseMapper.refreshCatalogStats(platformGbId);
                log.info("[平台登记] 目录先于注册到达，已补登记 gbId={}", platformGbId);
            }
        } catch (Exception e) {
            log.warn("[平台登记] 同步统计刷新失败 gbId={} err={}", platformGbId, e.getMessage());
        }
    }

    @Override
    public List<GbPlatformRegistry> listByDirection(String direction) {
        return lambdaQuery()
                .eq(GbPlatformRegistry::getDirection, direction)
                .eq(GbPlatformRegistry::getEnabled, 1)
                .orderByDesc(GbPlatformRegistry::getChannelCount)
                .orderByAsc(GbPlatformRegistry::getPlatformGbId)
                .list();
    }

    private static String displayName(GbDevice device) {
        if (device.getName() != null && !device.getName().isBlank()) {
            return device.getName();
        }
        if (device.getReportName() != null && !device.getReportName().isBlank()) {
            return device.getReportName();
        }
        return device.getDeviceId();
    }
}
