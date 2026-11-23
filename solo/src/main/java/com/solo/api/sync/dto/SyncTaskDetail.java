package com.solo.api.sync.dto;

import com.solo.domain.sync.model.SyncRecord;
import com.solo.domain.sync.model.SyncRound;
import com.solo.domain.sync.model.SyncTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 同步轮次详情（同步中心右侧抽屉）。
 *
 * <p>四个数字之外必须带 <b>deleteSkipped / guardRatio / orgMissingCount</b>：
 * 它们是"那天为什么没删"和"为什么有资源挂在[未分类]"的唯一答案（C11/C13/C23）。
 * 只给"成功/失败"的详情页，运维还得去翻库。</p>
 */
@Schema(description = "同步轮次详情")
@Data
public class SyncTaskDetail {

    private SyncTask task;

    @Schema(description = "轮次台账（3.13.1）：执行事实，含当时的 deleteSkipped / guardRatio")
    private SyncRound round;

    @Schema(description = "平台名称，便于抽屉直接展示")
    private String platformName;

    @Schema(description = "平台编码")
    private String platformCode;

    @Schema(description = "最近一次成功轮次的目录指纹（platform_catalog_ver）")
    private String lastFingerprint;

    @Schema(description = "该轮的单条变更留痕；M2a 尚未写入，通常是空列表")
    private List<SyncRecord> records;

    @Schema(description = "是否还有活跃轮次（决定「取消」按钮是否可点）")
    private boolean active;
}
