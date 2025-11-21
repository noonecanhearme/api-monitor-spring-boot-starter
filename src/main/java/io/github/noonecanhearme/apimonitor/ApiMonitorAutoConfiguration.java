package io.github.noonecanhearme.apimonitor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.PostConstruct;
import io.github.noonecanhearme.apimonitor.aspect.ApiLogAspect;


/**
 * API监控自动配置类
 * <p>
 * 负责自动配置API监控所需的所有组件，包括日志记录器、切面、火焰图生成器等。
 * 当应用启动时，Spring Boot会根据配置自动初始化这些Bean。
 */
@Configuration
@EnableConfigurationProperties(ApiMonitorProperties.class)
public class ApiMonitorAutoConfiguration {

    /**
     * 配置API日志记录器
     * @param properties API监控配置属性
     * @return ApiLogger实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiLogger apiLogger(ApiMonitorProperties properties) {
        return new ApiLogger(properties);
    }

    /**
     * 配置API切面
     * @param apiLogger API日志记录器
     * @param properties API监控配置属性
     * @param flameGraphGenerator 火焰图生成器
     * @return ApiLogAspect实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiLogAspect apiLogAspect(ApiLogger apiLogger, ApiMonitorProperties properties, FlameGraphGenerator flameGraphGenerator) {
        return new ApiLogAspect(apiLogger, properties, flameGraphGenerator);
    }

    /**
     * 配置火焰图生成器
     * @param properties API监控配置属性
     * @return FlameGraphGenerator实例
     */
    @Bean
    @ConditionalOnMissingBean
    public FlameGraphGenerator flameGraphGenerator(ApiMonitorProperties properties) {
        return new FlameGraphGenerator(properties);
    }

    /**
     * 配置JdbcTemplate到ApiLogger的注入，避免循环依赖
     * @param apiLogger API日志记录器
     * @param jdbcTemplate JdbcTemplate实例
     * @return JdbcTemplateConfigurer实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "api.monitor.database", name = "enabled", havingValue = "true")
    @DependsOn({"apiLogger", "jdbcTemplate"})
    public JdbcTemplateConfigurer jdbcTemplateConfigurer(ApiLogger apiLogger, JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateConfigurer(apiLogger, jdbcTemplate);
    }

    /**
     * JdbcTemplate配置器，用于延迟注入JdbcTemplate到ApiLogger
     * 解决JdbcTemplate和ApiLogger之间可能出现的循环依赖问题
     */
    public static class JdbcTemplateConfigurer {
        private final ApiLogger apiLogger;
        private final JdbcTemplate jdbcTemplate;

        public JdbcTemplateConfigurer(ApiLogger apiLogger, JdbcTemplate jdbcTemplate) {
            this.apiLogger = apiLogger;
            this.jdbcTemplate = jdbcTemplate;
        }

        @PostConstruct
        public void configure() {
            apiLogger.setJdbcTemplate(jdbcTemplate);
        }
    }
}