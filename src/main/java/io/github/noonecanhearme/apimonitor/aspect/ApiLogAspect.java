package io.github.noonecanhearme.apimonitor.aspect;

import io.github.noonecanhearme.apimonitor.ApiMonitorProperties;
import io.github.noonecanhearme.apimonitor.ApiLogger;
import io.github.noonecanhearme.apimonitor.FlameGraphGenerator;
import io.github.noonecanhearme.apimonitor.annotation.EnableFlameGraph;
import io.github.noonecanhearme.apimonitor.entity.ApiLogEntity;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * API日志切面类
 * 负责拦截并记录API调用的详细信息，支持日志记录和火焰图生成功能
 */
@Aspect
@Order(0)  // 设置优先级，确保在其他切面之前执行
public class ApiLogAspect {

    private static final Logger logger = LoggerFactory.getLogger(ApiLogAspect.class);
    private final ApiLogger apiLogger;
    private final ApiMonitorProperties properties;
    private final FlameGraphGenerator flameGraphGenerator;

    @Autowired
    public ApiLogAspect(ApiLogger apiLogger, ApiMonitorProperties properties, FlameGraphGenerator flameGraphGenerator) {
        this.apiLogger = apiLogger;
        this.properties = properties;
        this.flameGraphGenerator = flameGraphGenerator;
    }

    /**
     * 定义切入点，拦截所有Controller层方法
     */
    @Pointcut("@within(org.springframework.stereotype.Controller) || @within(org.springframework.web.bind.annotation.RestController)")
    public void apiPointcut() {
    }

    /**
     * 环绕通知，记录API调用信息
     */
    @Around("apiPointcut()")
    public Object aroundAdvice(ProceedingJoinPoint joinPoint) throws Throwable {
        // 如果API监控未启用，直接执行原方法
        if (!properties.isEnabled()) {
            return joinPoint.proceed();
        }

        // 获取请求信息
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = attributes.getRequest();
        HttpServletResponse response = attributes.getResponse();

        // 检查是否需要忽略当前路径
        String requestUri = request.getRequestURI();
        for (String ignorePath : properties.getIgnorePaths()) {
            if (requestUri.contains(ignorePath) || requestUri.matches(ignorePath)) {
                return joinPoint.proceed();
            }
        }

        // 创建日志实体
        ApiLogEntity apiLog = new ApiLogEntity();
        apiLog.setId(UUID.randomUUID().toString());
        apiLog.setRequestId(UUID.randomUUID().toString());
        apiLog.setMethod(request.getMethod());
        apiLog.setUrl(requestUri);
        apiLog.setIp(getClientIp(request));
        apiLog.setUserAgent(request.getHeader("User-Agent"));
        apiLog.setStartTime(LocalDateTime.now());
        
        // 获取类名和方法名
        apiLog.setClassName(joinPoint.getTarget().getClass().getName());
        apiLog.setMethodName(joinPoint.getSignature().getName());
        
        // 记录查询参数
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (!parameterMap.isEmpty()) {
            apiLog.setQueryParams(convertParameterMapToString(parameterMap));
        }
        
        // 记录请求头信息
        if (properties.isLogHeaders()) {
            apiLog.setHeaders(extractRequestHeaders(request));
        }

        // 记录请求体
        if (properties.isLogRequestBody()) {
            try {
                // 获取请求体（这里简化处理，实际可能需要自定义过滤器获取完整请求体）
                String requestBody = extractRequestBody(request);
                // 限制请求体大小，避免日志过大
                if (StringUtils.hasText(requestBody) && requestBody.length() > properties.getRequestBodyMaxLength()) {
                    requestBody = requestBody.substring(0, properties.getRequestBodyMaxLength()) + "...(truncated)";
                }
                apiLog.setRequestBody(requestBody);
            } catch (Exception e) {
                apiLog.setRequestBody("[无法读取请求体: " + e.getMessage() + "]");
            }
        }

        Object result = null;
        String exceptionMsg = null;

        // 检查是否需要生成火焰图
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        EnableFlameGraph flameGraphAnnotation = method.getAnnotation(EnableFlameGraph.class);
        boolean generateFlameGraph = flameGraphAnnotation != null;
        boolean flameGraphStarted = false;

        try {
            // 如果需要生成火焰图，则启动火焰图生成
            if (generateFlameGraph && properties.getFlameGraph().isEnabled()) {
                try {
                    // 可以在这里处理注解参数，比如自定义采样时长
                    // 由于FlameGraphGenerator内部已经使用配置的采样时长，这里简化处理
                    flameGraphGenerator.startProfiling(apiLog.getRequestId());
                    flameGraphStarted = true;
                } catch (Exception e) {
                    logger.error("启动火焰图生成失败", e);
                    // 火焰图生成失败不应影响API的正常执行
                }
            }

            // 执行原方法
            long startTime = System.currentTimeMillis();
            result = joinPoint.proceed();
            long endTime = System.currentTimeMillis();
            
            // 计算执行时间
            apiLog.setExecuteTime(endTime - startTime);
            apiLog.setEndTime(LocalDateTime.now());

            // 记录响应状态码
            if (response != null) {
                apiLog.setStatusCode(response.getStatus());
            }

            // 记录响应体
            if (properties.isLogResponseBody() && result != null) {
                try {
                    String responseBody = result.toString();
                    // 限制响应体大小，避免日志过大
                    if (responseBody.length() > properties.getResponseBodyMaxLength()) {
                        responseBody = responseBody.substring(0, properties.getResponseBodyMaxLength()) + "...(truncated)";
                    }
                    apiLog.setResponseBody(responseBody);
                } catch (Exception e) {
                    apiLog.setResponseBody("[无法读取响应体: " + e.getMessage() + "]");
                }
            }
            
            // 记录响应头
            if (properties.isLogHeaders() && response != null) {
                apiLog.setResponseHeaders(extractResponseHeaders(response));
            }

            return result;
        } catch (Throwable throwable) {
            // 记录异常信息
            apiLog.setException(throwable.toString());
            apiLog.setExceptionMessage(throwable.getMessage());
            
            // 获取异常堆栈信息
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            apiLog.setExceptionStack(sw.toString());
            
            apiLog.setEndTime(LocalDateTime.now());
            if (response != null) {
                apiLog.setStatusCode(500);
            }
            throw throwable;
        } finally {
            // 停止火焰图生成
            if (flameGraphStarted) {
                try {
                    flameGraphGenerator.stopProfiling(apiLog.getRequestId());
                } catch (Exception e) {
                    logger.error("停止火焰图生成失败", e);
                    // 记录失败不应影响主流程
                }
            }

            // 异步记录日志，避免影响响应性能
            if (properties.isAsyncLogging()) {
                CompletableFuture.runAsync(() -> {
                    try {
                        apiLogger.log(apiLog);
                    } catch (Exception e) {
                        logger.error("异步记录API日志失败", e);
                    }
                });
            } else {
                try {
                    apiLogger.log(apiLog);
                } catch (Exception e) {
                    logger.error("同步记录API日志失败", e);
                }
            }
        }
    }

