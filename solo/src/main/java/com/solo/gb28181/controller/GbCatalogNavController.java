package com.solo.gb28181.controller;

import com.solo.aop.RequirePermission;
import com.solo.common.Result;
import com.solo.gb28181.request.GbCatalogNavRequest;
import com.solo.gb28181.response.GbCatalogNavVO;
import com.solo.gb28181.service.GbCatalogNavService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目录左树懒加载接口。
 *
 * <p>设备页与通道页共用：两页的权限码不同，这里用 ANY 语义（{@link RequirePermission}
 * 默认值）同时放行，避免为了共用一棵树给角色多开权限。</p>
 */
@Tag(name = "国标目录左树", description = "设备/通道页左侧目录树（按域切换数据源，逐层懒加载）")
@RestController
@RequestMapping("/api/gb28181/catalog")
@RequiredArgsConstructor
public class GbCatalogNavController {

    private final GbCatalogNavService navService;

    @Operation(summary = "懒加载一层目录树",
            description = "domain=LOCAL 本地组织树；SUBORDINATE 下级平台 + 镜像分组；REMOTE 空层；"
                    + "ALL 根层把本地组织根与各平台并列。parentKey 为空取根层，"
                    + "非空按 key 前缀展开（org:{id} / plat:{端点码} / grp:{端点码}:{组织码}）。")
    @GetMapping("/nav")
    @RequirePermission({"gb28181:device:list", "gb28181:device-channel:list"})
    public Result<GbCatalogNavVO> nav(
            @Parameter(description = "资源域：LOCAL / SUBORDINATE / REMOTE / ALL")
            @RequestParam(required = false, defaultValue = "ALL") String domain,
            @Parameter(description = "展开的节点 key；不传=根层")
            @RequestParam(required = false) String parentKey,
            @Parameter(description = "名称/编码关键字，只过滤当前层")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "单层返回上限，默认 200，最大 500")
            @RequestParam(required = false, defaultValue = "200") Integer limit) {
        GbCatalogNavRequest request = new GbCatalogNavRequest();
        request.setDomain(domain);
        request.setParentKey(parentKey);
        request.setKeyword(keyword);
        request.setLimit(limit);
        return Result.success(navService.nav(request));
    }
}
