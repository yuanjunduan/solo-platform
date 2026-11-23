package com.solo.gb28181.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solo.domain.resource.model.ResChannel;
import com.solo.domain.resource.model.SourceType;
import com.solo.domain.share.service.ShareCatalogService;
import com.solo.gb28181.config.Gb28181Properties;
import com.solo.gb28181.entity.CascadeSendSession;
import com.solo.gb28181.entity.GbInviteSession;
import com.solo.gb28181.entity.GbParentPlatform;
import com.solo.gb28181.mapper.GbParentPlatformMapper;
import com.solo.gb28181.request.InboundPlayRequest;
import com.solo.gb28181.request.PlayStartRequest;
import com.solo.gb28181.request.PlayStopRequest;
import com.solo.gb28181.response.PlayResultResponse;
import com.solo.gb28181.service.CascadeSendSessionService;
import com.solo.gb28181.service.GbInviteSessionCleanupService;
import com.solo.gb28181.service.GbInviteSessionService;
import com.solo.gb28181.service.GbParentPlatformService;
import com.solo.gb28181.service.GbPlayService;
import com.solo.gb28181.sip.SipSender;
import com.solo.gb28181.support.CascadeSdpAddress;
import com.solo.gb28181.support.GbInviteLiveness;
import com.solo.gb28181.support.MediaHopGuard;
import com.solo.gb28181.transmit.cmd.PlatformSipHeaderProvider;
import com.solo.gb28181.transmit.cmd.SdpBuilder;
import com.solo.gb28181.utils.SipUtils;
import com.solo.media.client.ZlmRestClient;
import com.solo.media.entity.MediaServer;
import com.solo.media.mapper.GbMediaServerMapper;
import tools.jackson.databind.JsonNode;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sip.message.Request;
import javax.sip.message.Response;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 级联被上级点播编排（本平台作为下级，上级发 INVITE 点播本平台共享通道）。
 *
 * <p>两条腿模型（见 ADR-019）：</p>
 * <ol>
 *   <li><strong>取流腿</strong>：复用 {@link GbPlayService#start} 向真实下级设备取流，流进 ZLM（app=rtp/srcStream）。
 *       这一步阻塞等流就绪，由 {@code InviteRequestProcessor} 丢到 gb28181 业务线程池执行，不阻塞 SIP 线程（ADR-007）。</li>
 *   <li><strong>推流腿</strong>：取流就绪后用 ZLM {@code startSendRtp} 把 srcStream 推到上级 SDP 给的 ip/port/ssrc。</li>
 * </ol>
 *
 * <p>SSRC 策略：直接用上级 SDP 的 {@code y=} 值（顺从上级指定，见 ADR-019 决策 3），不自行分配。</p>
 * <p>推流触发时机：UDP / TCP 被动（本平台主动去连上级）在收到上级 ACK 后 {@code startSendRtp}；
 * TCP 主动（上级来连本平台）在回 200 OK 后立即 {@code startSendRtpPassive} 开监听。</p>
 *
 * <h3>依赖说明</h3>
 * <p>本类作为级联点播唯一编排层，<b>必须</b>调下列业务方法，因此直接依赖这些 service：</p>
 * <ul>
 *   <li>{@link ShareCatalogService#resolveForPlay} — 上级码反查可见通道 + 授权校验（M4 出站三表）</li>
 *   <li>{@link CascadeSendSessionService} — 推流会话生命周期（CRUD + 端口分配，全 Redis 复合键）</li>
 *   <li>{@link GbInviteSessionService#getByStreamId} — 取流侧会话查询（Redis 实现，无 mapper）</li>
 *   <li>{@link GbPlayService#start} — 复用设备点播全链路</li>
 *   <li>{@link GbInviteSessionCleanupService#failAndBye} — 取流回收（关 RTP 端口 + 释放 SSRC + 删会话）</li>
 *   <li>{@link PlatformSipHeaderProvider} / {@link SipSender} / {@link ZlmRestClient} — SIP 出站 + ZLM 推流接口</li>
 * </ul>
 * <p>单表查（节点、平台）走 mapper；保留的 service 依赖都是真正业务方法或跨表逻辑。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CascadeSendService {

    private static final String LOG_TAG = "级联点播";
    private static final String APP = "rtp";
    /** 预分配端口被占用时最多尝试次数（含首次）。见 D5：可用性优先，允许实际端口与 SDP 不一致 */
    private static final int MAX_SEND_PORT_ATTEMPTS = 3;
    /** lastRtpTime 最小回写间隔（秒）：巡检 10s 一轮，避免把整个会话 JSON 频繁重写 Redis */
    private static final int LAST_RTP_WRITE_INTERVAL_SEC = 5;

    private final Gb28181Properties properties;
    private final ShareCatalogService shareCatalogService;
    private final CascadeSendSessionService sendSessionService;
    private final GbInviteSessionService inviteSessionService;
    private final GbParentPlatformService gbParentPlatformService;
    private final GbPlayService playService;
    private final GbInviteSessionCleanupService support;
    private final ZlmRestClient zlmRestClient;
    private final GbMediaServerMapper mediaServerMapper;
    private final PlatformSipHeaderProvider headerProvider;
    private final SipSender sipSender;
    private final GbParentPlatformMapper gbParentPlatformMapper;
    private final MediaHopGuard mediaHopGuard;

    /**
     * 处理上级 INVITE：校验共享 → 取流 → 建会话 → 回 200 OK（sendonly SDP）。
     * <p>调用方（InviteRequestProcessor）已回过 100 Trying 且在业务线程池内执行。</p>
     *
     * <p><b>每跳独立互斥（6.6 / T5.3）</b>：这里持有的是"出站推流腿"，按「上级平台 + 上级看到的
     * 通道编码」各持一把，与取流腿（{@code GbPlayServiceImpl} 按 streamId）互不影响。
     * 上级 A 反复重试或断线，只冷却 A 自己这个 key，不会把 B 的点播一起锁死——
     * 这正是"不能共用一个通道级锁"的原因。</p>
     */
    public void onUpstreamInvite(SIPRequest request, GbParentPlatform platform,
                                 String channelId, String upstreamSdp) {
        javax.sip.header.CallIdHeader callIdHeader =
                (javax.sip.header.CallIdHeader) request.getHeader(javax.sip.header.CallIdHeader.NAME);
        String callId = callIdHeader.getCallId();
        String upstreamFromTag = request.getFromHeader().getTag();

        // 1) 授权校验 + 反查（M4 出站三表）：撤销后、以及 DELETE 推送成功前，这里必须直接拒绝（C12 / C27）
        ShareCatalogService.ExposedChannel resolved = findSharedChannel(platform.getId(), channelId);
        if (resolved == null) {
            log.warn("[{}] 上级 {} 点播未共享/不存在的通道 上级通道编码:{} 原因:回 404",
                    LOG_TAG, platform.getServerGbId(), channelId);
            sendError(request, Response.NOT_FOUND, "channel not shared");
            return;
        }
        // 会话里的 deviceId 只是排查线索：本地设备用设备国标码，入站通道用来源平台码（由 GbPlayService 解析）
        boolean inbound = SourceType.INBOUND.equals(resolved.channel().getSourceType());
        String localDeviceCode = resolved.device() == null ? null : resolved.device().getCode();
        if (!inbound && (localDeviceCode == null || localDeviceCode.isBlank())) {
            log.warn("[{}] 通道缺少可点播的设备编码 上级平台:{} 上级通道编码:{} 本平台通道id:{}",
                    LOG_TAG, platform.getServerGbId(), channelId, resolved.channel().getId());
            sendError(request, Response.NOT_FOUND, "device not ready");
            return;
        }

        // 2) 解析上级期望收流的目的地址 / SSRC / 传输模式
        // 严格走协议层 SDP c=/m=/y=：下级无 authority 修改上级声明的收流地址（ADR-034）
        // —— 网络不通时让上级修 SDP，不在业务代码层面做静默覆盖
        String dstIp = SdpBuilder.extractConnectionIp(upstreamSdp);
        int dstPort = SdpBuilder.extractMediaPort(upstreamSdp);
        String ssrc = SdpBuilder.extractSsrc(upstreamSdp);
        String transport = SdpBuilder.extractTransport(upstreamSdp);
        if (dstIp == null || dstPort <= 0 || ssrc == null || ssrc.isBlank()) {
            log.warn("[{}] 上级 SDP 缺 c=/m=/y= 关键字段 目的IP:{} 目的端口:{} SSRC:{} 原因:回 400",
                    LOG_TAG, dstIp, dstPort, ssrc);
            sendError(request, Response.BAD_REQUEST, "invalid sdp");
            return;
        }
        log.info("[{}] 收到上级点播 上级平台:{} 上级通道编码:{} 本平台通道编码:{} 收流目标:{}:{} SSRC:{} 传输方式:{}",
                LOG_TAG, platform.getServerGbId(), channelId, resolved.channel().getCode(),
                dstIp, dstPort, ssrc, transport);

        // 3) 每跳互斥 + 冷却：同一上级同一条通道同时只允许一次取流/推流编排
        String hopKey = MediaHopGuard.outboundKey(platform.getId(), channelId);
        long coolingMs = mediaHopGuard.cooldownRemainingMs(hopKey);
        if (coolingMs > 0) {
            long seconds = Math.max(1L, (coolingMs + 999L) / 1000L);
            log.warn("[{}] 出站推流冷却中 上级平台:{} 上级通道编码:{} 剩余:{}s",
                    LOG_TAG, platform.getServerGbId(), channelId, seconds);
            sendError(request, Response.BUSY_HERE, "retry after " + seconds + "s");
            return;
        }
        if (!mediaHopGuard.tryAcquire(hopKey)) {
            log.warn("[{}] 出站推流已有请求在飞 上级平台:{} 上级通道编码:{}",
                    LOG_TAG, platform.getServerGbId(), channelId);
            sendError(request, Response.BUSY_HERE, "invite busy");
            return;
        }
        try {
            deliverUpstream(request, platform, channelId, resolved,
                    new UpstreamMedia(dstIp, dstPort, ssrc, transport), callId, upstreamFromTag);
        } catch (RuntimeException e) {
            cooldownOutbound(platform, channelId);
            throw e;
        } finally {
            mediaHopGuard.release(hopKey);
        }
    }

    /** 上级 INVITE 里已校验过的媒体参数（c= / m= / y= / transport） */
    private record UpstreamMedia(String dstIp, int dstPort, String ssrc, String transport) {
    }

    /**
     * 出站推流主体（持锁后执行）：取流 → 建推流会话 → 回 200 OK。
     */
    private void deliverUpstream(SIPRequest request, GbParentPlatform platform, String channelId,
                                 ShareCatalogService.ExposedChannel resolved, UpstreamMedia media,
                                 String callId, String upstreamFromTag) {
        ResChannel local = resolved.channel();
        boolean inbound = SourceType.INBOUND.equals(local.getSourceType());
        String localChannelCode = local.getCode();
        String localDeviceCode = resolved.device() == null ? null : resolved.device().getCode();
        String dstIp = media.dstIp();
        int dstPort = media.dstPort();
        String ssrc = media.ssrc();
        String transport = media.transport();

        // 取流（阻塞等流就绪）：本地通道复用设备点播，入站通道向来源平台点播
        //    注意：上级来的 channelId 是暴露码，取流必须用原始编码，否则对端不认识暴露码
        PlayResultResponse play;
        try {
            play = inbound ? playInbound(local) : playLocal(localDeviceCode, localChannelCode);
        } catch (Exception e) {
            log.warn("[{}] 取流失败 上级平台:{} 上级通道编码:{} 本平台通道编码:{} 错误:{}",
                    LOG_TAG, platform.getServerGbId(), channelId, localChannelCode, e.getMessage(), e);
            cooldownOutbound(platform, channelId);
            sendError(request, Response.BUSY_HERE, "fetch stream failed");
            return;
        }

        MediaServer server = mediaServer(play.getMediaServerId());
        if (server == null) {
            log.error("[{}] 取流就绪但节点丢失 节点ID:{}", LOG_TAG, play.getMediaServerId());
            cooldownOutbound(platform, channelId);
            sendError(request, Response.SERVER_INTERNAL_ERROR, "media server lost");
            return;
        }

        // 4) 解析回给上级的媒体地址（C21a）：必须是本平台对外可达地址，绝不透传上级 SDP 里的 c=
        String sdpIp = CascadeSdpAddress.resolve(
                properties.getCascade().getSdpPublicIp(), server.getStreamIp(), server.getHostIp());
        if (sdpIp == null) {
            log.error("[{}] 媒体节点无可用对外地址，拒绝点播 节点:{} streamIp:{} hostIp:{} sdpPublicIp:{}，"
                            + "请配置 gb28181.cascade.sdp-public-ip",
                    LOG_TAG, server.getServerId(), server.getStreamIp(), server.getHostIp(),
                    properties.getCascade().getSdpPublicIp());
            stopFetchedStreamIfUnused(server, play.getStreamId());
            cooldownOutbound(platform, channelId);
            sendError(request, Response.SERVER_INTERNAL_ERROR, "media address not configured");
            return;
        }
        if (CascadeSdpAddress.isPrivate(sdpIp)) {
            log.warn("[{}] 回给上级的媒体地址是私网 {}，上级不在同一内网将不可达；可配置 gb28181.cascade.sdp-public-ip",
                    LOG_TAG, sdpIp);
        }

        // 5) 建推流会话
        String localToTag = SipUtils.newToTag();
        CascadeSendSession session = new CascadeSendSession();
        session.setSendStreamId("send_" + callId);
        session.setServerId(server.getServerId());
        session.setPlatformDbId(platform.getId());
        session.setPlatformServerGbId(platform.getServerGbId());
        session.setLocalGbId(properties.getId());
        session.setChannelId(channelId);
        session.setLocalChannelId(localChannelCode);
        session.setDeviceId(localDeviceCode);
        session.setSrcApp(APP);
        session.setSrcStream(play.getStreamId());
        session.setDstIp(dstIp);
        session.setDstPort(dstPort);
        session.setSsrc(ssrc);
        session.setTransport(transport);
        session.setCallId(callId);
        session.setFromTag(upstreamFromTag);
        session.setToTag(localToTag);
        session.setStatus(CascadeSendSession.STATUS_WAIT_ACK);
        session.setSdpRewritten(true);
        session.setCreateTime(LocalDateTime.now());

        // 6) 决定回给上级的 m= 端口（C21a：UDP / TCP 被动时也不能为 0——RFC 4566 中 0 表示禁用该媒体流）
        //    TCP 主动（上级来连本平台）：端口必须真实，先开监听
        //    UDP / TCP 被动（本平台直推 / 主动去连上级）：先预分配，ACK 时复用同一端口
        int sendPort;
        boolean upstreamActive = session.isUpstreamActive();
        if (upstreamActive) {
            try {
                sendPort = zlmRestClient.startSendRtpPassive(server, APP, play.getStreamId(), ssrc);
                session.setStatus(CascadeSendSession.STATUS_SENDING);
                session.setSendTime(LocalDateTime.now());
            } catch (Exception e) {
                log.error("[{}] startSendRtpPassive 失败 流ID:{} 错误:{}",
                        LOG_TAG, play.getStreamId(), e.getMessage(), e);
                cooldownOutbound(platform, channelId);
                sendError(request, Response.SERVER_INTERNAL_ERROR, "passive listen failed");
                return;
            }
        } else {
            sendPort = sendPortInRange();
        }
        session.setLocalSendPort(sendPort);

        sendSessionService.save(session);

        // 7) 回 200 OK（sendonly SDP，地址与端口均由本平台构造）
        String answer = SdpBuilder.buildSendAnswer(properties.getId(), sdpIp, sendPort, ssrc, transport);
        try {
            SIPResponse ok = headerProvider.buildInviteOk(platform, request, answer, localToTag);
            sipSender.send(request.getLocalAddress().getHostAddress(), ok);
            log.info("[{}] 回 200 OK 上级平台:{} 本平台通道编码:{} 源流ID:{} 媒体地址:{}:{} 传输方式:{} 待ACK:{}",
                    LOG_TAG, platform.getServerGbId(), localChannelCode, play.getStreamId(),
                    sdpIp, sendPort, transport, !upstreamActive);
        } catch (Exception e) {
            log.error("[{}] 回 200 OK 失败 Call-ID:{} 错误:{}", LOG_TAG, callId, e.getMessage(), e);
            cooldownOutbound(platform, channelId);
            cleanup(session, true);
        }
    }

    /**
     * 上级 ACK 到达：对 UDP / TCP 被动会话真正 startSendRtp（TCP 主动已在回 200 时开监听，跳过）。
     */
    public void onUpstreamAck(String callId) {
        CascadeSendSession session = sendSessionService.getByCallId(callId);
        if (session == null) {
            return;
        }
        if (CascadeSendSession.STATUS_SENDING.equals(session.getStatus())) {
            // TCP 主动已在回 200 时启动监听，或重复 ACK，幂等跳过
            return;
        }
        MediaServer server = mediaServer(session.getServerId());
        if (server == null) {
            log.error("[{}] ACK 时节点丢失 节点ID:{}", LOG_TAG, session.getServerId());
            cleanup(session, true);
            return;
        }
        try {
            // 站在上级视角：TCP-passive 表示上级等本平台去连 → 本平台主动去连（is_udp=0）；UDP 直推
            boolean udp = !session.isTcp();
            int localPort = startSendRtpWithPortFallback(session, server, udp);
            session.setLocalSendPort(localPort);
            session.setStatus(CascadeSendSession.STATUS_SENDING);
            session.setSendTime(LocalDateTime.now());
            sendSessionService.update(session);
            log.info("[{}] startSendRtp 已推流 上级平台:{} 本平台通道编码:{} 目的:{}:{} UDP:{} 本地端口:{}",
                    LOG_TAG, session.getPlatformServerGbId(), session.getLocalChannelId(),
                    session.getDstIp(), session.getDstPort(), udp, localPort);
        } catch (Exception e) {
            log.error("[{}] startSendRtp 失败 Call-ID:{} 错误:{}", LOG_TAG, callId, e.getMessage(), e);
            cleanup(session, true);
        }
    }

    /**
     * 上级发 BYE 停播（由 ByeRequestProcessor 在按 callId 命中推流会话时调用）。
     */
    public void onUpstreamBye(CascadeSendSession session) {
        if (session == null) return;
        cleanup(session, false);
        log.info("[{}] 上级停播清理完成 上级平台:{} 本平台通道编码:{} 源流ID:{}",
                LOG_TAG, session.getPlatformServerGbId(), session.getLocalChannelId(), session.getSrcStream());
    }

    /**
     * ZLM {@code on_send_rtp_stopped} 回调：某路推流被 ZLM 中止（对端断开 / 超时），清理会话。
     */
    public void onSendRtpStopped(String stream, String ssrc) {
        List<CascadeSendSession> sessions = sendSessionService.listBySrcStream(stream);
        for (CascadeSendSession s : sessions) {
            if (ssrc == null || ssrc.isBlank() || ssrc.equals(s.getSsrc())) {
                cleanup(s, true);
                log.info("[{}] on_send_rtp_stopped 清理 流ID:{} SSRC:{}", LOG_TAG, stream, s.getSsrc());
            }
        }
    }

    /**
     * 扫描 WAIT_ACK 超时会话：回 200 OK 后迟迟收不到上级 ACK → 释放并 BYE 上级。由 GbHealthScheduledTasks 定时调用。
     */
    public void sweepAckTimeout() {
        long timeoutMs = properties.getCascade().getSendAckTimeoutMs();
        LocalDateTime deadline = LocalDateTime.now().minusNanos(timeoutMs * 1_000_000L);
        for (CascadeSendSession s : sendSessionService.listAllActive()) {
            if (CascadeSendSession.STATUS_WAIT_ACK.equals(s.getStatus())
                    && s.getCreateTime() != null && s.getCreateTime().isBefore(deadline)) {
                log.warn("[{}] 等待上级 ACK 超时，释放并 BYE 上级 上级平台:{} 本平台通道编码:{}",
                        LOG_TAG, s.getPlatformServerGbId(), s.getLocalChannelId());
                cleanup(s, true);
            }
        }
    }

    /**
     * 源流巡检（C21c：按源流判活，不靠信令）：源流已死就逐个上级会话<b>先停推再发 BYE</b>，最后回收来源侧。
     *
     * <p>触发条件来自 {@link GbInviteLiveness} 的采样结论，只处理 READY 状态的实时点播/回放会话
     * （INVITING 还没起流；DOWNLOAD / RECORD 是 TCP 文件流，本就没有连续 RTP）。
     * 每轮每节点只查一次 {@code getMediaList}，不按会话重复问 ZLM。</p>
     *
     * @return 本轮判定并清理的源流条数（供日志用）
     */
    public int sweepZombieSources() {
        long timeoutSec = properties.getCascade().getRtpTimeoutSec();
        if (timeoutSec <= 0) {
            return 0;
        }
        List<GbInviteSession> actives = inviteSessionService.listAllActive();
        if (actives == null || actives.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        Map<String, List<JsonNode>> mediaByServer = new HashMap<>();
        int reaped = 0;
        for (GbInviteSession invite : actives) {
            try {
                if (!isLivenessJudgeable(invite)) {
                    continue;
                }
                MediaServer server = mediaServer(invite.getServerId());
                if (server == null) {
                    continue;
                }
                GbInviteLiveness.Probe probe =
                        GbInviteLiveness.probe(mediaOf(mediaByServer, server), APP, invite.getStreamId());
                switch (probe) {
                    case UNREACHABLE -> {
                        // 节点问不到：本轮整体放行，绝不以"查不到"当"流已死"
                        log.debug("[{}] 源流巡检跳过：节点不可达 节点:{} 流ID:{}",
                                LOG_TAG, server.getServerId(), invite.getStreamId());
                        continue;
                    }
                    case FLOWING -> {
                        touchLastRtpTime(invite, now);
                        continue;
                    }
                    case WATCHED -> {
                        // 有本地观看者时不判死：HLS 拉流、暂停、切片刷新都会出现瞬时 0 码流
                        continue;
                    }
                    case GONE, SILENT -> {
                        if (!GbInviteLiveness.isZombie(invite.getLastRtpTime(), invite.getReadyTime(), now, timeoutSec)) {
                            continue;
                        }
                        reapZombieSource(invite, probe);
                        reaped++;
                    }
                }
            } catch (Exception e) {
                log.warn("[{}] 源流巡检异常 流ID:{} 错误:{}", LOG_TAG, invite.getStreamId(), e.getMessage(), e);
            }
        }
        return reaped;
    }

    // ---------------- helpers ----------------

    /** 只有"已就绪、且靠连续 RTP 维持"的会话才参与判死；DOWNLOAD/RECORD 是 TCP 文件流，不适用 */
    private static boolean isLivenessJudgeable(GbInviteSession s) {
        if (s == null || s.getStreamId() == null || s.getStreamId().isBlank()) {
            return false;
        }
        if (!GbInviteSession.STATUS_READY.equals(s.getStatus())) {
            return false;
        }
        if (s.getReadyTime() == null && s.getLastRtpTime() == null) {
            return false;
        }
        String type = s.getSessionType();
        return !GbInviteSession.TYPE_DOWNLOAD.equals(type) && !GbInviteSession.TYPE_RECORD.equals(type);
    }

    /** 每轮每节点只问一次 ZLM；不可达节点把 {@code null} 也缓存下来，避免同轮反复重试 */
    private List<JsonNode> mediaOf(Map<String, List<JsonNode>> cache, MediaServer server) {
        if (cache.containsKey(server.getServerId())) {
            return cache.get(server.getServerId());
        }
        List<JsonNode> media = zlmRestClient.getMediaListOrNull(server);
        cache.put(server.getServerId(), media);
        return media;
    }

    /**
     * 刷新 {@code lastRtpTime}。巡检 10s 一轮，但只有推进够一定幅度才落 Redis，
     * 避免把整个会话 JSON 每秒重写一遍。
     */
    private void touchLastRtpTime(GbInviteSession invite, LocalDateTime now) {
        LocalDateTime last = invite.getLastRtpTime();
        if (last != null && Duration.between(last, now).getSeconds() < LAST_RTP_WRITE_INTERVAL_SEC) {
            return;
        }
        invite.setLastRtpTime(now);
        try {
            inviteSessionService.update(invite);
        } catch (Exception e) {
            log.warn("[{}] 回写 lastRtpTime 失败 流ID:{} 错误:{}", LOG_TAG, invite.getStreamId(), e.getMessage());
        }
    }

    /**
     * 源流已死：按 {@code by_src} 索引反查所有上级会话，逐个走 {@link #cleanup}（内含
     * <b>先 stopSendRtp 再 BYE</b> 的顺序，反了会出现"BYE 已发、推流还在"的窗口）。
     *
     * <p>来源侧不在本方法里额外清理：{@code cleanup} 的最后一跳在"没有其它上级在推、且本地无人观看"时
     * 已经回收来源侧；若这次因为"还有观看者"没回收，下一轮巡检仍会命中同一个源流并补上，
     * 自愈比在这里硬清更安全（硬清可能把有人正在看的流一起掐掉）。</p>
     */
    private void reapZombieSource(GbInviteSession invite, GbInviteLiveness.Probe probe) {
        String reason = probe == GbInviteLiveness.Probe.GONE ? "源流已从节点消失" : "源流长时间无 RTP";
        List<CascadeSendSession> sessions = sendSessionService.listBySrcStream(invite.getStreamId());
        if (sessions == null || sessions.isEmpty()) {
            log.warn("[{}] {}，无上级在推，直接回收来源侧 流ID:{} 设备:{} 通道:{}",
                    LOG_TAG, reason, invite.getStreamId(), invite.getDeviceId(), invite.getChannelId());
            support.timeoutSession(invite, LOG_TAG, reason);
            return;
        }
        log.warn("[{}] {}，逐个停推并 BYE 上级 流ID:{} 上级会话数:{}",
                LOG_TAG, reason, invite.getStreamId(), sessions.size());
        for (CascadeSendSession session : sessions) {
            cleanup(session, true);
        }
    }

    /**
     * 统一清理：stopSendRtp → 删会话 → BYE 上级(可选) → 无人观看时停取流。
     *
     * @param byeUpstream 是否需要本平台主动向上级发 BYE（上级主动 BYE 的场景传 false，其余传 true）
     */
    private void cleanup(CascadeSendSession session, boolean byeUpstream) {
        MediaServer server = mediaServer(session.getServerId());

        // 1) 停推流
        if (server != null) {
            try {
                zlmRestClient.stopSendRtp(server, session.getSrcApp(), session.getSrcStream(), session.getSsrc());
            } catch (Exception e) {
                log.warn("[{}] stopSendRtp 异常(忽略) 源流ID:{} 错误:{}", LOG_TAG, session.getSrcStream(), e.getMessage(), e);
            }
        }

        // 2) 删推流会话
        sendSessionService.removeBySendStreamId(session.getSendStreamId());

        // 3) 主动 BYE 上级（超时 / startSendRtp 失败等本平台主动结束的场景）
        if (byeUpstream) {
            byeUpstream(session);
        }

        // 4) 若这路源流已无其它上级在推、且本地也无人观看，则向真实下级停取流回收
        try {
            boolean stillSharing = !sendSessionService.listBySrcStream(session.getSrcStream()).isEmpty();
            if (!stillSharing && server != null) {
                GbInviteSession invite = inviteSessionService.getByStreamId(session.getSrcStream());
                if (invite != null && !streamHasReader(server, session.getSrcStream())) {
                    // 复用设备点播语义：BYE 下级 + closeRtpServer + 释放 SSRC + 删会话
                    support.failAndBye(invite, LOG_TAG, "级联无观看者回收取流");
                }
            }
        } catch (Exception e) {
            log.warn("[{}] 取流回收判断异常 源流ID:{} 错误:{}", LOG_TAG, session.getSrcStream(), e.getMessage(), e);
        }
    }

    private void byeUpstream(CascadeSendSession session) {
        GbParentPlatform p = gbParentPlatformMapper.selectById(session.getPlatformDbId());
        if (p == null) {
            log.warn("[{}] BYE 上级时平台已删除 平台ID:{}", LOG_TAG, session.getPlatformDbId());
            return;
        }
        try {
            Request request = headerProvider.createByeToUpstream(
                    p, session.getCallId(), session.getFromTag(), session.getToTag());
            sipSender.send(headerProvider.localIp(), request);
            log.info("[{}] 已向上级发 BYE 上级平台={} 本平台通道编码={}", LOG_TAG, p.getServerGbId(), session.getLocalChannelId());
        } catch (Exception e) {
            log.warn("[{}] 向上级发 BYE 失败 上级平台:{} 错误:{}", LOG_TAG, p.getServerGbId(), e.getMessage(), e);
        }
    }

    /**
     * 上级编码 → 可见通道（M4 出站三表反查，含共享授权校验）。
     * <p>上级 INVITE 带的是本平台对它暴露的码（本地透传时等于原始码，撞码时是重分配的暴露码），
     * 反查链 {@code out_code_map → out_share(visible, ref_alive=1) → res_channel} 任何一环缺失都返回 null，
     * 由调用方回 404 拒绝——撤销后到 DELETE 推送成功前，上级仍可能点播，此时必须直接拒绝（C12）。</p>
     */
    private ShareCatalogService.ExposedChannel findSharedChannel(Long platformDbId, String channelId) {
        ShareCatalogService.ExposedChannel resolved = shareCatalogService.resolveForPlay(platformDbId, channelId);
        if (resolved == null) {
            log.warn("[{}] 上级点播 上级通道编码:{} 未共享给该上级或资源已消失/已撤销", LOG_TAG, channelId);
        }
        return resolved;
    }

    /** 本地通道：走设备点播全链路（deviceId / channelId 都是设备侧国标码） */
    private PlayResultResponse playLocal(String deviceCode, String channelCode) {
        PlayStartRequest req = new PlayStartRequest();
        req.setDeviceId(deviceCode);
        req.setChannelId(channelCode);
        req.setTransport(null);        // 取流侧传输由默认策略决定，与上级收流传输无关
        req.setStreamNumber(null);     // 级联点播固定主码流
        return playService.start(req);
    }

    /** 入站通道：对端平台就是"设备"，按行 id 反查来源平台后向对端 INVITE（3.7.4） */
    private PlayResultResponse playInbound(ResChannel channel) {
        InboundPlayRequest req = new InboundPlayRequest();
        req.setChannelRowId(String.valueOf(channel.getId()));
        req.setTransport(null);
        req.setStreamNumber(null);
        return playService.startInbound(req);
    }

    private MediaServer mediaServer(String serverId) {
        return mediaServerMapper.selectOne(
                new LambdaQueryWrapper<MediaServer>().eq(MediaServer::getServerId, serverId));
    }

    /** 给"上级 + 上级通道编码"这条出站腿记冷却；冷却时长 &le; 0 时由守卫自己忽略 */
    private void cooldownOutbound(GbParentPlatform platform, String channelId) {
        mediaHopGuard.cooldown(MediaHopGuard.outboundKey(platform.getId(), channelId),
                properties.getCascade().getHopCooldownSec() * 1000L);
    }

    private Integer sendPortInRange() {
        return sendSessionService.acquireSendPort(
                properties.getCascade().getSendRtpPortStart(),
                properties.getCascade().getSendRtpPortEnd());
    }

    /**
     * 按回 200 OK 时预分配的端口推流；端口被占则换下一个重试（最多 {@value #MAX_SEND_PORT_ATTEMPTS} 次）。
     *
     * <p>为什么允许"实际端口 ≠ SDP 里的端口"：UDP 下上级是按<b>自己</b> INVITE 声明的 {@code c=} / {@code m=} 收流，
     * 不校验本平台的源端口；TCP 被动同理（本平台是连接发起方，源端口由系统分配）。
     * 换端口只影响排障时对不上号，不影响收流，所以可用性优先（决策 D5）。
     * 唯一的例外是 TCP 主动——那条路径在回 200 前就已开好监听，端口必然真实。</p>
     *
     * <p>顺序要求：本方法只在 ACK 到达后调用，此时 200 OK 已发出。</p>
     */
    private int startSendRtpWithPortFallback(CascadeSendSession session, MediaServer server, boolean udp)
            throws Exception {
        Integer preferred = session.getLocalSendPort();
        Integer srcPort = preferred != null && preferred > 0 ? preferred : sendPortInRange();
        for (int attempt = 0; attempt < MAX_SEND_PORT_ATTEMPTS; attempt++) {
            try {
                int localPort = zlmRestClient.startSendRtp(server, session.getSrcApp(), session.getSrcStream(),
                        session.getSsrc(), session.getDstIp(), session.getDstPort(), udp, srcPort);
                int actual = localPort > 0 ? localPort : srcPort;
                if (attempt > 0) {
                    log.warn("[{}] 预分配端口 {} 不可用，已改用 {}（回给上级的 SDP 仍写 {}，UDP/TCP被动下不影响上级收流）",
                            LOG_TAG, preferred, actual, preferred);
                }
                return actual;
            } catch (Exception e) {
                if (!isPortConflict(e) || attempt == MAX_SEND_PORT_ATTEMPTS - 1) {
                    throw e;
                }
                log.warn("[{}] 推流端口 {} 冲突，换端口重试 第{}次 错误:{}",
                        LOG_TAG, srcPort, attempt + 1, e.getMessage());
                srcPort = sendPortInRange();
            }
        }
        throw new IllegalStateException("startSendRtp 端口重试耗尽");
    }

    /**
     * 判断异常是否由本地端口占用引起。只有这类错误值得换端口重试；
     * "流不存在""对端不可达"之类换端口也不会好，必须原样抛出交给上层清理。
     */
    private static boolean isPortConflict(Throwable e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("already in use")
                || msg.contains("address already")
                || msg.contains("bind")
                || msg.contains("端口");
    }

    /**
     * 点播中止时回收已拉起的取流。<b>只在没有其它观看者时执行</b>——
     * 否则会把本地正在预览/回放的同一路流一起停掉（同一通道 10 人只拉一路，见 3.11.3）。
     */
    private void stopFetchedStreamIfUnused(MediaServer server, String streamId) {
        try {
            if (streamHasReader(server, streamId)) {
                log.info("[{}] 取流回收跳过：仍有其它观看者 流ID:{}", LOG_TAG, streamId);
                return;
            }
            PlayStopRequest stop = new PlayStopRequest();
            stop.setStreamId(streamId);
            playService.stop(stop);
            log.info("[{}] 点播中止，已回收取流 流ID:{}", LOG_TAG, streamId);
        } catch (Exception e) {
            log.warn("[{}] 回收取流失败(忽略) 流ID:{} 错误:{}", LOG_TAG, streamId, e.getMessage());
        }
    }

    /**
     * getMediaList 里 totalReaderCount / readerCount > 0 视为仍有观看者。
     * <p>ZLM 会把同一路流按 schema（ts / rtsp / fmp4 / rtmp…）各返回一条，readerCount 是各 schema 自己的读者数，
     * totalReaderCount 才是全量。调用方拿这个结果决定要不要向真实设备回收取流，误判“没人看”会搞断正在进行的观看，
     * 所以遍历全部匹配项取最大值，不能命中第一条就下结论。</p>
     */
    private boolean streamHasReader(MediaServer server, String stream) {
        int maxReaders = 0;
        for (JsonNode node : zlmRestClient.getMediaList(server)) {
            if (!APP.equals(node.path("app").asText()) || !stream.equals(node.path("stream").asText())) {
                continue;
            }
            maxReaders = Math.max(maxReaders, Math.max(
                    node.path("readerCount").asInt(0), node.path("totalReaderCount").asInt(0)));
        }
        return maxReaders > 0;
    }

    private void sendError(SIPRequest request, int code, String reason) {
        try {
            SIPResponse resp = headerProvider.buildResponse(request, code, reason);
            sipSender.send(request.getLocalAddress().getHostAddress(), resp);
        } catch (Exception e) {
            log.warn("[{}] 回 {} 失败 错误:{}", LOG_TAG, code, e.getMessage(), e);
        }
    }
}
