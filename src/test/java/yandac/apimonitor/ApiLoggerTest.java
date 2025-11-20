package yandac.apimonitor;

import yandac.apimonitor.entity.ApiLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiLoggerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ApiLogger apiLogger;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        apiLogger = new ApiLogger();
        apiLogger.setLogType("log"); // 使用日志文件模式
        apiLogger.setJdbcTemplate(jdbcTemplate); // 注入模拟的JdbcTemplate，但模式是log，不会使用到
    }

    @Test
    void testLogApiCallWithLogType() {
        // 创建测试数据
        ApiLogEntity logEntity = new ApiLogEntity();
        logEntity.setRequestId("test-id");
        logEntity.setMethod("GET");
        logEntity.setUrl("/api/test");
        
        // 执行被测试方法
        apiLogger.logApiCall(logEntity);
        
        // 在日志模式下，不应该调用JdbcTemplate的update方法
        verify(jdbcTemplate, never()).update(anyString(), any());
    }

    @Test
    void testLogApiCallWithDatabaseType() {
        // 设置为数据库模式
        apiLogger.setLogType("database");
        apiLogger.setDatabaseEnabled(true);
        apiLogger.setAutoCreateTable(false); // 禁用自动创建表
        
        // 创建测试数据
        ApiLogEntity logEntity = new ApiLogEntity();
        logEntity.setRequestId("test-id");
        logEntity.setMethod("GET");
        logEntity.setUrl("/api/test");
        logEntity.setIp("127.0.0.1");
        logEntity.setRequestBody("{}");
        logEntity.setResponseBody("{\"result\":\"success\"}");
        logEntity.setStatus(200);
        logEntity.setExecutionTime(100L);
        logEntity.setException(null);
        
        // 执行被测试方法
        apiLogger.logApiCall(logEntity);
        
        // 验证结果
        verify(jdbcTemplate, times(1)).update(
            anyString(), 
            eq("test-id"), 
            eq("GET"), 
            eq("/api/test"),
            eq("127.0.0.1"),
            eq("{}"),
            eq("{\"result\":\"success\"}"),
            eq(200),
            anyLong(),
            eq(null)
        );
    }

    @Test
    void testCreateTable() {
        // 设置为数据库模式
        apiLogger.setDatabaseEnabled(true);
        apiLogger.setAutoCreateTable(true);
        apiLogger.setTablePrefix("api_");
        
        // 模拟JdbcTemplate，当执行表存在查询时返回0，表示表不存在
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        
        // 执行被测试方法
        apiLogger.createTableIfNotExists();
        
        // 验证结果 - 应该执行创建表的SQL
        verify(jdbcTemplate, times(1)).update(anyString());
    }
}