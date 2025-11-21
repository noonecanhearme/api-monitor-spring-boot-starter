package io.github.noonecanhearme.apimonitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API Monitor Configuration Properties Class
 * Manages all configuration options for API monitoring, including log recording, database storage, and flame graph generation functions
 */
@ConfigurationProperties(prefix = "api.monitor")
public class ApiMonitorProperties {

    private static final Logger logger = LoggerFactory.getLogger(ApiMonitorProperties.class);
    
    /**
     * Whether to enable API monitoring
     */
    private boolean enabled = true;

    /**
     * Log recording method enumeration
     */
    public enum LogType {
        LOG, DATABASE
    }
    
    /**
     * Log recording method: log (default) or database
     */
    private String logType = LogType.LOG.name().toLowerCase();
    
    /**
     * Log file save path, default is api-monitor-logs subdirectory under system temporary directory
     */
    private String logFilePath = ensureTrailingSlash(System.getProperty("java.io.tmpdir")) + "api-monitor-logs";

    /**
     * Database configuration
     */
    private DatabaseConfig database = new DatabaseConfig();

    /**
     * Flame graph configuration
     */
    private FlameGraphConfig flameGraph = new FlameGraphConfig();

    /**
     * Whether to record request body
     */
    private boolean logRequestBody = true;

    /**
     * Whether to record response body
     */
    private boolean logResponseBody = true;

    /**
     * URL paths to ignore
     */
    private String[] ignorePaths = new String[]{};
    
    /**
     * Whether to record request and response headers
     */
    private boolean logHeaders = false;
    
    /**
     * Sensitive header information that needs to be filtered in logs
     */
    private Set<String> sensitiveHeaders = new HashSet<>(Arrays.asList("authorization", "token", "secret", "password"));
    
    /**
     * Whether to record logs asynchronously
     */
    private boolean asyncLogging = true;
    
    /**
     * Maximum request body length (bytes)
     */
    private int requestBodyMaxLength = 1024 * 10; // Default 10KB
    
    /**
     * Maximum response body length (bytes)
     */
    private int responseBodyMaxLength = 1024 * 10; // Default 10KB

    // getter and setter methods
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLogType() {
        return logType;
    }

    public void setLogType(String logType) {
        this.logType = logType;
    }

    public DatabaseConfig getDatabase() {
        return database;
    }

    public void setDatabase(DatabaseConfig database) {
        this.database = database;
    }

    public FlameGraphConfig getFlameGraph() {
        return flameGraph;
    }

    public void setFlameGraph(FlameGraphConfig flameGraph) {
        this.flameGraph = flameGraph;
    }

    public boolean isLogRequestBody() {
        return logRequestBody;
    }

    public void setLogRequestBody(boolean logRequestBody) {
        this.logRequestBody = logRequestBody;
    }

    public boolean isLogResponseBody() {
        return logResponseBody;
    }

    public void setLogResponseBody(boolean logResponseBody) {
        this.logResponseBody = logResponseBody;
    }

    public String[] getIgnorePaths() {
        return ignorePaths;
    }

    public void setIgnorePaths(String[] ignorePaths) {
        this.ignorePaths = ignorePaths;
    }
    
    public boolean isLogHeaders() {
        return logHeaders;
    }
    
    public void setLogHeaders(boolean logHeaders) {
        this.logHeaders = logHeaders;
    }
    
    public Set<String> getSensitiveHeaders() {
        return sensitiveHeaders;
    }
    
    public void setSensitiveHeaders(Set<String> sensitiveHeaders) {
        this.sensitiveHeaders = sensitiveHeaders;
    }
    
    public boolean isAsyncLogging() {
        return asyncLogging;
    }
    
    public void setAsyncLogging(boolean asyncLogging) {
        this.asyncLogging = asyncLogging;
    }
    
    public int getRequestBodyMaxLength() {
        return requestBodyMaxLength;
    }
    
    public void setRequestBodyMaxLength(int requestBodyMaxLength) {
        this.requestBodyMaxLength = requestBodyMaxLength;
    }
    
    public int getResponseBodyMaxLength() {
        return responseBodyMaxLength;
    }
    
    public void setResponseBodyMaxLength(int responseBodyMaxLength) {
        this.responseBodyMaxLength = responseBodyMaxLength;
    }
    
    /**
     * Get log file save path
     * @return Log file save path
     */
    public String getLogFilePath() {
        return logFilePath;
    }
    
    /**
     * Set log file save path
     * @param logFilePath Log file save path
     */
    public void setLogFilePath(String logFilePath) {
        this.logFilePath = ensureTrailingSlash(logFilePath);
    }
    
