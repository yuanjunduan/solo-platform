package com.solo.domain.resource.support;

import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResourceStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 同源组内的取流顺序（3.10.3「播放」行）。
 *
 * <p>同一台物理通道经两条路径进来时保留两行，播放时按固定顺序挑一条：</p>
 * <ol>
 *   <li><b>用户点的那条优先</b>：手动选路是明确意图，能用就用它，不擅自替换；</li>
 *   <li>其余按 <b>在线 → 跳数少 → id 小</b>：在线比什么都重要；同样在线时跳数少意味着链路短、
 *       少一次转发（3.11.5 的固有时延），这也是本文档里"延迟"唯一可得的代理指标；</li>
 *   <li>已消失（REMOVED）的行直接剔除：它只是历史记录，不再是一条可用的链路。</li>
 * </ol>
 *
 * <p>纯函数，不查库：候选集由调用方按 {@code canonical_id} 取好，排序规则可单测。</p>
 */
public final class CanonicalRouteOrder {

    private CanonicalRouteOrder() {
    }

    /**
     * @param candidates  同源组成员（可含请求行本身）
     * @param requestedId 用户点的行 id；{@code null} = 没有偏好，纯按在线/跳数/ id 排
     * @return 排好序的新列表；REMOVED 已剔除
     */
    public static List<ResChannel> order(List<ResChannel> candidates, Long requestedId) {
        List<ResChannel> usable = new ArrayList<>();
        if (candidates != null) {
            for (ResChannel candidate : candidates) {
                if (candidate != null && !ResourceStatus.REMOVED.equals(candidate.getStatus())) {
                    usable.add(candidate);
                }
            }
        }
        usable.sort(Comparator
                .comparingInt((ResChannel c) -> isRequested(c, requestedId) ? 0 : 1)
                .thenComparingInt(c -> isOnline(c) ? 0 : 1)
                .thenComparingInt(CanonicalRouteOrder::hopOf)
                .thenComparing(ResChannel::getId, Comparator.nullsLast(Long::compareTo)));
        return usable;
    }

    private static boolean isRequested(ResChannel channel, Long requestedId) {
        return requestedId != null && requestedId.equals(channel.getId());
    }

    private static boolean isOnline(ResChannel channel) {
        return "ON".equalsIgnoreCase(channel.getOnlineStatus());
    }

    /** 跳数缺失按最大处理：不知道跳几次就不要把它排在没有证据的位置前面 */
    private static int hopOf(ResChannel channel) {
        Integer hop = channel.getHopCount();
        return hop == null || hop < 0 ? Integer.MAX_VALUE : hop;
    }
}
