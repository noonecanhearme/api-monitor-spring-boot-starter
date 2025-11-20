package io.github.noonecanhearme.apimonitor.config;

import io.github.noonecanhearme.apimonitor.ApiMonitorProperties;
import io.github.noonecanhearme.apimonitor.filter.RequestCachingFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 过滤器配置类
 */
@Configuration
public class FilterConfig {

    /**
     * 注册请求体缓存过滤器
     */
    @Bean
    @ConditionalOnProperty(prefix = "api.monitor", name = "enabled", havingValue = "true")
    public FilterRegistrationBean<RequestCachingFilter> requestCachingFilter(ApiMonitorProperties properties) {
        FilterRegistrationBean<RequestCachingFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new RequestCachingFilter());
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(Integer.MIN_VALUE + 1); // 设置较高优先级
        registrationBean.setName("requestCachingFilter");
        return registrationBean;
    }
}