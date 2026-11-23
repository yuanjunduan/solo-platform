package com.solo.gb28181.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "gb28181")
public class Gb28181Properties {

    // SIP 监听 IP，留空就监听所有网卡
    private String ip;

    /**
     * SIP 公网 IP，NAT/公网部署的时候要填，用来构造 Via/Contact 头，
     * 不然下级/设备回包找不到路。留空就用 ip 字段的值。
     */
    private String publicIp;

    /**
     * 取实际用于构造 SIP 消息的 IP：公网 IP 配了就用公网的，没配就用本机的。
     * <p>
     * 注意：Spring Boot 读 YAML 时 public-ip 空值会变成空串""，不是 null，
     * 所以判断的时候必须用 isBlank()，不能用 != null。
     * 之前踩过坑——空串被当成有效 IP 写进 Via 头，设备按空 host 回包，
     * 结果就是 DeviceInfo 查询啥的全没响应。
     */
    public String resolveSipIp(String localIp) {
        return (publicIp == null || publicIp.isBlank()) ? localIp : publicIp;
    }

    // SIP 监听端口
    private int port = 5060;

    // 本平台 SIP ID，20 位国标编码
    private String id;

    // 本平台 SIP 域
    private String domain;

    // 设备注册 Digest 鉴权密码；空密码为开放模式，仅限隔离测试环境使用
    private String password;

    // 设备注册有效期，单位秒
    private int registerExpires = 3600;

    // 心跳超时倍数，超过这个数 × 心跳周期没收到心跳就算离线
    private int keepaliveTimeoutMultiplier = 3;

    /**
     * 手动存活探测超时时间，毫秒。
     * 向设备发 DeviceInfo 查询后等多久算超时。
     * NAT 后、广域网、老设备响应慢的，别设太短。
     */
    private long detectTimeoutMs = 10000;

    /**
     * 优先用报文源 IP/端口当设备地址。
     * true：直接取 UDP/TCP 收包源地址，适合 NAT 后的设备（大多数情况）
     * false：优先取 Via 头的 received/rport，适合公网直连、要严格按 Via 路由的场景
     */
    private boolean useSourceIpAsRemoteAddress = true;

    /**
     * 业务线程池配置 - 核心线程数（默认：max(2 * CPU, 16)）
     */
    private Integer corePoolSize;

    /**
     * 业务线程池配置 - 最大线程数（默认：10 * corePoolSize）
     */
    private Integer maxPoolSize;

    /**
     * 业务线程池配置 - 队列容量（默认：10000）
     */
    private Integer queueCapacity;

    /**
     * 级联下级侧配置（本平台作为下级向上级平台推送数据时使用）。
     */
    private Cascade cascade = new Cascade();

    /**
     * Catalog 分片累积调优（下级/平台设备的 Catalog 响应处理用）。
     * <p>设备通告的 SumNum 在协议层无"完成"信号，平台只能靠启发式判断何时收尾：
     * 完全无响应（expire）→ 强制收尾，防止 4w+ 大目录慢流时内存堆积。</p>
     */
    private Catalog catalog = new Catalog();

    /**
     * 通道目录同步专用线程池调优（落库任务，IO 密集）。
     * <p>独立于业务线程池：Catalog 同步是 10w+ 通道下的重活，不能与 SIP 消息分发挤同一个池。</p>
     */
    private CatalogSyncPool catalogSyncPool = new CatalogSyncPool();

    private Position position = new Position();

    private Record record = new Record();

    /**
     * 点播取流侧调优（3.11.3）。
     */
    private Play play = new Play();

    /**
     * <p>本平台作为下级向上级平台推送数据（Catalog 响应、Notify 变更）时的分片/节奏控制。</p>
     */
    @Data
    public static class Cascade {

        /**
         * Catalog 响应分片大小：每包 XML 中 &lt;Item&gt; 数量上限。
         * <p>过大易触发上级 SIP MSG 长度限制或 UDP 分片，过小徒增交互次数。主流开源国标平台默认 3-8，
         * 1/2/4/8 是经过线上踩坑收敛出的「单包安全档位」（ADR-027）。本项目取 3（保守起步）。</p>
         */
        private int catalogPageSize = 3;

