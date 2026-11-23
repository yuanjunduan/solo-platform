package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.resource.model.ResGroup;
import org.apache.ibatis.annotations.Mapper;

/**
 * 目录分组持久化（215/216）。M1 只建表与 Mapper，写入链路随 M2 的 Catalog 同步落地。
 */
@Mapper
public interface ResGroupMapper extends BaseMapper<ResGroup> {
}
