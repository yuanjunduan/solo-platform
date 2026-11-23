package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.resource.model.ResChannelPreset;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通道预置位（M6）：编号唯一性由 {@code uk_channel_preset(channel_row_id, preset_index)} 保证。
 */
@Mapper
public interface ResChannelPresetMapper extends BaseMapper<ResChannelPreset> {
}