        /**
         * Catalog 响应等 ACK 超时（毫秒）。每发一片 MESSAGE，等上级回 200 OK 的最长等待时间；
         * 超时则按 {@link #catalogPageIntervalMs} 兜底节奏发下一片，避免对端死掉时整条链路卡死。
         * <p>5s 粒度受 {@link com.solo.gb28181.task.GbHealthScheduledTasks#sweepSipSubscribe} 限制，
         * 实际最坏情况 ≈ 5s + 3s。3s 适合内网主流平台；公网 / 老国标可上调到 5-8s。</p>
         */
        private long catalogAckTimeoutMs = 3000;

        /**
         * Catalog 响应 fallback 间隔（毫秒）。仅当上一片等 ACK 超时 / 出错时使用——按兜底节奏继续发下一片，
         * 防止对端长时间无响应时本平台 sender 线程被长时间占用。ACK 正常到达时不走这条（ADR-027 改为 ACK 驱动）。
         * <p>UDP 突发易被上级丢包或触发 fair-queuing，200ms 足够宽松且最坏情况（兜底分支）总耗时可控。</p>
         */
        private long catalogPageIntervalMs = 200;

        /**
         * 上级点播（sendRtp）本地发送端口段起始。
         * <p>本平台把流转推给上级时，ZLM 从此段选一个本地端口发流。需与 ZLM 收流端口段错开，并在防火墙放行。</p>
         */
        private int sendRtpPortStart = 50000;

        /**
         * 上级点播（sendRtp）本地发送端口段结束。
         */
        private int sendRtpPortEnd = 60000;

        /**
         * 上级点播回 200 OK 后等待上级 ACK 的超时（毫秒）。超时未收到 ACK 即释放会话并向上级发 BYE。
         */
        private long sendAckTimeoutMs = 60_000;

        /**
         * 回给上级 200 OK 的媒体地址（SDP c= / o= 行）。留空则依次回退到
         * {@code MediaServer.streamIp} 与 {@code MediaServer.hostIp}。
         * <p>NAT / 多网卡 / 上级不在同一网段时，节点 IP 往往不是上级可达的地址，必须在这里显式指定对外 IP。
         * 配了但不合法（{@code 0.0.0.0} / 回环）时直接拒绝点播并回 500，不再静默回退——配置错误必须立刻暴露，
         * 否则上级表现为"点播黑屏，而本侧取流、推流全部正常"，是最难查的一类故障（架构约束 C21a）。</p>
         */
        private String sdpPublicIp;

        /**
         * 源流存活阈值（秒）：源流超过该时长一直没有任何 RTP（或者已经从 ZLM 上消失），
         * 判定为僵尸流 → 逐个上级会话<b>先停推再发 BYE</b>，最后回收来源侧会话。
         * <p>专网 30s、公网 60s 是常见值；设 0 或负数关闭巡检。</p>
         * <p>架构文档里还写了"按上级平台覆盖（{@code platform.rtp_timeout_sec}）"，
         * 但 {@code gb_parent_platform} 目前没有该列，因此首版只有全局阈值。</p>
         */
        private long rtpTimeoutSec = 30;

        /**
         * 每跳点播失败后的冷却时长（秒）。同一跳（入站取流腿按 streamId、出站推流腿按上级+通道）
         * 在一次点播失败后进入冷却，冷却期内的重复请求直接快速失败，不再触碰设备/下级平台。
         * <p>为什么需要：上游或设备卡死时，前端与上级都会按秒级节奏重试，每次重试都要等满
         * {@code media.invite-timeout-seconds} 才失败，线程池很快被拖满。3s 足够覆盖一次
         * 设备级抖动，又不会让"刚修好"的通道长时间不可用。设为 0 或负数关闭冷却。</p>
         */
        private int hopCooldownSec = 3;
    }

    /**
     * <p>设备响应 Catalog 时按 SumNum 分多个 MESSAGE 报文分批送回，平台无法从协议层判定"已发完"，
     * {@link #expireMs} 用来兜底——完全多少毫秒无任何响应（含空响应）→ 强制收尾，
     * 防止 4w+ 大目录慢流时内存堆积。其余几项是"边收边落"的刷盘策略。普通小目录通常 10s 内收齐，
     * 根本走不到 expireMs 这条路径。</p>
     */
    @Data
    public static class Catalog {

        /**
         * 强制收尾：完全多少毫秒无任何响应 → sweep 路径 partial finalize，释放内存。
         * <p>4w+ 大目录 + 慢设备场景下兜底用，普通 1k 通道规模无需调整。默认 600s（10 分钟）。</p>
         */
        private long expireMs = 600_000L;

