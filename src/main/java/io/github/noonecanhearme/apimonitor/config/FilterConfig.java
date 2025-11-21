package io.github.noonecanhearme.apimonitor.config;

import io.github.noonecanhearme.apimonitor.ApiMonitorProperties;
import io.github.noonecanhearme.apimonitor.filter.RequestCachingFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import javax.servlet.Filter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filter configuration class
 */
@Configuration
public class FilterConfig {

    /**
     * Register request body caching filter
     */
    @Bean
    @ConditionalOnProperty(prefix = "api.monitor", name = "enabled", havingValue = "true")
    public FilterRegistrationBean requestCachingFilter(ApiMonitorProperties properties) {
        FilterRegistrationBean registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new RequestCachingFilter());
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(Integer.MIN_VALUE + 1); // Set higher priority
        registrationBean.setName("requestCachingFilter");
        return registrationBean;
    }
}