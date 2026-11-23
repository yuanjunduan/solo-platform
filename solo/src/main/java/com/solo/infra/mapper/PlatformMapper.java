package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.platform.model.Platform;
import org.apache.ibatis.annotations.Mapper;

/**
 * 平台连接持久化。M1 只提供基础 CRUD；心跳、注册事件与状态推进随 M2 落地。
 *
 * <p>注意与本平台作为下级时连接的上级平台区分：出站共享的目标是
 * {@code gb_parent_platform}（见 {@code GbParentPlatformMapper}），不是本表。</p>
 */
@Mapper
public interface PlatformMapper extends BaseMapper<Platform> {
}
