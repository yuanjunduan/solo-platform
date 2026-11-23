package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.sync.model.PlatformCatalogVer;
import org.apache.ibatis.annotations.Mapper;

/**
 * 平台目录指纹（3.8.2 第 3e 步）。
 *
 * <p>主键是 {@code platform_id} 而不是雪花 id——一个平台只有一行，
 * 用业务主键可以直接 upsert，不需要先查后插。</p>
 */
@Mapper
public interface PlatformCatalogVerMapper extends BaseMapper<PlatformCatalogVer> {
}
