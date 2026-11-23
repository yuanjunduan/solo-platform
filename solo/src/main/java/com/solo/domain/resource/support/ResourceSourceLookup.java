package com.solo.domain.resource.support;

import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.ResDevice;
import com.solo.domain.resource.model.ResGroup;
import com.solo.domain.resource.model.ResOrg;
import com.solo.domain.resource.model.ResourceKind;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import com.solo.infra.mapper.ResGroupMapper;
import com.solo.infra.mapper.ResOrgMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 按 id 回查资源来源（只读收口第一层要用）。
 *
 * <p>只查一列 {@code source_type}，不把整行拉回来：注解层在每次写请求上都会跑，
 * 多查十几列没有意义。同理也不做缓存——资源可能刚被同步改成 STALE，
 * 守卫拿的是"这一刻库里的事实"。</p>
 */
@Component
@RequiredArgsConstructor
public class ResourceSourceLookup {

    private final ResOrgMapper orgMapper;
    private final ResGroupMapper groupMapper;
    private final ResDeviceMapper deviceMapper;
    private final ResChannelMapper channelMapper;

    /**
     * @return 来源类型；资源不存在时返回 {@code null}（交给业务方法自己报 404，守卫不抢这个错）
     */
    public String sourceTypeOf(ResourceKind kind, Long id) {
        if (kind == null || id == null) {
            return null;
        }
        return switch (kind) {
            case ORG -> {
                ResOrg row = orgMapper.selectById(id);
                yield row == null ? null : row.getSourceType();
            }
            case GROUP -> {
                ResGroup row = groupMapper.selectById(id);
                yield row == null ? null : row.getSourceType();
            }
            case DEVICE -> {
                ResDevice row = deviceMapper.selectById(id);
                yield row == null ? null : row.getSourceType();
            }
            case CHANNEL -> {
                ResChannel row = channelMapper.selectById(id);
                yield row == null ? null : row.getSourceType();
            }
        };
    }
}
