package com.solo.domain.sync.spi;

import com.solo.common.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按协议标识路由到适配器（3.7.3 "注册到工厂"）。
 *
 * <p>新增厂商 = 新增一个 {@link InboundAdapter} 实现 + 在 {@code platform.sync_protocol} 填对值，
 * 本类与同步服务都不用改。</p>
 */
@Slf4j
@Component
public class InboundAdapterRegistry {

    private final Map<String, InboundAdapter> adapters = new HashMap<>();

    public InboundAdapterRegistry(List<InboundAdapter> implementations) {
        for (InboundAdapter adapter : implementations) {
            InboundAdapter previous = adapters.put(adapter.protocol(), adapter);
            if (previous != null) {
                // 协议标识重复属于装配错误：两个实现抢同一个值，路由结果将取决于装配顺序
                throw new IllegalStateException("协议标识重复：" + adapter.protocol());
            }
        }
        log.info("[资源同步] 已注册入站适配器 {}", adapters.keySet());
    }

    /**
     * 解析协议。
     *
     * @param syncProtocol 平台配置值，可为空
     * @param fallback     旧版 {@code platform.protocol}，作为缺省兜底
     */
    public InboundAdapter resolve(String syncProtocol, String fallback) {
        String protocol = syncProtocol == null || syncProtocol.isBlank() ? fallback : syncProtocol;
        if (protocol == null || protocol.isBlank()) {
            throw new ServiceException("平台未配置目录同步协议");
        }
        InboundAdapter adapter = adapters.get(protocol);
        if (adapter == null) {
            throw new ServiceException("不支持的目录同步协议：" + protocol);
        }
        return adapter;
    }
}
