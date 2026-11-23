package com.solo.domain.sync.model;

/**
 * 一条"可变列"定义：本地资源表列 ↔ 暂存表列。
 *
 * <p><b>这个类存在的唯一理由</b>：集合 SQL 的 {@code SET} 列集与 {@code WHERE} 差异列集
 * 必须由同一份清单派生（C9）。两处各写一遍的后果是"改了一边漏了另一边"，
 * 表现为"数据明明没变却每次都全量 UPDATE"——百万行级别足以拖垮整轮同步。</p>
 *
 * @param resColumn     本地资源表列名（{@code res_device} / {@code res_channel}）
 * @param stagingColumn 暂存表列名（{@code sync_staging}）
 */
public record MutableColumn(String resColumn, String stagingColumn) {

    public static MutableColumn of(String column) {
        return new MutableColumn(column, column);
    }
}
