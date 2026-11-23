package com.solo.domain.sync.spi;

import com.solo.domain.sync.model.CatalogItem;

import java.util.List;

/**
 * 一批目录项（3.8.2 synching 阶段的最小单位）。
 *
 * @param items         本批条目
 * @param expectedTotal 对端声明的总条数（GB28181 的 SumNum），用于进度与 G1 空批判断
 * @param declaredNum   本批报文声明条数，与 {@code items.size()} 不等说明解析丢条
 */
public record CatalogBatch(List<CatalogItem> items, Integer expectedTotal, Integer declaredNum) {
}
