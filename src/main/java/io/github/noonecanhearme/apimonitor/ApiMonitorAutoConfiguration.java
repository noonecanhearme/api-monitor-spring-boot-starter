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
 * API monitoring auto-configuration class
 * <p>
 * Responsible for auto-configuring all components needed for API monitoring, including log recorder, aspect, flame graph generator, etc.
 * When the application starts, Spring Boot will automatically initialize these beans based on the configuration.
 */
@Configuration
@EnableConfigurationProperties(ApiMonitorProperties.class)
public class ApiMonitorAutoConfiguration {

    /**
     * Configure API logger
     * @param properties API monitoring configuration properties
     * @return ApiLogger instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiLogger apiLogger(ApiMonitorProperties properties) {
        return new ApiLogger(properties);
    }

    /**
     * Configure API aspect
     * @param apiLogger API logger
     * @param properties API monitoring configuration properties
     * @param flameGraphGenerator Flame graph generator
     * @return ApiLogAspect instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiLogAspect apiLogAspect(ApiLogger apiLogger, ApiMonitorProperties properties, FlameGraphGenerator flameGraphGenerator) {
        return new ApiLogAspect(apiLogger, properties, flameGraphGenerator);
    }

    /**
     * Configure flame graph generator
     * @param properties API monitoring configuration properties
     * @return FlameGraphGenerator instance
     */
    @Bean
    @ConditionalOnMissingBean
    public FlameGraphGenerator flameGraphGenerator(ApiMonitorProperties properties) {
        return new FlameGraphGenerator(properties);
    }

    /**
     * Configure JdbcTemplate injection to ApiLogger to avoid circular dependency
     * @param apiLogger API logger
     * @param jdbcTemplate JdbcTemplate instance
     * @return JdbcTemplateConfigurer instance
     */
    @Bean
    @ConditionalOnProperty(prefix = "api.monitor.database", name = "enabled", havingValue = "true")
    @DependsOn({"apiLogger", "jdbcTemplate"})
    public JdbcTemplateConfigurer jdbcTemplateConfigurer(ApiLogger apiLogger, JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateConfigurer(apiLogger, jdbcTemplate);
    }

    /**
     * JdbcTemplate configurer, used for delayed injection of JdbcTemplate into ApiLogger
     * Solves potential circular dependency issues between JdbcTemplate and ApiLogger
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