    /**
     * Ensure path ends with file separator
     * @param path Path string
     * @return Path ending with file separator
     */
    private String ensureTrailingSlash(String path) {
        if (!StringUtils.hasLength(path)) {
            return path;
        }
        
        String normalizedPath = path.replace('/', File.separatorChar).replace('\\', File.separatorChar);
        if (!normalizedPath.endsWith(File.separator)) {
            normalizedPath += File.separator;
        }
        return normalizedPath;
    }
    
    /**
     * Validate configuration validity
     */
    @PostConstruct
    public void validate() {
        // Validate log type
        if (!LogType.LOG.name().toLowerCase().equals(logType) && 
            !LogType.DATABASE.name().toLowerCase().equals(logType)) {
            throw new IllegalArgumentException("Invalid logType: " + logType + ", must be 'log' or 'database'");
        }
        
        // Validate sensitive headers set is not empty
        if (sensitiveHeaders == null) {
            sensitiveHeaders = new HashSet<>(Arrays.asList("authorization", "token", "secret", "password"));
        }
        
        // Convert sensitive headers to lowercase to ensure case-insensitive matching
        sensitiveHeaders = sensitiveHeaders.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        
        // Validate sampling configuration rationality
        if (flameGraph != null) {
            if (flameGraph.getSamplingRate() <= 0) {
                flameGraph.setSamplingRate(50);
            }
            if (flameGraph.getSamplingDuration() <= 0) {
                flameGraph.setSamplingDuration(1000);
            }
            if (flameGraph.getSamplingRate() >= flameGraph.getSamplingDuration()) {
                flameGraph.setSamplingRate(flameGraph.getSamplingDuration() / 2);
            }
            
            // Validate output format
            String format = flameGraph.getFormat().toLowerCase();
            if (!Arrays.asList("html", "svg", "json").contains(format)) {
                flameGraph.setFormat("html");
                logger.warn("Invalid flame graph format: {}. Using default: html", format);
            }
            
            // Validate event type
            if (flameGraph.getEventType() == null) {
                flameGraph.setEventType(FlameGraphEventType.CPU);
                logger.warn("Event type not specified. Using default: CPU");
            }
            
            // Ensure flame graph path is correct
            flameGraph.setSavePath(ensureTrailingSlash(flameGraph.getSavePath()));
        }
    }

    /**
     * Database configuration inner class
     */
    public static class DatabaseConfig {
        /**
         * Whether to enable database storage
         */
        private boolean enabled = false;

        /**
         * Database table name prefix
         */
        private String tablePrefix = "api_";

        /**
         * Whether to automatically create tables
         */
        private boolean autoCreateTable = true;

        // getter and setter methods
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTablePrefix() {
            return tablePrefix;
        }

        public void setTablePrefix(String tablePrefix) {
            this.tablePrefix = tablePrefix;
        }

        public boolean isAutoCreateTable() {
            return autoCreateTable;
        }

        public void setAutoCreateTable(boolean autoCreateTable) {
            this.autoCreateTable = autoCreateTable;
        }
    }

    /**
     * Flame graph analysis event type enumeration
     */
    public enum FlameGraphEventType {
        /**
         * CPU usage analysis
         */
        CPU,
        
        /**
         * Memory allocation analysis
         */
        ALLOC,
        
        /**
         * Lock contention analysis
         */
        LOCK,
        
        /**
         * Cache miss analysis
         */
        CACHE_MISSES
    }

    /**
     * Flame graph configuration inner class
     */
    public static class FlameGraphConfig {
        /**
         * Whether to enable flame graph generation
         */
        private boolean enabled = false;

        /**
         * Flame graph save path
         */
        private String savePath = "./flamegraphs";

        /**
         * Flame graph sampling duration (milliseconds)
         */
        private int samplingDuration = 1000;
        
        /**
         * Flame graph sampling rate (milliseconds)
         */
        private int samplingRate = 50;
        
        /**
         * Flame graph output format, supports html, svg, json, default is html
         */
        private String format = "html";
        
        /**
         * Flame graph analysis event type, default is CPU analysis
         */
        private FlameGraphEventType eventType = FlameGraphEventType.CPU;

        // getter and setter methods
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSavePath() {
            return savePath;
        }

        public void setSavePath(String savePath) {
            this.savePath = savePath;
        }

        public int getSamplingDuration() {
            return samplingDuration;
        }

        public void setSamplingDuration(int samplingDuration) {
            this.samplingDuration = samplingDuration;
        }
        
        public int getSamplingRate() {
            return samplingRate;
        }
        
        public void setSamplingRate(int samplingRate) {
            this.samplingRate = samplingRate;
        }
        
        public String getFormat() {
            return format;
        }
        
        public void setFormat(String format) {
            this.format = format;
        }
        
        public FlameGraphEventType getEventType() {
            return eventType;
        }
        
        public void setEventType(FlameGraphEventType eventType) {
            this.eventType = eventType;
        }
    }
}