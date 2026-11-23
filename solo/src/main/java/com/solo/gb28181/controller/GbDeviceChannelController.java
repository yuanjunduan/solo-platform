package com.solo.gb28181.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.aop.OperationLog;
import com.solo.aop.RequirePermission;
import com.solo.common.enums.BusinessType;
import com.solo.common.PageResult;
import com.solo.common.Result;
import com.solo.common.ServiceException;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.request.GbChannelSubtreePageRequest;
import com.solo.gb28181.request.GbDeviceChannelBatchDeleteRequest;
import com.solo.gb28181.request.GbDeviceChannelPageRequest;
import com.solo.gb28181.response.GbCatalogSourceVO;
import com.solo.gb28181.response.GbChannelStatusCountVO;
import com.solo.gb28181.response.GbChannelTreeNodeVO;
import com.solo.gb28181.response.GbChannelTreeLevelVO;
import com.solo.gb28181.response.GbDeviceChannelResponse;
import com.solo.gb28181.service.GbDeviceChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "国标设备通道管理", description = "国标设备通道管理")
@RestController
@RequestMapping("/api/gb28181/device-channel")
@RequiredArgsConstructor
public class GbDeviceChannelController {

    private final GbDeviceChannelService channelService;

    @Operation(summary = "分页查询通道")
    @PostMapping("/page")
    @RequirePermission({"gb28181:device-channel:list"})
    public Result<PageResult<GbDeviceChannelResponse>> page(@RequestBody GbDeviceChannelPageRequest request) {
        Page<GbDeviceChannelResponse> page = channelService.pageQuery(request);
        return Result.success(new PageResult<>(page.getTotal(), page.getRecords()));
    }


    @Operation(summary = "目录来源列表（推过目录的端点：下级平台 / 接入设备）")
    @GetMapping("/sources")
    @RequirePermission({"gb28181:device-channel:list"})
    public Result<List<GbCatalogSourceVO>> sources() {
        return Result.success(channelService.catalogSources());
    }

    @Operation(summary = "按状态统计通道数")
    @GetMapping("/status-count")
    @RequirePermission({"gb28181:device-channel:list"})
    public Result<List<GbChannelStatusCountVO>> statusCount(
            @Parameter(description = "来源端点国标编码；不传=全部来源")
            @RequestParam(required = false) String deviceId,
            @Parameter(description = "历史参数（等价于 deviceId，仅兼容旧调用）")
            @RequestParam(required = false) String platformId,
            @Parameter(description = "组织 id；-1=未归属；不传=不按组织过滤")
            @RequestParam(required = false) Long orgId,
            @Parameter(description = "资源域：LOCAL / SUBORDINATE / REMOTE / ALL；不传=全部")
            @RequestParam(required = false) String domain,
            @Parameter(description = "按组织统计时是否含子孙组织，默认 true")
            @RequestParam(defaultValue = "true") boolean includeSubtree,
            @Parameter(description = "左树选中分组时的 tree_path 前缀；不传=不按分组过滤")
            @RequestParam(required = false) String pathPrefix) {
        return Result.success(channelService.statusCount(deviceId, platformId, orgId, domain,
                includeSubtree, pathPrefix));
    }

    @Operation(summary = "按设备国标ID列出全部通道")
    @Parameter(name = "deviceId", description = "设备国标 20 位 ID", in = ParameterIn.PATH)
    @GetMapping("/by-device/{deviceId}")
    @RequirePermission({"gb28181:device-channel:list"})
    public Result<List<GbDeviceChannel>> listByDevice(@PathVariable String deviceId) {
        List<GbDeviceChannel> list = channelService.lambdaQuery()
                .eq(GbDeviceChannel::getDeviceId, deviceId)
                .eq(GbDeviceChannel::getParental, 0)
                .orderByAsc(GbDeviceChannel::getChannelId)
                .list();
        return Result.success(list);
    }

    @Operation(summary = "按设备查询目录树（分组 + 通道）")
    @Parameter(name = "deviceId", description = "设备国标 20 位 ID", in = ParameterIn.PATH)
    @GetMapping("/tree/{deviceId}")
    @RequirePermission({"gb28181:device-channel:list"})
    public Result<List<GbChannelTreeNodeVO>> tree(@PathVariable String deviceId) {
        return Result.success(channelService.treeByDevice(deviceId));
    }

    @Operation(summary = "目录树懒加载一层（含子树通道数）")
    @GetMapping("/tree/children")
    @RequirePermission({"gb28181:device-channel:list"})
    public Result<GbChannelTreeLevelVO> treeChildren(
            @Parameter(description = "设备国标 20 位 ID") @RequestParam String deviceId,
            @Parameter(description = "组织国标码；不传=设备根") @RequestParam(required = false) String orgCode) {
        return Result.success(channelService.treeChildren(deviceId, orgCode));
    }

    @Operation(summary = "选中组织下的通道分页（可包含子组织）")
    @PostMapping("/subtree/page")
    @RequirePermission({"gb28181:device-channel:list"})
    public Result<PageResult<GbDeviceChannelResponse>> subtreePage(@RequestBody GbChannelSubtreePageRequest request) {
        Page<GbDeviceChannelResponse> page = channelService.subtreePage(request);
        return Result.success(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    @Operation(summary = "新增通道")
    @OperationLog(module = "国标通道管理", type = BusinessType.ADD, description = "新增通道")
    @PostMapping("/save")
    @RequirePermission({"gb28181:device-channel:add"})
    public Result<Void> save(@RequestBody GbDeviceChannel channel) {
        channelService.createByManual(channel);
        return Result.success();
    }

    @Operation(summary = "编辑通道")
    @OperationLog(module = "国标通道管理", type = BusinessType.EDIT, description = "编辑通道")
    @PostMapping("/update")
    @RequirePermission({"gb28181:device-channel:edit"})
    public Result<Void> update(@RequestBody GbDeviceChannel channel) {
        if (channel.getId() == null) {
            throw new ServiceException("通道ID不能为空");
        }
        channelService.updateByManual(channel);
        return Result.success();
    }

    @Operation(summary = "删除通道")
    @OperationLog(module = "国标通道管理", type = BusinessType.DELETE, description = "删除通道")
    @Parameter(name = "id", description = "通道主键 ID", in = ParameterIn.PATH)
    @DeleteMapping("/{id}")
    @RequirePermission({"gb28181:device-channel:delete"})
    public Result<Void> delete(@PathVariable Long id) {
        channelService.deleteById(id);
        return Result.success();
    }

    @Operation(summary = "批量删除通道")
    @OperationLog(module = "国标通道管理", type = BusinessType.DELETE, description = "批量删除通道")
    @PostMapping("/batch/delete")
    @RequirePermission({"gb28181:device-channel:delete"})
    @Transactional
    public Result<Void> batchDelete(@RequestBody GbDeviceChannelBatchDeleteRequest request) {
        channelService.deleteBatch(request.getIds());
        return Result.success();
    }

}
