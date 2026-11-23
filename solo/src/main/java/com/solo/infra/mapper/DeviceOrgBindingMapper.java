package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.sync.model.DeviceOrgBinding;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 设备↔组织人工绑定（C13 回填第 ② 步）。
 */
@Mapper
public interface DeviceOrgBindingMapper extends BaseMapper<DeviceOrgBinding> {

    default DeviceOrgBinding selectByDevice(Long sourcePlatformId, String deviceCode) {
        List<DeviceOrgBinding> list = selectList(new LambdaQueryWrapper<DeviceOrgBinding>()
                .eq(DeviceOrgBinding::getSourcePlatformId, sourcePlatformId)
                .eq(DeviceOrgBinding::getDeviceCode, deviceCode)
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }
}
