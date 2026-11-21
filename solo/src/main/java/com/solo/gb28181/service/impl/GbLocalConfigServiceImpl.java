package com.solo.gb28181.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.common.ServiceException;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.GbDevice;
import com.solo.gb28181.entity.GbLocalConfig;
import com.solo.gb28181.entity.GbParentPlatform;
import com.solo.gb28181.entity.GbPlatformGroup;
import com.solo.gb28181.mapper.GbDeviceMapper;
import com.solo.gb28181.mapper.GbLocalConfigMapper;
import com.solo.gb28181.mapper.GbParentPlatformMapper;
import com.solo.gb28181.mapper.GbPlatformGroupMapper;
import com.solo.gb28181.request.GbLocalConfigSaveRequest;
import com.solo.gb28181.response.GbLocalConfigResponse;
import com.solo.gb28181.service.GbLocalConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 本平台国标配置实现。
 *
 * <p>配置优先级：gb_local_config（id=1）非空字段 &gt; application yaml。
 * 字段为 null 表示沿用 yaml，因此保存采用“删除单行 + 重新插入”的整行写法，
 * 避免 MyBatis-Plus updateById 默认忽略 null 导致清不回 yaml 兜底。</p>
 *
 * <p>生效时机：sipId / sipDomain / sipIp / sipPort 属于平台身份——SIP 监听点只在
 * 进程启动时创建，Via/Contact 也按运行值构造，因此运行期只落库不覆盖 properties，
 * 重启时由 {@link #applyOnStartup()} 生效；其余接入参数保存后即时写回 properties。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbLocalConfigServiceImpl implements GbLocalConfigService {

    private static final long ROW_ID = 1L;
    private static final String PASSWORD_MASK = "******";
    private static final Pattern DIGITS_20 = Pattern.compile("^\\d{20}$");
    private static final Pattern DIGITS_10 = Pattern.compile("^\\d{10}$");
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");

    private final GbLocalConfigMapper gbLocalConfigMapper;
    private final Gb28181Properties properties;
    private final GbPlatformGroupMapper gbPlatformGroupMapper;
    private final GbParentPlatformMapper gbParentPlatformMapper;
    private final GbDeviceMapper gbDeviceMapper;

    /**
     * 当前实际生效的平台身份。只在启动加载完成时刷新；运行期保存身份项不刷新，
     * 页面据此区分“已保存”和“已生效”。
     */
    private volatile RunningIdentity runningIdentity;

    @Override
    public void applyOnStartup() {
        GbLocalConfig row = gbLocalConfigMapper.selectById(ROW_ID);
        if (row == null) {
            log.info("[本平台配置] gb_local_config 无记录，沿用 application yaml：sipId={} sipIp={} sipPort={}",
                    properties.getId(), properties.getIp(), properties.getPort());
        } else {
            applyToProperties(row, true);
            log.info("[本平台配置] 已从数据库加载（库优先于 yaml）：sipId={} sipDomain={} sipIp={} sipPort={}",
                    properties.getId(), properties.getDomain(), properties.getIp(), properties.getPort());
        }
        runningIdentity = RunningIdentity.of(properties);
        if (trimToNull(properties.getId()) == null || trimToNull(properties.getDomain()) == null) {
            log.error("[本平台配置] 缺少平台身份：gb_local_config 无有效记录且 yaml 也未配置 gb28181.id/domain，"
                    + "协议栈会以无身份状态启动（下级注册/级联都会失败）。"
                    + "请执行 sql 脚本中的 gb_local_config 默认行，或在「国标平台 → 本平台配置」保存一次后重启");
        }
    }

    @Override
    public GbLocalConfigResponse getConfig() {
        return buildResponse(gbLocalConfigMapper.selectById(ROW_ID));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GbLocalConfigResponse save(GbLocalConfigSaveRequest request) {
        if (request == null) {
            throw new ServiceException("配置内容不能为空");
        }
        GbLocalConfig exist = gbLocalConfigMapper.selectById(ROW_ID);
        GbLocalConfig row = new GbLocalConfig();
        row.setId(ROW_ID);
        row.setSipId(trimToNull(request.getSipId()));
        row.setSipDomain(trimToNull(request.getSipDomain()));
        // sipIp 保留空串语义：空串=自动扫网卡，null=沿用 yaml
        row.setSipIp(request.getSipIp() == null ? null : request.getSipIp().trim());
        row.setSipPublicIp(trimToNull(request.getSipPublicIp()));
        row.setSipPort(request.getSipPort());
        row.setPassword(resolvePassword(request, exist));
        row.setRegisterExpires(request.getRegisterExpires());
        row.setKeepaliveTimeoutMultiplier(request.getKeepaliveTimeoutMultiplier());
        row.setDetectTimeoutMs(request.getDetectTimeoutMs());
        row.setUseSourceIpAsRemoteAddress(request.getUseSourceIpAsRemoteAddress());
        row.setCatalogPageSize(request.getCatalogPageSize());
        row.setCatalogAckTimeoutMs(request.getCatalogAckTimeoutMs());
        row.setCatalogPageIntervalMs(request.getCatalogPageIntervalMs());
        row.setSendRtpPortStart(request.getSendRtpPortStart());
        row.setSendRtpPortEnd(request.getSendRtpPortEnd());

        validate(row);

        if (exist == null) {
            gbLocalConfigMapper.insert(row);
        } else {
            // 整行重建：允许把某项清回 null（=沿用 yaml），updateById 做不到这一点
            gbLocalConfigMapper.deleteById(ROW_ID);
            gbLocalConfigMapper.insert(row);
        }
        // 身份项不写回 properties，等重启由 applyOnStartup 生效；其余字段立即热更
        applyToProperties(row, false);
        log.info("[本平台配置] 保存成功 sipId={} sipDomain={} sipIp={} sipPort={}（身份项重启生效）",
                row.getSipId(), row.getSipDomain(), row.getSipIp(), row.getSipPort());
        return buildResponse(row);
    }

    /**
     * password 语义：resetPassword=true 清空（开放模式，存空串区别于 null）；
     * 非空字符串=修改；null/空串=保持原值（原值为 null 时继续沿用 yaml）。
     */
    private String resolvePassword(GbLocalConfigSaveRequest request, GbLocalConfig exist) {
        if (Boolean.TRUE.equals(request.getResetPassword())) {
            return "";
        }
        String password = trimToNull(request.getPassword());
        if (password != null) {
            return password;
        }
        return exist == null ? null : exist.getPassword();
    }

    private void validate(GbLocalConfig row) {
        RunningIdentity running = runningIdentity();
        String sipId = firstNonBlank(row.getSipId(), running.sipId());
        String sipDomain = firstNonBlank(row.getSipDomain(), running.sipDomain());
        int sipPort = row.getSipPort() != null ? row.getSipPort() : running.sipPort();

        if (sipId == null) {
            throw new ServiceException("本平台 SIP 国标 ID 不能为空");
        }
        if (!DIGITS_20.matcher(sipId).matches()) {
            throw new ServiceException("本平台 SIP 国标 ID 必须为 20 位数字");
        }
        if (sipDomain == null) {
            throw new ServiceException("本平台 SIP 域不能为空");
        }
        if (!DIGITS_10.matcher(sipDomain).matches()) {
            throw new ServiceException("本平台 SIP 域必须为 10 位数字");
        }
        if (!sipId.startsWith(sipDomain)) {
            throw new ServiceException("本平台 SIP 域必须是国标 ID 的前 10 位");
        }
        if (sipPort < 1 || sipPort > 65535) {
            throw new ServiceException("SIP 监听端口必须在 1-65535 之间");
        }
        checkIp("SIP 监听 IP", row.getSipIp());
        checkIp("SIP 公网 IP", row.getSipPublicIp());

        if (row.getPassword() != null && row.getPassword().length() > 100) {
            throw new ServiceException("鉴权密码长度不能超过 100 个字符");
        }
        checkRange("设备注册有效期", row.getRegisterExpires(), 60, 86400);
        checkRange("心跳超时倍数", row.getKeepaliveTimeoutMultiplier(), 2, 10);
        checkRange("存活探测超时", row.getDetectTimeoutMs(), 1000L, 60000L);
        checkRange("Catalog 单包条数", row.getCatalogPageSize(), 1, 8);
        checkRange("Catalog 分片 ACK 超时", row.getCatalogAckTimeoutMs(), 500L, 60000L);
        checkRange("Catalog 兜底间隔", row.getCatalogPageIntervalMs(), 0L, 10000L);
        // 推流端口段只校验正数与先后顺序：现网 yaml 有 60000-70000 这类越界历史值，
        // 用 65535 硬顶会让用户改其它字段时保存不了
        checkRange("上级点播端口段起始", row.getSendRtpPortStart(), 1, Integer.MAX_VALUE);
        checkRange("上级点播端口段结束", row.getSendRtpPortEnd(), 1, Integer.MAX_VALUE);

        Gb28181Properties.Cascade cascade = properties.getCascade();
        int rtpStart = row.getSendRtpPortStart() != null ? row.getSendRtpPortStart()
                : (cascade == null ? 0 : cascade.getSendRtpPortStart());
        int rtpEnd = row.getSendRtpPortEnd() != null ? row.getSendRtpPortEnd()
                : (cascade == null ? 0 : cascade.getSendRtpPortEnd());
        if (rtpStart > 0 && rtpEnd > 0 && rtpStart >= rtpEnd) {
            throw new ServiceException("上级点播端口段起始必须小于结束端口");
        }

        // SIP 域变更会让已建分组的 20 位编码（前 10 位=domain）与平台身份不一致，直接拦截
        if (!Objects.equals(sipDomain, running.sipDomain())) {
            Long badGroups = gbPlatformGroupMapper.selectCount(new LambdaQueryWrapper<GbPlatformGroup>()
                    .notLikeRight(GbPlatformGroup::getCode, sipDomain));
            if (badGroups != null && badGroups > 0) {
                throw new ServiceException("存在 " + badGroups
                        + " 个推送分组编码不以新 SIP 域 " + sipDomain + " 开头，修改域会导致目录编码不一致，请先清理这些分组");
            }
        }
        // SIP ID 是向上级注册和下级设备寻址的身份，变更后必须重新注册，这里只提示影响面
        if (!Objects.equals(sipId, running.sipId())) {
            log.warn("[本平台配置] SIP ID 变更（重启生效）：{} -> {}；当前上级平台 {} 条、接入设备 {} 条，重启后需重新注册",
                    running.sipId(), sipId,
                    gbParentPlatformMapper.selectCount(new LambdaQueryWrapper<GbParentPlatform>()),
                    gbDeviceMapper.selectCount(new LambdaQueryWrapper<GbDevice>()));
        }
    }

    private void checkIp(String label, String ip) {
        if (ip != null && !IPV4.matcher(ip).matches()) {
            throw new ServiceException(label + "格式不正确：" + ip);
        }
    }

    private void checkRange(String label, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            throw new ServiceException(label + "必须在 " + min + "-" + max + " 之间");
        }
    }

    private void checkRange(String label, Long value, long min, long max) {
        if (value != null && (value < min || value > max)) {
            throw new ServiceException(label + "必须在 " + min + "-" + max + " 之间");
        }
    }

    /**
     * 把库记录写回 properties。includeIdentity=false 时跳过身份项（运行期保存走这条路径）。
     * 字段为 null 一律不改，保持 yaml 兜底。
     */
    private void applyToProperties(GbLocalConfig row, boolean includeIdentity) {
        if (includeIdentity) {
            if (row.getSipId() != null) {
                properties.setId(row.getSipId());
            }
            if (row.getSipDomain() != null) {
                properties.setDomain(row.getSipDomain());
            }
            if (row.getSipIp() != null) {
                properties.setIp(row.getSipIp());
            }
            if (row.getSipPort() != null) {
                properties.setPort(row.getSipPort());
            }
        }
        if (row.getSipPublicIp() != null) {
            properties.setPublicIp(row.getSipPublicIp());
        }
        if (row.getPassword() != null) {
            properties.setPassword(row.getPassword());
        }
        if (row.getRegisterExpires() != null) {
            properties.setRegisterExpires(row.getRegisterExpires());
        }
        if (row.getKeepaliveTimeoutMultiplier() != null) {
            properties.setKeepaliveTimeoutMultiplier(row.getKeepaliveTimeoutMultiplier());
        }
        if (row.getDetectTimeoutMs() != null) {
            properties.setDetectTimeoutMs(row.getDetectTimeoutMs());
        }
        if (row.getUseSourceIpAsRemoteAddress() != null) {
            properties.setUseSourceIpAsRemoteAddress(row.getUseSourceIpAsRemoteAddress());
        }
        Gb28181Properties.Cascade cascade = properties.getCascade();
        if (cascade != null) {
            if (row.getCatalogPageSize() != null) {
                cascade.setCatalogPageSize(row.getCatalogPageSize());
            }
            if (row.getCatalogAckTimeoutMs() != null) {
                cascade.setCatalogAckTimeoutMs(row.getCatalogAckTimeoutMs());
            }
            if (row.getCatalogPageIntervalMs() != null) {
                cascade.setCatalogPageIntervalMs(row.getCatalogPageIntervalMs());
            }
            if (row.getSendRtpPortStart() != null) {
                cascade.setSendRtpPortStart(row.getSendRtpPortStart());
            }
            if (row.getSendRtpPortEnd() != null) {
                cascade.setSendRtpPortEnd(row.getSendRtpPortEnd());
            }
        }
    }

    private GbLocalConfigResponse buildResponse(GbLocalConfig row) {
        RunningIdentity running = runningIdentity();
        GbLocalConfigResponse res = new GbLocalConfigResponse();
        res.setSource(row == null ? "yaml" : "db");

        // 身份项展示“库里保存的值”（可能是待重启的），未保存过则展示当前运行值
        res.setSipId(firstNonBlank(row == null ? null : row.getSipId(), running.sipId()));
        res.setSipDomain(firstNonBlank(row == null ? null : row.getSipDomain(), running.sipDomain()));
        res.setSipIp(row == null || row.getSipIp() == null ? running.sipIp() : row.getSipIp());
        res.setSipPort(row == null || row.getSipPort() == null ? running.sipPort() : row.getSipPort());

        // 其余字段已热更到 properties，直接取运行值即为当前生效值
        res.setSipPublicIp(properties.getPublicIp());
        String password = row != null && row.getPassword() != null ? row.getPassword() : properties.getPassword();
        boolean passwordConfigured = password != null && !password.isEmpty();
        res.setPassword(passwordConfigured ? PASSWORD_MASK : "");
        res.setPasswordConfigured(passwordConfigured);
        res.setRegisterExpires(properties.getRegisterExpires());
        res.setKeepaliveTimeoutMultiplier(properties.getKeepaliveTimeoutMultiplier());
        res.setDetectTimeoutMs(properties.getDetectTimeoutMs());
        res.setUseSourceIpAsRemoteAddress(properties.isUseSourceIpAsRemoteAddress());
        Gb28181Properties.Cascade cascade = properties.getCascade();
        if (cascade != null) {
            res.setCatalogPageSize(cascade.getCatalogPageSize());
            res.setCatalogAckTimeoutMs(cascade.getCatalogAckTimeoutMs());
            res.setCatalogPageIntervalMs(cascade.getCatalogPageIntervalMs());
            res.setSendRtpPortStart(cascade.getSendRtpPortStart());
            res.setSendRtpPortEnd(cascade.getSendRtpPortEnd());
        }

        res.setRunningSipId(running.sipId());
        res.setRunningSipDomain(running.sipDomain());
        res.setRunningSipIp(running.sipIp());
        res.setRunningSipPort(running.sipPort());
        res.setRestartRequired(identityChanged(row, running));
        return res;
    }

    private boolean identityChanged(GbLocalConfig row, RunningIdentity running) {
        if (row == null) {
            return false;
        }
        return differs(row.getSipId(), running.sipId())
                || differs(row.getSipDomain(), running.sipDomain())
                || differs(row.getSipIp(), running.sipIp())
                || (row.getSipPort() != null && row.getSipPort() != running.sipPort());
    }

    private boolean differs(String saved, String running) {
        return saved != null && !Objects.equals(trimToEmpty(saved), trimToEmpty(running));
    }

    private RunningIdentity runningIdentity() {
        RunningIdentity current = runningIdentity;
        if (current == null) {
            synchronized (this) {
                if (runningIdentity == null) {
                    runningIdentity = RunningIdentity.of(properties);
                }
                current = runningIdentity;
            }
        }
        return current;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 当前运行中的平台身份快照（启动加载后的 properties 值）。
     */
    private record RunningIdentity(String sipId, String sipDomain, String sipIp, int sipPort) {

        static RunningIdentity of(Gb28181Properties properties) {
            return new RunningIdentity(trimToEmpty(properties.getId()),
                    trimToEmpty(properties.getDomain()),
                    trimToEmpty(properties.getIp()),
                    properties.getPort());
        }
    }
}
