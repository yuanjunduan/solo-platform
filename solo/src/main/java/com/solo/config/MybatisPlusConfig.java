package com.solo.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;

import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.solo.infra.interceptor.ResourceWriteGuardInterceptor;
import com.solo.infra.ops.SlowSqlInterceptor;
import com.solo.infra.ops.SlowSqlStats;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.Properties;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 不指定 DbType：分页插件按当前 JDBC 连接自动识别 MySQL / PostgreSQL
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor()); // 如果配置多个插件, 切记分页最后添加
        return interceptor;
    }

    /**
     * 只读收口第三层（3.6.5）：{@code UPDATE/DELETE res_*} 若没带来源条件或被写成硬删，
     * 按 {@code resource.write-guard.mode} 告警或抛错。
     *
     * <p>返回 {@link Interceptor} 就会被 mybatis-spring-boot 自动装配进 SqlSessionFactory，
     * 不需要再挂到某个 Mapper 上。</p>
     */
    @Bean
    public Interceptor resourceWriteGuardInterceptor(
            @Value("${resource.write-guard.mode:warn}") String mode) {
        return new ResourceWriteGuardInterceptor(mode);
    }

    /**
     * 慢 SQL 探测（M7，6.7）：与只读守卫一样，返回 {@link Interceptor} 即被自动装配。
     * 统计口径见 {@link SlowSqlStats}：只记语句 id 与耗时，不记 SQL 原文与参数值。
     */
    @Bean
    public Interceptor slowSqlInterceptor(SlowSqlStats slowSqlStats) {
        return new SlowSqlInterceptor(slowSqlStats);
    }

    // mapper XML 里用 databaseId 区分方言语句；识别不到的库不会加载带 databaseId 的语句
    @Bean
    public DatabaseIdProvider databaseIdProvider() {
        VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
        Properties properties = new Properties();
        properties.setProperty("MySQL", "mysql");
        properties.setProperty("MariaDB", "mysql");
        properties.setProperty("PostgreSQL", "postgresql");
        provider.setProperties(properties);
        return provider;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
