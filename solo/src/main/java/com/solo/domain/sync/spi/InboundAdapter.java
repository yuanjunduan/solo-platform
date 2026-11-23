package com.solo.domain.sync.spi;

/**
 * 资源同步适配器（3.7.3）：一个协议一个实现，按 {@code platform.sync_protocol} 路由。
 *
 * <p>只保留 M2a 真正用得上的两个方法。文档里还有 {@code connect}/{@code subscribe}/
 * {@code disconnect}——它们属于 M2b（增量通知与运行保障），等真有第二个协议实现时再补，
 * 现在写出来只会是空实现。</p>
 *
 * <p><b>实现约束</b>：{@link #open} 必须尽快返回，不能阻塞到目录收完——GB28181 的目录是
 * 异步 MESSAGE 分片推过来的，阻塞等待会把 SIP 线程池占死。</p>
 */
public interface InboundAdapter {

    /** 与 {@code platform.sync_protocol} 对应，例如 {@code SIP_GB28181} */
    String protocol();

    /**
     * 发起一轮目录拉取。
     *
     * <p>返回后，实现应通过 {@code sink} 陆续投递批次，最后投 {@code onComplete} 或 {@code onError}。
     * 前置条件不满足（对端未注册、平台被禁用、该平台已有活跃轮次）应直接抛
     * {@link com.solo.common.ServiceException}，让调用方给出明确错误码，而不是异步报错。</p>
     *
     * @return 可中止的会话句柄
     */
    CatalogSession open(PlatformConn conn, CatalogSink sink);
}
