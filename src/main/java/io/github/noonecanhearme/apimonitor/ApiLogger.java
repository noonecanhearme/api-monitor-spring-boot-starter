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
 * API Logger Class
 * Responsible for recording API call logs to files or databases, supporting asynchronous log writing and multiple database types
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
     * Constructor
     * @param properties API monitoring configuration properties
     */

    public ApiLogger(ApiMonitorProperties properties) {
        this.properties = properties;
        // Use more reasonable thread pool configuration
        this.logExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread thread = new Thread(r, "api-logger-worker");
                thread.setDaemon(true); // Set as daemon thread to avoid preventing application shutdown
                return thread;
            }
        );
        initDatabaseTableCreators();
    }
    
    /**
     * Destroy method for shutting down thread pool resources
     */
    @Override
    public void destroy() {
        shutdownExecutor();
    }
    
    /**
     * Safely shutdown the thread pool
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
            logger.error("Thread pool shutdown interrupted", e);
        }
    }

    /**
     * Set JdbcTemplate (delayed injection to avoid circular dependencies)
     */
    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // Initialize database table
        if (properties.getDatabase().isEnabled() && properties.getDatabase().isAutoCreateTable()) {
            createDatabaseTable();
        }
    }

    /**
     * Record API call log
     * @param apiLog API call log entity
     */
    public void log(ApiLogEntity apiLog) {
        if (apiLog == null) {
            logger.warn("Attempting to record empty API log");
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
            logger.error("Exception occurred while recording API log", e);
        }
    }

    /**
     * Save API log to specified log file path
     * @param apiLog API call log entity
     */
    private void saveToLogFile(ApiLogEntity apiLog) {
        String logMessage = buildLogMessage(apiLog);
        
        // Check if log file path is configured
        if (StringUtils.hasText(properties.getLogFilePath())) {
            handleFileLogging(properties.getLogFilePath(), logMessage, apiLog.getStatusCode());
        } else {
            // No log file path configured, using default log output
            logToLogger(logMessage, apiLog.getStatusCode());
        }
    }
    
    /**
     * Build log message
     * @param apiLog API call log entity
     * @return Formatted log message
     */
    private String buildLogMessage(ApiLogEntity apiLog) {
        StringBuilder logMessageBuilder = new StringBuilder();
        logMessageBuilder.append("API call record [")
                .append(apiLog.getRequestId()).append("] - ")
                .append(apiLog.getMethod()).append(" ")
                .append(apiLog.getUrl()).append(" - ")
                .append(apiLog.getStatusCode()).append(" - ")
                .append(apiLog.getExecuteTime()).append("ms\n");

        // Record client information
        logMessageBuilder.append("Client: IP=").append(apiLog.getIp())
                .append(", UA=").append(apiLog.getUserAgent()).append("\n");

        // Record request body
        if (properties.isLogRequestBody() && StringUtils.hasText(apiLog.getRequestBody())) {
            logMessageBuilder.append("Request body: ").append(apiLog.getRequestBody()).append("\n");
        }

        // Record response body
        if (properties.isLogResponseBody() && StringUtils.hasText(apiLog.getResponseBody())) {
            logMessageBuilder.append("Response body: ").append(apiLog.getResponseBody()).append("\n");
        }

        // Record exception information
        if (StringUtils.hasText(apiLog.getException())) {
            logMessageBuilder.append("Exception: ").append(apiLog.getException()).append("\n");
        }
        
        return logMessageBuilder.toString();
    }
    
    /**
     * Handle file logging
     * @param logFilePath Log file path
     * @param logMessage Log message
     * @param statusCode HTTP status code
     */
    private void handleFileLogging(String logFilePath, String logMessage, int statusCode) {
        File logDir = new File(logFilePath);
        
        // Ensure log directory exists
        if (!ensureDirectoryExists(logDir)) {
            logger.error("Failed to create log directory: {}", logFilePath);
            // Fallback to default log output
            logToLogger(logMessage, statusCode);
            return;
        }
        
        // Generate log file name with date
        String logFileName = "api-monitor-" + LocalDate.now().format(DATE_FORMATTER) + ".log";
        File logFile = new File(logDir, logFileName);
        
        String formattedLog = "[" + LocalDateTime.now().format(DATETIME_FORMATTER) + "] " + logMessage;
        
        // Asynchronous file writing
        logExecutor.execute(() -> {
            try {
                writeToFile(logFile, formattedLog);
            } catch (Exception e) {
                logger.error("Failed to write log file asynchronously", e);
                // Log to default logger as backup
                logger.info("Fallback log record: {}", formattedLog);
            }
        });
    }
    
    /**
     * Ensure directory exists, create if not
     * @param directory Directory file object
     * @return Whether directory was successfully created or already exists
     */
    private boolean ensureDirectoryExists(File directory) {
        if (directory == null) {
            return false;
        }
        return directory.exists() || directory.mkdirs();
    }
    
    /**
     * Write log to file
     * @param logFile Log file
     * @param logMessage Log message
     * @throws IOException Thrown when file writing fails
     */
    private void writeToFile(File logFile, String logMessage) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write(logMessage);
            writer.newLine();
        }
    }
    
    /**
     * Log to default logger based on status code
     * @param logMessage Log message
     * @param statusCode HTTP status code
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
     * Save log to database
     * @param apiLog API call log entity
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
            logger.error("Failed to save API log to database: {}", e.getMessage(), e);
            // If database saving fails, try to save to log file as backup
            try {
                saveToLogFile(apiLog);
            } catch (Exception fallbackException) {
                logger.error("Fallback to file logging also failed: {}", fallbackException.getMessage(), fallbackException);
            }
        }
    }

    /**
     * Create database table
     */
    private void createDatabaseTable() {
        try {
            if (jdbcTemplate == null) {
                logger.warn("JdbcTemplate not initialized, cannot create database table");
                return;
            }

            // Get database type
            String databaseType = getDatabaseType();
            DatabaseTableCreator tableCreator = databaseTableCreators.get(databaseType);

            if (tableCreator != null) {
                tableCreator.createTable(jdbcTemplate, properties.getDatabase().getTablePrefix() + "log");
                logger.info("API log table created successfully");
            } else {
                logger.warn("Unsupported database type: {}", databaseType);
            }
        } catch (Exception e) {
            logger.error("Failed to create API log table: {}", e.getMessage(), e);
        }
    }

    /**
     * Get database type
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
            logger.error("Failed to get database type: {}", e.getMessage(), e);
            return "unknown";
        }
    }

    /**
     * Initialize database table creators
     */
    private void initDatabaseTableCreators() {
        // MySQL table creator
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

        // PostgreSQL table creator
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
            // Create index
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_request_id ON " + tableName + "(request_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_url ON " + tableName + "(url)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_start_time ON " + tableName + "(start_time)");
        });

        // SQL Server table creator
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
            // Create index
            jdbcTemplate.execute("IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name='idx_" + tableName + "_request_id') " +
                    "CREATE INDEX idx_" + tableName + "_request_id ON " + tableName + "(request_id)");
            jdbcTemplate.execute("IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name='idx_" + tableName + "_url') " +
                    "CREATE INDEX idx_" + tableName + "_url ON " + tableName + "(url)");
            jdbcTemplate.execute("IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name='idx_" + tableName + "_start_time') " +
                    "CREATE INDEX idx_" + tableName + "_start_time ON " + tableName + "(start_time)");
        });
        
        // Oracle table creator
        databaseTableCreators.put("oracle", (jdbcTemplate, tableName) -> {
            // Check if table exists using Oracle's all_tables
            boolean tableExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM all_tables WHERE table_name = UPPER(?)",
                new Object[]{tableName},
                Integer.class
            ) > 0;
            
            if (!tableExists) {
                String sql = "CREATE TABLE " + tableName + " (" +
                        "id VARCHAR2(36) PRIMARY KEY, " +
                        "request_id VARCHAR2(36) NOT NULL, " +
                        "method VARCHAR2(10) NOT NULL, " +
                        "url VARCHAR2(500) NOT NULL, " +
                        "ip VARCHAR2(50), " +
                        "user_agent VARCHAR2(1000), " +
                        "request_body CLOB, " +
                        "response_body CLOB, " +
                        "query_params CLOB, " +
                        "headers CLOB, " +
                        "response_headers CLOB, " +
                        "status_code NUMBER(10) NOT NULL, " +
                        "execute_time NUMBER(19) NOT NULL, " +
                        "start_time TIMESTAMP NOT NULL, " +
                        "end_time TIMESTAMP NOT NULL, " +
                        "exception CLOB, " +
                        "exception_message CLOB, " +
                        "exception_stack CLOB, " +
                        "class_name VARCHAR2(255), " +
                        "method_name VARCHAR2(255)" +
                        ")";
                jdbcTemplate.execute(sql);
                
                // Create indexes
                jdbcTemplate.execute("CREATE INDEX idx_" + tableName + "_request_id ON " + tableName + "(request_id)");
                jdbcTemplate.execute("CREATE INDEX idx_" + tableName + "_url ON " + tableName + "(url)");
                jdbcTemplate.execute("CREATE INDEX idx_" + tableName + "_start_time ON " + tableName + "(start_time)");
            }
        });
    }

    /**
     * Database table creator interface
     */
    @FunctionalInterface
    private interface DatabaseTableCreator {
        void createTable(JdbcTemplate jdbcTemplate, String tableName);
    }
}