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
 * API Log Aspect Class
 * Responsible for intercepting and recording detailed information about API calls, supporting log recording and flame graph generation functions
 */
@Aspect
@Order(0)  // Set priority to ensure execution before other aspects
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
     * Define pointcut to intercept all Controller layer methods
     */
    @Pointcut("@within(org.springframework.stereotype.Controller) || @within(org.springframework.web.bind.annotation.RestController)")
    public void apiPointcut() {
    }

    /**
     * Around advice to record API call information
     */
    @Around("apiPointcut()")
    public Object aroundAdvice(ProceedingJoinPoint joinPoint) throws Throwable {
        // If API monitoring is not enabled, execute the original method directly
        if (!properties.isEnabled()) {
            return joinPoint.proceed();
        }

        // Get request information
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = attributes.getRequest();
        HttpServletResponse response = attributes.getResponse();

        // Check if current path needs to be ignored
        String requestUri = request.getRequestURI();
        for (String ignorePath : properties.getIgnorePaths()) {
            if (requestUri.contains(ignorePath) || requestUri.matches(ignorePath)) {
                return joinPoint.proceed();
            }
        }

        // Create log entity
        ApiLogEntity apiLog = new ApiLogEntity();
        apiLog.setId(UUID.randomUUID().toString());
        apiLog.setRequestId(UUID.randomUUID().toString());
        apiLog.setMethod(request.getMethod());
        apiLog.setUrl(requestUri);
        apiLog.setIp(getClientIp(request));
        apiLog.setUserAgent(request.getHeader("User-Agent"));
        apiLog.setStartTime(LocalDateTime.now());
        
        // Get class name and method name
        apiLog.setClassName(joinPoint.getTarget().getClass().getName());
        apiLog.setMethodName(joinPoint.getSignature().getName());
        
        // Record query parameters
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (!parameterMap.isEmpty()) {
            apiLog.setQueryParams(convertParameterMapToString(parameterMap));
        }
        
        // Record request header information
        if (properties.isLogHeaders()) {
            apiLog.setHeaders(extractRequestHeaders(request));
        }

        // Record request body
        if (properties.isLogRequestBody()) {
            try {
                // Get request body (simplified here, actual implementation may need custom filter to get complete request body)
                String requestBody = extractRequestBody(request);
                // Limit request body size to avoid large logs
                if (StringUtils.hasText(requestBody) && requestBody.length() > properties.getRequestBodyMaxLength()) {
                    requestBody = requestBody.substring(0, properties.getRequestBodyMaxLength()) + "...(truncated)";
                }
                apiLog.setRequestBody(requestBody);
            } catch (Exception e) {
                apiLog.setRequestBody("[Failed to read request body: " + e.getMessage() + "]");
            }
        }

        Object result = null;
        String exceptionMsg = null;

        // Check if flame graph generation is needed
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        EnableFlameGraph flameGraphAnnotation = method.getAnnotation(EnableFlameGraph.class);
        boolean generateFlameGraph = flameGraphAnnotation != null;
        boolean flameGraphStarted = false;

        try {
            // If flame graph generation is needed, start it
            if (generateFlameGraph && properties.getFlameGraph().isEnabled()) {
                try {
                    // Can handle annotation parameters here, such as custom sampling duration
                    // Simplified processing here as FlameGraphGenerator already uses configured sampling duration
                    flameGraphGenerator.startProfiling(apiLog.getRequestId());
                    flameGraphStarted = true;
                } catch (Exception e) {
                    logger.error("Failed to start flame graph generation", e);
                    // Flame graph generation failure should not affect normal API execution
                }
            }

            // Execute original method
            long startTime = System.currentTimeMillis();
            result = joinPoint.proceed();
            long endTime = System.currentTimeMillis();
            
            // Calculate execution time
            apiLog.setExecuteTime(endTime - startTime);
            apiLog.setEndTime(LocalDateTime.now());

            // Record response status code
            if (response != null) {
                apiLog.setStatusCode(response.getStatus());
            }

            // Record response body
            if (properties.isLogResponseBody() && result != null) {
                try {
                    String responseBody = result.toString();
                    // Limit response body size to avoid large logs
                    if (responseBody.length() > properties.getResponseBodyMaxLength()) {
                        responseBody = responseBody.substring(0, properties.getResponseBodyMaxLength()) + "...(truncated)";
                    }
                    apiLog.setResponseBody(responseBody);
                } catch (Exception e) {
                    apiLog.setResponseBody("[Failed to read response body: " + e.getMessage() + "]");
                }
            }
            
            // Record response headers
            if (properties.isLogHeaders() && response != null) {
                apiLog.setResponseHeaders(extractResponseHeaders(response));
            }

            return result;
        } catch (Throwable throwable) {
            // Record exception information
            apiLog.setException(throwable.toString());
            apiLog.setExceptionMessage(throwable.getMessage());
            
            // Get exception stack trace
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
            // Stop flame graph generation
            if (flameGraphStarted) {
                try {
                    flameGraphGenerator.stopProfiling(apiLog.getRequestId());
                } catch (Exception e) {
                    logger.error("Failed to stop flame graph generation", e);
                    // Logging failure should not affect the main process
                }
            }

            // Asynchronously record logs to avoid affecting response performance
            if (properties.isAsyncLogging()) {
                CompletableFuture.runAsync(() -> {
                    try {
                        apiLogger.log(apiLog);
                    } catch (Exception e) {
                        logger.error("Failed to record API log asynchronously", e);
                    }
                });
            } else {
                try {
                    apiLogger.log(apiLog);
                } catch (Exception e) {
                    logger.error("Failed to record API log synchronously", e);
                }
            }
        }
    }

    /**
     * Get client's real IP address
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
        // Take the first one when there are multiple IPs
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * Extract request body information
     */
    private String extractRequestBody(HttpServletRequest request) {
        try {
            // If the request has been wrapped as CachedBodyHttpServletRequest, directly get the cached request body
            if (request instanceof io.github.noonecanhearme.apimonitor.filter.CachedBodyHttpServletRequest) {
                return ((io.github.noonecanhearme.apimonitor.filter.CachedBodyHttpServletRequest) request).getCachedBody();
            }
            return "[Unwrapped request, cannot read request body]";
        } catch (Exception e) {
            return "[Failed to read request body: " + e.getMessage() + "]";
        }
    }
    
    /**
     * Convert parameter map to string
     */
    private String convertParameterMapToString(Map<String, String[]> parameterMap) {
        return parameterMap.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + String.join(",", entry.getValue()))
                .collect(Collectors.joining("&"));
    }
    
    /**
     * Extract request header information
     */
    private String extractRequestHeaders(HttpServletRequest request) {
        Enumeration<String> headerNames = request.getHeaderNames();
        Map<String, String> headers = new LinkedHashMap<>();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            // Filter sensitive header information
            if (!isSensitiveHeader(headerName)) {
                headers.put(headerName, request.getHeader(headerName));
            }
        }
        return headers.toString();
    }
    
    /**
     * Extract response header information
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
     * Check if it is sensitive header information
     * @param headerName Header name
     * @return Whether it is sensitive header information
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