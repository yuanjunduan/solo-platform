package com.solo.domain.resource.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.api.resource.dto.DeviceSaveRequest;
import com.solo.api.resource.dto.ResourcePageRequest;
import com.solo.domain.resource.model.ResDevice;

/**
 * 设备管理（本地设备）。
 *
 * <p>设备是通道的"取流载体"，所以组织归属在设备上落一次，通道再各自落一次：
 * 通道可以从设备继承位置，但它自己那一份是硬约束，不能为 null（架构约束 C4）。</p>
 */
public interface DeviceService {

    /** 分页查询（关键词 / 组织子树 / 来源 / 状态） */
    Page<ResDevice> pageQuery(ResourcePageRequest req);

    /** 详情，不存在时报错 */
    ResDevice detail(Long id);

    /** 新建设备（组织必须存在且可写） */
    ResDevice create(DeviceSaveRequest req);

    /** 编辑设备；换组织时同步刷新 org_path 快照 */
    void update(Long id, DeviceSaveRequest req);

    /** 删除设备；名下还有通道时拒绝 */
    void delete(Long id);
}