    /**
     * 获取客户端真实IP地址
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多个IP时取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 提取请求体信息
     */
    private String extractRequestBody(HttpServletRequest request) {
        try {
            // 如果请求已经被包装为CachedBodyHttpServletRequest，则直接获取缓存的请求体
            if (request instanceof io.github.noonecanhearme.apimonitor.filter.CachedBodyHttpServletRequest) {
                return ((io.github.noonecanhearme.apimonitor.filter.CachedBodyHttpServletRequest) request).getCachedBody();
            }
            return "[未包装的请求，无法读取请求体]";
        } catch (Exception e) {
            return "[读取请求体失败: " + e.getMessage() + "]";
        }
    }
    
    /**
     * 转换参数映射为字符串
     */
    private String convertParameterMapToString(Map<String, String[]> parameterMap) {
        return parameterMap.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + String.join(",", entry.getValue()))
                .collect(Collectors.joining("&"));
    }
    
    /**
     * 提取请求头信息
     */
    private String extractRequestHeaders(HttpServletRequest request) {
        Enumeration<String> headerNames = request.getHeaderNames();
        Map<String, String> headers = new LinkedHashMap<>();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            // 过滤敏感头信息
            if (!isSensitiveHeader(headerName)) {
                headers.put(headerName, request.getHeader(headerName));
            }
        }
        return headers.toString();
    }
    
    /**
     * 提取响应头信息
     */
    private String extractResponseHeaders(HttpServletResponse response) {
        Collection<String> headerNames = response.getHeaderNames();
        Map<String, String> headers = new LinkedHashMap<>();
        for (String headerName : headerNames) {
            headers.put(headerName, response.getHeader(headerName));
        }
        return headers.toString();
    }
    
    /**
     * 检查是否为敏感头信息
     * @param headerName 头信息名称
     * @return 是否为敏感头信息
     */
    private boolean isSensitiveHeader(String headerName) {
        if (StringUtils.isEmpty(headerName)) {
            return false;
        }
        
        String lowerHeader = headerName.toLowerCase();
        return lowerHeader.contains("authorization") || 
               lowerHeader.contains("token") || 
               lowerHeader.contains("secret") || 
               lowerHeader.contains("password") ||
               (properties.getSensitiveHeaders() != null && 
                properties.getSensitiveHeaders().contains(lowerHeader));
    }


}