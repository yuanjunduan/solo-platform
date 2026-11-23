package com.solo.domain.share.service.impl;

import com.solo.common.ServiceException;
import com.solo.domain.share.service.ShareRepublishService;
import com.solo.infra.mapper.OutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 出站事件重试实现。
 *
 * <p>上限 500 与明细上限同口径：一次重试超过这个量说明问题不在单条报文，
 * 应该先看平台在线状态与队列积压，逐条重试只会把同样的失败再演一遍。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareRepublishServiceImpl implements ShareRepublishService {

    private static final int MAX_RETRY_PER_REQUEST = 500;

    private final OutboxMapper outboxMapper;

    @Override
    @Transactional
    public int retry(List<Long> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            throw new ServiceException("请选择要重试的事件");
        }
        Set<Long> clean = new LinkedHashSet<>();
        for (Long id : eventIds) {
            if (id != null) {
                clean.add(id);
            }
        }
        if (clean.isEmpty()) {
            throw new ServiceException("请选择要重试的事件");
        }
        if (clean.size() > MAX_RETRY_PER_REQUEST) {
            throw new ServiceException(400, "一次最多重试 " + MAX_RETRY_PER_REQUEST
                    + " 条；超过这个量请先检查上级平台的在线状态与队列积压");
        }
        int reset = outboxMapper.resetForRetry(new ArrayList<>(clean), LocalDateTime.now());
        log.info("[上级共享] 人工重试 选中={} 实际重置={}", clean.size(), reset);
        return reset;
    }
}