        /**
         * 滚动缓冲阈值：分片累积达到该条数后立即触发批量入库，未必等齐 SumNum。
         * <p>GB28181 协议没有"发完"信号；靠 SumNum 全量收齐可能慢达数分钟，
         * 改为边收边落：每凑够此数就 flush 一次，最后一片连同残片一起收尾。
         * 调大 → 入库更少但内存占用更高；调小 → 入库更频繁但 DB 压力更大。默认 1000。</p>
         */
        private int flushThreshold = 1000;

        /**
         * 时间触发 flush 间隔（毫秒）。距上次 flush 超过此间隔且缓冲非空，强制 flush 一次。
         * <p>解决"设备发包慢（每分片 0-1 个 item）+ flushThreshold 较大 → 进度条长期不动"
         * 问题：每 N 毫秒即便缓冲未达阈值也触发 flush，让前端进度能持续滚动。
         * 太小 → 频繁空 flush 浪费 DB；太大 → 进度条更新卡顿。默认 5000。</p>
         */
        private long flushIntervalMs = 5000L;

        /**
         * 单批入库 SQL 的行数上限。MySQL 单条 INSERT 行数受 max_allowed_packet 限制，
         * 500-1000 行是安全区间。flush 缓冲 ≥ 该值时分片入库，每片独立执行 upsert SQL。
         */
        private int batchSize = 500;

        /**
         * 丢包容忍：只有累计收到 ≥ sumNum × 阈值比例时，才删"已不存在的通道"（清空设备目录）；
         * 低于此比例说明 UDP 丢包或中途超时，保留已入库数据等下次 sync 补齐。
         * <p>默认 0.95：5% 缺失容忍。</p>
         */
        private double completenessThreshold = 0.95;
    }

    /**
     * <p>调优思路：DB 写并发受 MySQL 连接池（默认 10）和单行锁竞争约束，
     * 线程数过多只会增加上下文切换。core 20 / max 50 / queue 5000 适合单库 10w+ 通道批写；
     * 撑满后 CallerRunsPolicy 让上游（CatalogDataCache flush 线程）回压，自然限流。</p>
     */
    @Data
    public static class CatalogSyncPool {

        /**
         * 核心线程数
         */
        private int corePoolSize = 20;

        /**
         * 最大线程数
         */
        private int maxPoolSize = 50;

        /**
         * 队列容量（每个任务是一次 flush 批次，500-1000 条）
         */
        private int queueCapacity = 5000;
    }

    /**
     * <p>设备可按 5s/次高频上报 MobilePosition，全量长期保留会让 {@code gb_mobile_position}
     * 无限膨胀，因此按保留天数每日清理过期数据。</p>
     */
    @Data
    public static class Position {

        /**
         * 轨迹保留天数，超过的记录每日凌晨清理。设为 0 或负数表示不自动清理。默认 30 天。
         */
        private int retentionDays = 30;
    }

    /**
     * <p>云端录像：切片落存储失败的重试次数和录像保留天数。</p>
     */
    @Data
    public static class Record {

        /**
         * 录像保留天数，每日凌晨清理更旧的成功切片（存储对象一并删除）。
         * 0 或负数不自动清理。计划上配了 retentionDays 时以计划为准，计划为 0 时回落到这个全局值。
         */
        private int retentionDays = 30;

        /**
         * on_record_mp4 拉 MP4 落存储失败后的最大重试次数。超过后切片停留在 FAILED，
         * 源 URL 是 ZLM 本机地址，ZLM 重启/切片目录清理后大概率已经拉不到，重试太多没意义。
         */
        private int maxRetry = 3;

        /**
         * 上传中（ZLM 回调后进程崩溃等）卡在 UPLOADING 多久算僵死，由重试任务重新拉取，单位分钟。
         */
        private int uploadingStuckMinutes = 30;
    }

    /**
     * 点播取流侧调优（3.11.3）：空闲回收阈值。
     */
    @Data
    public static class Play {

        /**
         * 空闲回收阈值（秒）：一路取流会话连续该时长没有任何观看者（ZLM 播放器/级联推流/语音广播消费），
         * 巡检时主动 BYE + 关流，避免长期占用上游设备带宽与本地端口。
         * <p>从未有观看者的会话以 readyTime（或 startTime）为基线起算，避免"刚拉起就被回收"。
         * 设 0 或负数关闭巡检。</p>
         */
        private int idleReclaimSec = 120;
    }
}
