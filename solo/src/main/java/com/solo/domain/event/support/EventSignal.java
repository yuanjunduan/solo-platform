package com.solo.domain.event.support;

import org.springframework.stereotype.Component;

/**
 * 进程内的"有新事件"信号（3.11.7 / 6.4 的唤醒手段）。
 *
 * <p><b>它只是加速器，不是投递通道</b>：事件真正的来源是 {@code change_event} 表，
 * 信号丢了最多让连接多睡一个空闲周期（{@code event.idle-poll-ms}），不会丢事件。
 * 所以这里不需要 Redis 发布订阅，也不需要处理"多实例收不到"的问题——
 * 多实例场景本来就是各连各的库、各推各的游标。</p>
 *
 * <p>用 {@code notifyAll} 而不是每人一个 Future：订阅方数量是"集成方级别"（个位数到几十），
 * 一起醒来各查一次库的成本可以接受，换来的是没有连接登记表要维护。</p>
 */
@Component
public class EventSignal {

    private final Object monitor = new Object();

    /** 有新事件写入（事务内调用也无妨：早醒一次查不到就再睡） */
    public void raise() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    /**
     * @return true=被信号唤醒；false=睡满超时（调用方据此判断该发心跳了）
     */
    public boolean await(long millis) {
        if (millis <= 0) {
            return false;
        }
        synchronized (monitor) {
            try {
                monitor.wait(millis);
                return true;
            } catch (InterruptedException e) {
                // 关停时打断等待，交给调用方检查停止标记
                Thread.currentThread().interrupt();
                return true;
            }
        }
    }
}
