package com.solo.domain.sync.spi;

/**
 * 一轮目录拉取的句柄。
 *
 * <p>存在的意义只有"能中止"：超时、用户取消、平台被禁用时都要能把投递口摘掉，
 * 否则对端继续推的批次会写进一个已经判失败的轮次。</p>
 */
public interface CatalogSession extends AutoCloseable {

    /** 会话引用（GB28181 = 对端设备编码），日志与排障用 */
    String ref();

    /** 摘掉投递口；幂等，重复调用不报错 */
    @Override
    void close();
}
