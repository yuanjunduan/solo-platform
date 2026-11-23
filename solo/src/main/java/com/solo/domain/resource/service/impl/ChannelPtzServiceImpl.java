package com.solo.domain.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.api.resource.dto.ChannelPresetSaveRequest;
import com.solo.api.resource.dto.ChannelPtzCommandRequest;
import com.solo.api.resource.dto.ChannelPtzDragZoomRequest;
import com.solo.common.ServiceException;
import com.solo.domain.resource.model.ResChannelPreset;
import com.solo.domain.resource.service.ChannelPtzService;
import com.solo.gb28181.support.ChannelEndpointResolver;
import com.solo.gb28181.support.ChannelEndpointResolver.ChannelEndpoint;
import com.solo.gb28181.transmit.cmd.SipCommander;
import com.solo.gb28181.utils.SipUtils;
import com.solo.infra.mapper.ResChannelPresetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 云台与预置位的统一入口：解析端点 → 能力位门控 → 复用 {@link SipCommander} 发指令。
 *
 * <p>预置位名称只存本地（{@code res_channel_preset}），编号与槽位内容始终由设备维护；
 * 删除先通知设备、成功后再删本地行，避免本地记录与设备状态分叉。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelPtzServiceImpl implements ChannelPtzService {

    private static final String LOG_TAG = "通道云台";
    private static final String CAP_PTZ = "PTZ";
    private static final int NAME_MAX = 64;

    private final ChannelEndpointResolver endpointResolver;
    private final SipCommander sipCommander;
    private final ResChannelPresetMapper presetMapper;

    @Override
    public void move(Long channelRowId, ChannelPtzCommandRequest req) {
        ChannelEndpoint endpoint = requirePtzCapability(channelRowId);
        int leftRight = 0; // 0 停 1 左 2 右
        int upDown = 0;    // 0 停 1 上 2 下
        int inOut = 0;     // 0 停 1 缩小 2 放大
        int moveSpeed = req.getSpeed() == null ? 100 : req.getSpeed();
        int zoomSpeed = req.getZoomSpeed() == null ? 5 : req.getZoomSpeed();

        switch (req.getCommand().trim().toLowerCase()) {
            case "left" -> leftRight = 1;
            case "right" -> leftRight = 2;
            case "up" -> upDown = 1;
            case "down" -> upDown = 2;
            case "upleft" -> { leftRight = 1; upDown = 1; }
            case "upright" -> { leftRight = 2; upDown = 1; }
            case "downleft" -> { leftRight = 1; upDown = 2; }
            case "downright" -> { leftRight = 2; upDown = 2; }
            case "zoomin" -> inOut = 2;
            case "zoomout" -> inOut = 1;
            case "stop" -> { moveSpeed = 0; zoomSpeed = 0; }
            default -> throw new ServiceException(400, "不支持的 PTZ 指令: " + req.getCommand());
        }
        send(endpoint, SipUtils.buildPtzCmd(leftRight, upDown, inOut, moveSpeed, zoomSpeed), req.getCommand());
    }

    @Override
    public void focus(Long channelRowId, String direction, int speed) {
        ChannelEndpoint endpoint = requirePtzCapability(channelRowId);
        int dir = switch (direction == null ? "" : direction.trim().toLowerCase()) {
            case "near" -> 1;
            case "far" -> 2;
            case "stop" -> 0;
            default -> throw new ServiceException(400, "不支持的聚焦指令: " + direction);
        };
        send(endpoint, SipUtils.buildPtzFocusCmd(dir, speed), "focus/" + direction);
    }

    @Override
    public void iris(Long channelRowId, String direction, int speed) {
        ChannelEndpoint endpoint = requirePtzCapability(channelRowId);
        int dir = switch (direction == null ? "" : direction.trim().toLowerCase()) {
            case "open" -> 1;
            case "close" -> 2;
            case "stop" -> 0;
            default -> throw new ServiceException(400, "不支持的光圈指令: " + direction);
        };
        send(endpoint, SipUtils.buildPtzIrisCmd(dir, speed), "iris/" + direction);
    }

    @Override
    public void wiper(Long channelRowId, String action) {
        ChannelEndpoint endpoint = requirePtzCapability(channelRowId);
        boolean on = switch (action == null ? "" : action.trim().toLowerCase()) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new ServiceException(400, "不支持的雨刷指令: " + action);
        };
        send(endpoint, SipUtils.buildPtzWiperCmd(on), "wiper/" + action);
    }

    @Override
    public void dragZoom(Long channelRowId, ChannelPtzDragZoomRequest req) {
        ChannelEndpoint endpoint = requirePtzCapability(channelRowId);
        boolean zoomIn = switch (req.getAction() == null ? "" : req.getAction().trim().toLowerCase()) {
            case "in" -> true;
            case "out" -> false;
            default -> throw new ServiceException(400, "不支持的拉框指令: " + req.getAction());
        };
        sipCommander.sendDragZoomCmd(endpoint.device(), endpoint.channelId(), zoomIn,
                req.getLength(), req.getWidth(), req.getMidPointX(), req.getMidPointY(),
                req.getLengthX(), req.getLengthY());
    }

    @Override
    public void homePosition(Long channelRowId, boolean enabled, Integer resetTime, Integer presetIndex) {
        ChannelEndpoint endpoint = requirePtzCapability(channelRowId);
        sipCommander.sendHomePositionCmd(endpoint.device(), endpoint.channelId(), enabled, resetTime, presetIndex);
    }

    /* ==================== 预置位 ==================== */

    @Override
    public List<ResChannelPreset> listPresets(Long channelRowId) {
        // 列表只读本地名称，不校验设备在线：设备离线时也要能看到已保存的槽位
        if (channelRowId == null) {
            throw new ServiceException(400, "通道 id 不能为空");
        }
        return presetMapper.selectList(new LambdaQueryWrapper<ResChannelPreset>()
                .eq(ResChannelPreset::getChannelRowId, channelRowId)
                .orderByAsc(ResChannelPreset::getPresetIndex));
    }

    @Override
    public ResChannelPreset savePreset(Long channelRowId, ChannelPresetSaveRequest req) {
        ChannelEndpoint endpoint = requirePtzCapability(channelRowId);
        int index = req.getPresetIndex();
        String name = normalizeName(req.getName(), index);

        ResChannelPreset existing = findOne(channelRowId, index);
        // 先让设备把当前位置存进该槽位，成功后再写本地名称——反过来会留下"有名字没槽位"的行
        send(endpoint, SipUtils.buildPtzSetPreset(index), "set-preset/" + index);

        if (existing != null) {
            existing.setName(name);
            presetMapper.updateById(existing);
            return existing;
        }
        ResChannelPreset preset = new ResChannelPreset();
        preset.setChannelRowId(channelRowId);
        preset.setPresetIndex(index);
        preset.setName(name);
        presetMapper.insert(preset);
        return preset;
    }

    @Override
    public void deletePreset(Long channelRowId, Long presetId) {
        ResChannelPreset preset = requirePreset(channelRowId, presetId);
        ChannelEndpoint endpoint = requirePtzCapability(channelRowId);
        send(endpoint, SipUtils.buildPtzDeletePreset(preset.getPresetIndex()), "delete-preset/" + preset.getPresetIndex());
        presetMapper.deleteById(presetId);
    }

    @Override
    public void callPreset(Long channelRowId, Long presetId) {
        ResChannelPreset preset = requirePreset(channelRowId, presetId);
        ChannelEndpoint endpoint = requirePtzCapability(channelRowId);
        send(endpoint, SipUtils.buildPtzCallPreset(preset.getPresetIndex()), "call-preset/" + preset.getPresetIndex());
    }

    private ChannelEndpoint requirePtzCapability(Long channelRowId) {
        ChannelEndpoint endpoint = endpointResolver.resolve(channelRowId);
        if (!endpoint.supports(CAP_PTZ)) {
            throw new ServiceException(400, "该通道未声明云台能力（capability 不含 PTZ），无法控制");
        }
        return endpoint;
    }

    private ResChannelPreset requirePreset(Long channelRowId, Long presetId) {
        if (presetId == null) {
            throw new ServiceException(400, "预置位 id 不能为空");
        }
        ResChannelPreset preset = presetMapper.selectById(presetId);
        if (preset == null || !channelRowId.equals(preset.getChannelRowId())) {
            throw new ServiceException(404, "预置位不存在: " + presetId);
        }
        return preset;
    }

    private ResChannelPreset findOne(Long channelRowId, int presetIndex) {
        return presetMapper.selectOne(new LambdaQueryWrapper<ResChannelPreset>()
                .eq(ResChannelPreset::getChannelRowId, channelRowId)
                .eq(ResChannelPreset::getPresetIndex, presetIndex));
    }

    private String normalizeName(String raw, int index) {
        String name = raw == null || raw.isBlank() ? "预置位" + index : raw.trim();
        if (name.length() > NAME_MAX) {
            throw new ServiceException(400, "预置位名称不能超过 " + NAME_MAX + " 个字符");
        }
        return name;
    }

    private void send(ChannelEndpoint endpoint, String ptzCmd, String what) {
        log.info("[{}] 下发 {} 通道={} deviceId={} channelId={} cmd={}",
                LOG_TAG, what, endpoint.row().getId(), endpoint.deviceId(), endpoint.channelId(), ptzCmd);
        sipCommander.sendPtzCmd(endpoint.device(), endpoint.channelId(), ptzCmd);
    }
}
