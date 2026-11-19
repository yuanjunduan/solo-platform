package com.solo.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

import java.util.ArrayList;
import java.util.List;

/**
 * 周期性日志过滤器。
 * <p>挂在控制台和主日志 appender 上，把"定时任务 / 周期对账"的低级别日志挡掉，只让它们落到
 * {@code logs/scheduled.log}（对应 logger 在 logback-spring.xml 里单独挂了 SCHEDULED_FILE appender）。</p>
 *
 * <p>规则由 {@code PERIODIC_RULES} 提供，分号分隔，每条两种形态：</p>
 * <ul>
 *   <li>{@code logger 前缀}：该 logger 下所有低于 WARN 的日志都不进主日志；</li>
 *   <li>{@code logger 前缀|消息前缀}：只有消息前缀匹配的才挡。用于同一个类里既有业务操作日志、
 *       又有周期性对账日志的场景（如拉流代理：{@code [拉流代理] 保存} 要留主日志，
 *       {@code [拉流代理对账]} 只进周期日志）。</li>
 * </ul>
 *
 * <p>WARN 及以上一律放行：周期任务里的错误必须留在主日志和控制台，不能因为分流而丢失。</p>
 */
public class PeriodicLogFilter extends Filter<ILoggingEvent> {

    /** 规则串，格式见类注释；由 logback 注入 */
    private String rules;

    /** 解析后的规则：{logger 前缀, 消息前缀或 null} */
    private final List<String[]> parsedRules = new ArrayList<>();

    public void setRules(String rules) {
        this.rules = rules;
    }

    @Override
    public void start() {
        parsedRules.clear();
        if (rules != null) {
            for (String raw : rules.split(";")) {
                String rule = raw.trim();
                if (rule.isEmpty()) {
                    continue;
                }
                int split = rule.indexOf('|');
                if (split < 0) {
                    parsedRules.add(new String[]{rule, null});
                } else {
                    parsedRules.add(new String[]{rule.substring(0, split).trim(), rule.substring(split + 1)});
                }
            }
        }
        super.start();
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        // WARN / ERROR 不拆分：分流只针对 INFO 及以下
        if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
            return FilterReply.NEUTRAL;
        }
        String loggerName = event.getLoggerName();
        if (loggerName == null) {
            return FilterReply.NEUTRAL;
        }
        for (String[] rule : parsedRules) {
            if (!loggerName.startsWith(rule[0])) {
                continue;
            }
            if (rule[1] == null) {
                return FilterReply.DENY;
            }
            String message = event.getFormattedMessage();
            if (message != null && message.startsWith(rule[1])) {
                return FilterReply.DENY;
            }
        }
        return FilterReply.NEUTRAL;
    }
}
