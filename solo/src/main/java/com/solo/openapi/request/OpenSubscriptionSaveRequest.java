package com.solo.openapi.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 订阅 scope 编辑（6.4 / Q10）。
 *
 * <p>两个 scope 字段<b>留空即表示"不裁剪"</b>：{@code platformId} 空 = 所有来源平台，
 * {@code orgPath} 空 = 整租户。所以编辑接口必须能把它们改回空，后端用的是显式 SET
 * 的 SQL（{@code updateScope}），不是 MP 的按非空字段更新。</p>
 */
@Data
public class OpenSubscriptionSaveRequest {

    @NotNull(message = "订阅 ID 不能为空")
    private Long id;

    /** scope 一级：来源平台；空 = 全部 */
    private Long platformId;

    /** scope 二级：组织子树前缀（如 /1/5/）；空 = 整租户 */
    private String orgPath;

    /** 1 启用 / 0 停用 */
    private Integer status;

    private String remark;
}
