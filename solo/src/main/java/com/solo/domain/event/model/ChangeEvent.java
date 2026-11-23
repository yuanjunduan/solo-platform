package com.solo.domain.event.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资源变更事件（3.11.7）：同步/共享动作完成后给订阅方的增量通知。
 *
 * <p><b>两种形态只有两种</b>：{@link #SHAPE_DETAIL} 逐条明细、
 * {@link #SHAPE_REFRESH} 只说"某来源已刷新"。变化量超过
 * {@code event_detail_max}（默认 500）、组织树重建、删除被闸门跳过——
 * 四种情况一律降级成 REFRESH_ALL。</p>
 *
 * <p><b>游标为什么是自增 seq 而不是雪花 id</b>：SSE 的 {@code Last-Event-ID} 要求"单调且不跳号"。
 * 雪花 id 跨毫秒递增、同毫秒内多进程顺序不可控，做游标会有歧义；自增列天然就是插入顺序。
 * 降级成 REFRESH_ALL 时也必须落一行占住 seq——<b>事件可以合并，游标不能跳号</b>。</p>
 *
 * <p>只带定位不带全量：payload 里放 code/name/online_status 这类关键字段，
 * 客户端拿不到全量目录才有动力去调快照接口（也是 3.12.5 的防越权要求）。</p>
 */
@Schema(description = "资源变更事件：SSE 的游标源")
@Data
@TableName("change_event")
public class ChangeEvent {

    /** 形态：逐条明细 */
    public static final String SHAPE_DETAIL = "CHANGED_DETAIL";
    /** 形态：只说"已刷新"，客户端应重拉快照 */
    public static final String SHAPE_REFRESH = "REFRESH_ALL";

    public static final String CHANGE_ADDED = "ADDED";
    public static final String CHANGE_UPDATED = "UPDATED";
    public static final String CHANGE_REMOVED = "REMOVED";
    public static final String CHANGE_REFRESH = "REFRESH";

    public static final String ITEM_ORG = "ORG";
    public static final String ITEM_GROUP = "GROUP";
    public static final String ITEM_DEVICE = "DEVICE";
    public static final String ITEM_CHANNEL = "CHANNEL";

    /** 与 res_* 的 source_type 同义 */
    public static final String SOURCE_INBOUND = "INBOUND";
    public static final String SOURCE_NATIVE = "NATIVE";

    @Schema(description = "全局游标：SSE 的 Last-Event-ID")
    @TableId(value = "seq", type = IdType.AUTO)
    private Long seq;

    private Long tenantId;

    @Schema(description = "NATIVE/INBOUND")
    private String sourceType;

    @Schema(description = "INBOUND 的来源平台；NATIVE 为 NULL")
    private Long platformId;

    @Schema(description = "写库后回读的组织路径：订阅 scope 二次过滤用")
    private String orgPath;

    @Schema(description = "ORG/GROUP/DEVICE/CHANNEL")
    private String itemType;

    @Schema(description = "资源码；REFRESH_ALL 无明细时为 NULL")
    private String code;

    @Schema(description = "ADDED/UPDATED/REMOVED/REFRESH")
    private String changeType;

    @Schema(description = "CHANGED_DETAIL/REFRESH_ALL")
    private String shape;

    @Schema(description = "关键字段 JSON：只带定位不带全量")
    private String payload;

    @Schema(description = "关联的同步轮次（sync_task.id）")
    private Long taskId;

    private LocalDateTime createTime;
}
