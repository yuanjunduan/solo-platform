package com.solo.openapi.request;

import lombok.Data;

/** 订阅管理分页查询（管理端，走平台 JWT 与 RBAC，不是开放 API） */
@Data
public class OpenSubscriptionPageRequest {

    private Long page;

    private Long pageSize;

    /** appKey 模糊匹配 */
    private String appKey;

    /** 1 启用 / 0 停用；不传则全部 */
    private Integer status;
}
