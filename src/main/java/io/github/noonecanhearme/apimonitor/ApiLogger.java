package io.github.noonecanhearme.apimonitor;

import io.github.noonecanhearme.apimonitor.entity.ApiLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * API日志记录器类
 * 负责将API调用日志记录到文件或数据库，支持异步日志写入和多数据库类型
 */
public class ApiLogger implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(ApiLogger.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    private final ApiMonitorProperties properties;
    private final ConcurrentHashMap<String, DatabaseTableCreator> databaseTableCreators = new ConcurrentHashMap<>();
    private JdbcTemplate jdbcTemplate;
    private final ExecutorService logExecutor;
    
    /**
     * 构造函数
     * @param properties API监控配置属性
     */

    public ApiLogger(ApiMonitorProperties properties) {
        this.properties = properties;
        // 使用更合理的线程池配置
        this.logExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread thread = new Thread(r, "api-logger-worker");
                thread.setDaemon(true); // 设置为守护线程，避免阻止应用关闭
                return thread;
            }
        );
        initDatabaseTableCreators();
    }
    
    /**
     * 销毁方法，用于关闭线程池资源
     */
    @Override
    public void destroy() {
        shutdownExecutor();
    }
    
    /**
     * 安全关闭线程池
     */
    private void shutdownExecutor() {
        try {
            logExecutor.shutdown();
            if (!logExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                logExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logExecutor.shutdownNow();
            logger.error("线程池关闭时发生中断", e);
        }
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
     * @param apiLog API调用日志实体
     */
    public void log(ApiLogEntity apiLog) {
        if (apiLog == null) {
            logger.warn("尝试记录空的API日志");
            return;
        }
        
        try {
            if ("database".equalsIgnoreCase(properties.getLogType()) && 
                properties.getDatabase().isEnabled() && 
                jdbcTemplate != null) {
                saveToDatabase(apiLog);
            } else {
                saveToLogFile(apiLog);
            }
        } catch (Exception e) {
            logger.error("记录API日志时发生异常", e);
        }
    }

    /**
     * 将API日志保存到指定路径的日志文件
     * @param apiLog API调用日志实体
     */
    private void saveToLogFile(ApiLogEntity apiLog) {
        String logMessage = buildLogMessage(apiLog);
        
        // 检查是否配置了日志文件路径
        if (StringUtils.hasText(properties.getLogFilePath())) {
            handleFileLogging(properties.getLogFilePath(), logMessage, apiLog.getStatusCode());
        } else {
            // 未配置日志文件路径，使用默认日志输出
            logToLogger(logMessage, apiLog.getStatusCode());
        }
    }
    
    /**
     * 构建日志消息
     * @param apiLog API调用日志实体
     * @return 格式化的日志消息
     */
    private String buildLogMessage(ApiLogEntity apiLog) {
        StringBuilder logMessageBuilder = new StringBuilder();
        logMessageBuilder.append("API调用记录 [")
                .append(apiLog.getRequestId()).append("] - ")
                .append(apiLog.getMethod()).append(" ")
                .append(apiLog.getUrl()).append(" - ")
                .append(apiLog.getStatusCode()).append(" - ")
                .append(apiLog.getExecuteTime()).append("ms\n");

        // 记录客户端信息
        logMessageBuilder.append("客户端: IP=").append(apiLog.getIp())
                .append(", UA=").append(apiLog.getUserAgent()).append("\n");

        // 记录请求体
        if (properties.isLogRequestBody() && StringUtils.hasText(apiLog.getRequestBody())) {
            logMessageBuilder.append("请求体: ").append(apiLog.getRequestBody()).append("\n");
        }

        // 记录响应体
        if (properties.isLogResponseBody() && StringUtils.hasText(apiLog.getResponseBody())) {
            logMessageBuilder.append("响应体: ").append(apiLog.getResponseBody()).append("\n");
        }

        // 记录异常信息
        if (StringUtils.hasText(apiLog.getException())) {
            logMessageBuilder.append("异常信息: ").append(apiLog.getException()).append("\n");
        }
        
        return logMessageBuilder.toString();
    }
    
    /**
     * 处理文件日志记录
     * @param logFilePath 日志文件路径
     * @param logMessage 日志消息
     * @param statusCode HTTP状态码
     */
    private void handleFileLogging(String logFilePath, String logMessage, int statusCode) {
        File logDir = new File(logFilePath);
        
        // 确保日志目录存在
        if (!ensureDirectoryExists(logDir)) {
            logger.error("无法创建日志目录: {}", logFilePath);
            // 回退到默认日志输出
            logToLogger(logMessage, statusCode);
            return;
        }
        
        // 生成带日期的日志文件名
        String logFileName = "api-monitor-" + LocalDate.now().format(DATE_FORMATTER) + ".log";
        File logFile = new File(logDir, logFileName);
        
        String formattedLog = "[" + LocalDateTime.now().format(DATETIME_FORMATTER) + "] " + logMessage;
        
        // 异步写入文件
        logExecutor.execute(() -> {
            try {
                writeToFile(logFile, formattedLog);
            } catch (Exception e) {
                logger.error("异步写入日志文件失败", e);
                // 记录到默认日志器作为备份
                logger.info("备用日志记录: {}", formattedLog);
            }
        });
    }
    
    /**
     * 确保目录存在，如果不存在则创建
     * @param directory 目录文件对象
     * @return 是否成功创建或目录已存在
     */
    private boolean ensureDirectoryExists(File directory) {
        if (directory == null) {
            return false;
        }
        return directory.exists() || directory.mkdirs();
    }
    
    /**
     * 写入日志到文件
     * @param logFile 日志文件
     * @param logMessage 日志消息
     * @throws IOException 当文件写入失败时抛出
     */
    private void writeToFile(File logFile, String logMessage) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write(logMessage);
            writer.newLine();
        }
    }
    
    /**
     * 根据状态码记录日志到默认日志器
     * @param logMessage 日志消息
     * @param statusCode HTTP状态码
     */
    private void logToLogger(String logMessage, int statusCode) {
        if (statusCode >= 500) {
            logger.error(logMessage);
        } else if (statusCode >= 400) {
            logger.warn(logMessage);
        } else {
            logger.info(logMessage);
        }
    }

    /**
     * 保存日志到数据库
     * @param apiLog API调用日志实体
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
            // 如果数据库保存失败，尝试保存到日志文件作为备份
            try {
                saveToLogFile(apiLog);
            } catch (Exception fallbackException) {
                logger.error("备份到文件日志也失败: {}", fallbackException.getMessage(), fallbackException);
            }
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