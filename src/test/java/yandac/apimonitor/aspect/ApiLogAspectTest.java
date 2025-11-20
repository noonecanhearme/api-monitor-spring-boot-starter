package yandac.apimonitor.aspect;

import yandac.apimonitor.ApiLogger;
import yandac.apimonitor.FlameGraphGenerator;
import yandac.apimonitor.ApiMonitorProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiLogAspectTest {

    @Mock
    private ApiLogger apiLogger;

    @Mock
    private FlameGraphGenerator flameGraphGenerator;

    @Mock
    private ProceedingJoinPoint proceedingJoinPoint;

    @InjectMocks
    private ApiLogAspect apiLogAspect;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // 设置模拟的HTTP请求和响应
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/test");
        request.setRemoteAddr("127.0.0.1");
        
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        
        ServletRequestAttributes attributes = new ServletRequestAttributes(request, response);
        RequestContextHolder.setRequestAttributes(attributes);
    }

    @Test
    void testAroundApiCall() throws Throwable {
        // 模拟方法执行返回值
        when(proceedingJoinPoint.proceed()).thenReturn("success");
        
        // 执行被测试方法
        apiLogAspect.aroundApiCall(proceedingJoinPoint);
        
        // 验证结果
        verify(proceedingJoinPoint, times(1)).proceed();
        verify(apiLogger, times(1)).logApiCall(any());
    }

    @Test
    void testAroundApiCallWithException() throws Throwable {
        // 模拟方法执行抛出异常
        when(proceedingJoinPoint.proceed()).thenThrow(new RuntimeException("Test exception"));
        
        try {
            // 执行被测试方法
            apiLogAspect.aroundApiCall(proceedingJoinPoint);
        } catch (Exception e) {
            // 验证结果
            verify(proceedingJoinPoint, times(1)).proceed();
            verify(apiLogger, times(1)).logApiCall(any());
        }
    }

    @Test
    void testExtractRequestInfo() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/test");
        request.setRemoteAddr("127.0.0.1");
        
        ServletRequestAttributes attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        
        // 调用被测试方法
        String result = apiLogAspect.extractRequestInfo(proceedingJoinPoint);
        
        // 验证结果包含必要信息
        assert result.contains("POST");
        assert result.contains("/api/test");
        assert result.contains("127.0.0.1");
    }
}