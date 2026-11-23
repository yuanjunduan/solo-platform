package com.solo.domain.sync.model;

/**
 * 码链的一跳：哪家的哪个码（3.7.2）。
 *
 * <p>M2a 只落"直接来源"这一跳；多级级联（M5）才需要完整链，用来回答
 * "这个通道在上上级叫什么、经过哪几个平台重编号"。</p>
 */
public record CodeHop(Long platformId, String code) {
}
