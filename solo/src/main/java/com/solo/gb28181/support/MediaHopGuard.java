package com.solo.gb28181.support;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 每跳独立的互斥与冷却（架构约束 T5.3 / 6.6）。
 *
 * <p><b>为什么不能用一个通道级锁</b>：一条通道可能同时被本地点播、A 上级、B 上级点播。
 * 如果三者抢同一把锁，A 上级的一个失败或断线会把 B 上级一起锁住，表现为"别人断线我家黑屏"。
 * 所以锁按"跳"分配：</p>
 * <ul>
 *   <li>{@link #inboundKey} —— <b>入站取流腿</b>：把设备/下级平台的流拉进 ZLM。
 *       一路源流天生只该被拉一次，所有上级共享它，因此这条腿按 {@code streamId} 独占。</li>
 *   <li>{@link #outboundKey} —— <b>出站推流腿</b>：把源流推给某个上级。
 *       每个上级、每条通道各持一把，互不影响。</li>
 * </ul>
 *
 * <p><b>为什么不用 {@code ReentrantLock} 的 Map</b>：锁用完后 {@code remove} 会和"另一个线程已拿到旧锁对象"
 * 形成两个不同锁同时进临界区的竞态；这里只需要"同一跳同时只有一个在飞"，
 * 用 {@code putIfAbsent} 的标记位即可，语义更窄也更难写错。</p>
 *
 * <p><b>冷却</b>：同一点播反复失败时，前端/上级习惯性重试会把本来就很慢的设备打成雪崩。
 * 失败后在这一跳上记一个截止时间，冷却期内的请求快速失败，不再触碰设备。</p>
 *
 * <p>本类只做抑制，不做排队；争抢失败由调用方翻译成 486 BUSY_HERE 或业务提示。</p>
 */
@Component
public class MediaHopGuard {

    /** 入站取流腿的 key：一路源流唯一 */
    private static final String IN_PREFIX = "in:";
    /** 出站推流腿的 key：上级平台 + 上级看到的通道编码 */
    private static final String OUT_PREFIX = "out:";

    /** value 用固定布尔值，表示"这一跳有人在飞" */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    /** key → 冷却截止的毫秒时间戳 */
    private final ConcurrentHashMap<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public MediaHopGuard() {
        this(System::currentTimeMillis);
    }

    /** 仅供单测注入假时钟 */
    MediaHopGuard(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** 入站取流腿：把 {@code streamId} 对应的源流拉进 ZLM */
    public static String inboundKey(String streamId) {
        return IN_PREFIX + streamId;
    }

    /**
     * 出站推流腿：推给某个上级的某条通道。
     *
     * <p>用"上级平台 ID + 上级看到的通道编码"而不是本地通道 ID：同一路本地源流可以被多条
     * 暴露码引用（派生共享、来源切换后的双绑定），只有暴露码能唯一定位上级那一侧的节点。</p>
     */
    public static String outboundKey(Long platformId, String channelId) {
        return OUT_PREFIX + platformId + ":" + channelId;
    }

    /**
     * 尝试占用这一跳。
     *
     * @return {@code true} 拿到，调用方必须 {@code finally} 里 {@link #release}；
     *         {@code false} 说明同一条腿已有请求在飞
     */
    public boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) {
            // key 拼不出来说明上游参数有问题，放行让业务层报出更准确的错误
            return true;
        }
        return inFlight.putIfAbsent(key, Boolean.TRUE) == null;
    }

    /** 释放这一跳；幂等，重复调用无副作用 */
    public void release(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        inFlight.remove(key);
    }

    /**
     * 给这一跳记一段冷却。
     *
     * @param millis 冷却时长；&le; 0 表示不冷却（用于配置关闭）
     */
    public void cooldown(String key, long millis) {
        if (key == null || key.isBlank() || millis <= 0) {
            return;
        }
        cooldownUntil.put(key, clock.getAsLong() + millis);
    }

    /**
     * 剩余冷却时长（毫秒）。
     *
     * <p>读到已过期的条目顺手删掉：没有人再来问的 key 不会自己消失，
     * 靠读路径回收即可，无需额外清扫线程。</p>
     */
    public long cooldownRemainingMs(String key) {
        if (key == null || key.isBlank()) {
            return 0L;
        }
        Long until = cooldownUntil.get(key);
        if (until == null) {
            return 0L;
        }
        long remain = until - clock.getAsLong();
        if (remain <= 0L) {
            cooldownUntil.remove(key, until);
            return 0L;
        }
        return remain;
    }

    /** 清空某一跳的冷却（点播成功时调用，让"刚恢复"的通道立刻可用） */
    public void clearCooldown(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        cooldownUntil.remove(key);
    }

    /** 诊断用：当前在飞的跳数 */
    public int inFlightCount() {
        return inFlight.size();
    }

    /** 诊断用：当前处于冷却期的 key 数（含已过期但未被读取的条目） */
    public int coolingCount() {
        return cooldownUntil.size();
    }
}
