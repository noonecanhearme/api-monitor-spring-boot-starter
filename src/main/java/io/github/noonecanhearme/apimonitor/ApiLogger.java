package io.github.noonecanhearme.apimonitor;

import io.github.noonecanhearme.apimonitor.entity.ApiLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API日志记录器类
 */
public class ApiLogger {

    private static final Logger logger = LoggerFactory.getLogger(ApiLogger.class);
    private final ApiMonitorProperties properties;
    private final Map<String, DatabaseTableCreator> databaseTableCreators = new ConcurrentHashMap<>();
    private JdbcTemplate jdbcTemplate;

    public ApiLogger(ApiMonitorProperties properties) {
        this.properties = properties;
        initDatabaseTableCreators();
    }

    /**
     * 设置JdbcTemplate（延迟注入，避免循环依赖）
     */
    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // 初始化数据库表
        if (properties.getDatabase().isEnabled() && properties.getDatabase().isAutoCreateTable()) {
            createDatabaseTable();
        }
    }

    /**
     * 记录API调用日志
     */
    public void log(ApiLogEntity apiLog) {
        if (properties.getLogType().equalsIgnoreCase("database") && properties.getDatabase().isEnabled() && jdbcTemplate != null) {
            saveToDatabase(apiLog);
        } else {
            saveToLogFile(apiLog);
        }
    }

    /**
     * 保存日志到文件
     */
    private void saveToLogFile(ApiLogEntity apiLog) {
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("API调用记录 [")
                .append(apiLog.getRequestId()).append("] - ")
                .append(apiLog.getMethod()).append(" ")
                .append(apiLog.getUrl()).append(" - ")
                .append(apiLog.getStatusCode()).append(" - ")
                .append(apiLog.getExecuteTime()).append("ms\n");

        // 记录客户端信息
        logMessage.append("客户端: IP=").append(apiLog.getIp())
                .append(", UA=").append(apiLog.getUserAgent()).append("\n");

        // 记录请求体
        if (properties.isLogRequestBody() && StringUtils.hasText(apiLog.getRequestBody())) {
            logMessage.append("请求体: ").append(apiLog.getRequestBody()).append("\n");
        }

        // 记录响应体
        if (properties.isLogResponseBody() && StringUtils.hasText(apiLog.getResponseBody())) {
            logMessage.append("响应体: ").append(apiLog.getResponseBody()).append("\n");
        }

        // 记录异常信息
        if (StringUtils.hasText(apiLog.getException())) {
            logMessage.append("异常信息: ").append(apiLog.getException()).append("\n");
        }

        // 根据状态码决定日志级别
        if (apiLog.getStatusCode() >= 500) {
            logger.error(logMessage.toString());
        } else if (apiLog.getStatusCode() >= 400) {
            logger.warn(logMessage.toString());
        } else {
            logger.info(logMessage.toString());
        }
    }

    /**
     * 保存日志到数据库
     */
    private void saveToDatabase(ApiLogEntity apiLog) {
        try {
            String tableName = properties.getDatabase().getTablePrefix() + "log";
            String sql = "INSERT INTO " + tableName + " (id, request_id, method, url, ip, user_agent, request_body, " +
                    "response_body, status_code, execute_time, start_time, end_time, exception, class_name, method_name, " +
                    "query_params, headers, response_headers, exception_message, exception_stack) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql,
                    apiLog.getId(),
                    apiLog.getRequestId(),
                    apiLog.getMethod(),
                    apiLog.getUrl(),
                    apiLog.getIp(),
                    apiLog.getUserAgent(),
                    apiLog.getRequestBody(),
                    apiLog.getResponseBody(),
                    apiLog.getStatusCode(),
                    apiLog.getExecuteTime(),
                    apiLog.getStartTime(),
                    apiLog.getEndTime(),
                    apiLog.getException(),
                    apiLog.getClassName(),
                    apiLog.getMethodName(),
                    apiLog.getQueryParams(),
                    apiLog.getHeaders(),
                    apiLog.getResponseHeaders(),
                    apiLog.getExceptionMessage(),
                    apiLog.getExceptionStack()
            );
        } catch (Exception e) {
            logger.error("保存API日志到数据库失败: {}", e.getMessage(), e);
            // 如果数据库保存失败，尝试保存到日志文件
            saveToLogFile(apiLog);
        }
    }

    /**
     * 创建数据库表
     */
    private void createDatabaseTable() {
        try {
            if (jdbcTemplate == null) {
                logger.warn("JdbcTemplate未初始化，无法创建数据库表");
                return;
            }

            // 获取数据库类型
            String databaseType = getDatabaseType();
            DatabaseTableCreator tableCreator = databaseTableCreators.get(databaseType);

            if (tableCreator != null) {
                tableCreator.createTable(jdbcTemplate, properties.getDatabase().getTablePrefix() + "log");
                logger.info("API日志表创建成功");
            } else {
                logger.warn("不支持的数据库类型: {}", databaseType);
            }
        } catch (Exception e) {
            logger.error("创建API日志表失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取数据库类型
     */
    private String getDatabaseType() {
        try {
            String productName = jdbcTemplate.getDataSource().getConnection().getMetaData().getDatabaseProductName().toLowerCase();
            if (productName.contains("mysql")) {
                return "mysql";
            } else if (productName.contains("postgresql")) {
                return "postgresql";
            } else if (productName.contains("sql server")) {
                return "sqlserver";
            } else if (productName.contains("oracle")) {
                return "oracle";
            }
            return "unknown";
        } catch (Exception e) {
            logger.error("获取数据库类型失败: {}", e.getMessage(), e);
            return "unknown";
        }
    }

    /**
     * 初始化数据库表创建器
     */
    private void initDatabaseTableCreators() {
        // MySQL表创建器
        databaseTableCreators.put("mysql", (jdbcTemplate, tableName) -> {
            String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    "id VARCHAR(36) PRIMARY KEY, " +
                    "request_id VARCHAR(36) NOT NULL, " +
                    "method VARCHAR(10) NOT NULL, " +
                    "url VARCHAR(500) NOT NULL, " +
                    "ip VARCHAR(50), " +
                    "user_agent VARCHAR(1000), " +
                    "request_body LONGTEXT, " +
                    "response_body LONGTEXT, " +
                    "query_params TEXT, " +
                    "headers LONGTEXT, " +
                    "response_headers LONGTEXT, " +
                    "status_code INT NOT NULL, " +
                    "execute_time BIGINT NOT NULL, " +
                    "start_time DATETIME NOT NULL, " +
                    "end_time DATETIME NOT NULL, " +
                    "exception TEXT, " +
                    "exception_message TEXT, " +
                    "exception_stack LONGTEXT, " +
                    "class_name VARCHAR(255), " +
                    "method_name VARCHAR(255), " +
                    "INDEX idx_request_id (request_id), " +
                    "INDEX idx_url (url), " +
                    "INDEX idx_start_time (start_time)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
            jdbcTemplate.execute(sql);
        });

        // PostgreSQL表创建器
        databaseTableCreators.put("postgresql", (jdbcTemplate, tableName) -> {
            String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    "id VARCHAR(36) PRIMARY KEY, " +
                    "request_id VARCHAR(36) NOT NULL, " +
                    "method VARCHAR(10) NOT NULL, " +
                    "url VARCHAR(500) NOT NULL, " +
                    "ip VARCHAR(50), " +
                    "user_agent VARCHAR(1000), " +
                    "request_body TEXT, " +
                    "response_body TEXT, " +
                    "query_params TEXT, " +
                    "headers TEXT, " +
                    "response_headers TEXT, " +
                    "status_code INT NOT NULL, " +
                    "execute_time BIGINT NOT NULL, " +
                    "start_time TIMESTAMP NOT NULL, " +
                    "end_time TIMESTAMP NOT NULL, " +
                    "exception TEXT, " +
                    "exception_message TEXT, " +
                    "exception_stack TEXT, " +
                    "class_name VARCHAR(255), " +
                    "method_name VARCHAR(255)" +
                    ")";
            jdbcTemplate.execute(sql);
            // 创建索引
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_request_id ON " + tableName + "(request_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_url ON " + tableName + "(url)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_start_time ON " + tableName + "(start_time)");
        });

        // SQL Server表创建器
        databaseTableCreators.put("sqlserver", (jdbcTemplate, tableName) -> {
            String sql = "IF NOT EXISTS (SELECT * FROM sysobjects WHERE name='" + tableName + "' AND xtype='U') " +
                    "CREATE TABLE " + tableName + " (" +
                    "id VARCHAR(36) PRIMARY KEY, " +
                    "request_id VARCHAR(36) NOT NULL, " +
                    "method VARCHAR(10) NOT NULL, " +
                    "url VARCHAR(500) NOT NULL, " +
                    "ip VARCHAR(50), " +
                    "user_agent VARCHAR(1000), " +
                    "request_body NVARCHAR(MAX), " +
                    "response_body NVARCHAR(MAX), " +
                    "query_params NVARCHAR(MAX), " +
                    "headers NVARCHAR(MAX), " +
                    "response_headers NVARCHAR(MAX), " +
                    "status_code INT NOT NULL, " +
                    "execute_time BIGINT NOT NULL, " +
                    "start_time DATETIME NOT NULL, " +
                    "end_time DATETIME NOT NULL, " +
                    "exception NVARCHAR(MAX), " +
                    "exception_message NVARCHAR(MAX), " +
                    "exception_stack NVARCHAR(MAX), " +
                    "class_name VARCHAR(255), " +
                    "method_name VARCHAR(255)" +
                    ")";
            jdbcTemplate.execute(sql);
            // 创建索引
            jdbcTemplate.execute("IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name='idx_" + tableName + "_request_id') " +
                    "CREATE INDEX idx_" + tableName + "_request_id ON " + tableName + "(request_id)");
        });
    }

    /**
     * 数据库表创建器接口
     */
    @FunctionalInterface
    private interface DatabaseTableCreator {
        void createTable(JdbcTemplate jdbcTemplate, String tableName);
    }
}