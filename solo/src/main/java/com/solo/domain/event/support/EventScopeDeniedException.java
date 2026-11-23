package com.solo.domain.event.support;

/**
 * 请求的 scope 超出订阅授权范围（3.12.5 "未授权订阅其他平台资源"）。
 *
 * <p>为什么不直接按请求参数裁剪、而是拒绝：静默裁剪会让对接方以为"这个平台是空的"，
 * 排查方向完全跑偏。明确拒绝 + 说明原因，才是"每个入口单独校验"的正确姿势。</p>
 */
public class EventScopeDeniedException extends RuntimeException {

    public EventScopeDeniedException(String message) {
        super(message);
    }
}
