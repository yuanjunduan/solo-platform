package com.solo.gb28181.service;

import com.solo.gb28181.request.GbCatalogNavRequest;
import com.solo.gb28181.response.GbCatalogNavVO;

/**
 * 设备/通道页左侧目录树：按域切换数据源，逐层懒加载。
 *
 * <p>三套数据源共用这一个接口，是因为前端只关心"当前层有哪些节点"，
 * 而展开规则（组织 → 组织、平台 → 分组、分组 → 分组）由后端按 key 前缀分派：
 * <ul>
 *   <li>LOCAL：{@code gb_org} 组织树，可挂本地设备</li>
 *   <li>SUBORDINATE：{@code gb_platform_registry}(DOWNSTREAM) 为根，下面挂目录镜像分组</li>
 *   <li>REMOTE：预留，本轮无数据源，返回空层</li>
 *   <li>ALL：根层把本地组织根与各平台并列</li>
 * </ul></p>
 */
public interface GbCatalogNavService {

    /** 取一层节点；parentKey 为空表示根层 */
    GbCatalogNavVO nav(GbCatalogNavRequest request);
}
