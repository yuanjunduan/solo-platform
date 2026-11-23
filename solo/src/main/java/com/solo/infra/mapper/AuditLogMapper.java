package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.audit.model.AuditLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 审计日志持久化。业务侧只 INSERT，不提供 UPDATE / DELETE（只追加，见 3.12.6）。
 *
 * <p>唯一例外是 {@link #deleteBeforeBatch}：保留期回收（M7，5.2「保留 1 年」）。
 * 它不暴露给任何业务接口，只由 {@code RetentionJob} 调用。</p>
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /**
     * 保留期分批删除。派生表包一层是为了 MySQL 允许"删除同时子查询同表"，
     * PostgreSQL 同样接受这种写法，两套方言共用一条 SQL。
     */
    @Delete("""
            DELETE FROM audit_log WHERE id IN (
                SELECT id FROM (
                    SELECT id FROM audit_log WHERE create_time < #{before} LIMIT #{limit}
                ) t
            )
            """)
    int deleteBeforeBatch(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
