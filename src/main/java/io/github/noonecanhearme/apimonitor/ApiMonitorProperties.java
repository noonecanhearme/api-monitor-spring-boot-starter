package io.github.noonecanhearme.apimonitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API监控配置属性类
 * 管理API监控的所有配置选项，包括日志记录、数据库存储和火焰图生成等功能
 */
@ConfigurationProperties(prefix = "api.monitor")
public class ApiMonitorProperties {

    /**
     * 是否启用API监控
     */
    private boolean enabled = true;

    /**
     * 日志记录方式枚举
     */
    public enum LogType {
        LOG, DATABASE
    }
    
    /**
     * 日志记录方式：log（默认）或 database
     */
    private String logType = LogType.LOG.name().toLowerCase();
    
    /**
     * 日志文件保存路径，默认为系统临时目录下的api-monitor-logs子目录
     */
    private String logFilePath = ensureTrailingSlash(System.getProperty("java.io.tmpdir")) + "api-monitor-logs";

    /**
     * 数据库配置
     */
    private DatabaseConfig database = new DatabaseConfig();

    /**
     * 火焰图配置
     */
    private FlameGraphConfig flameGraph = new FlameGraphConfig();

    /**
     * 是否记录请求体
     */
    private boolean logRequestBody = true;

    /**
     * 是否记录响应体
     */
    private boolean logResponseBody = true;

    /**
     * 忽略的URL路径
     */
    private String[] ignorePaths = new String[]{};
    
    /**
     * 是否记录请求和响应头
     */
    private boolean logHeaders = false;
    
    /**
     * 敏感头信息，需要在日志中过滤
     */
    private Set<String> sensitiveHeaders = new HashSet<>(Arrays.asList("authorization", "token", "secret", "password"));
    
    /**
     * 是否异步记录日志
     */
    private boolean asyncLogging = true;
    
    /**
     * 请求体最大长度（字节）
     */
    private int requestBodyMaxLength = 1024 * 10; // 默认10KB
    
    /**
     * 响应体最大长度（字节）
     */
    private int responseBodyMaxLength = 1024 * 10; // 默认10KB

    // getter和setter方法
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
     * 获取日志文件保存路径
     * @return 日志文件保存路径
     */
    public String getLogFilePath() {
        return logFilePath;
    }
    
    /**
     * 设置日志文件保存路径
     * @param logFilePath 日志文件保存路径
     */
    public void setLogFilePath(String logFilePath) {
        this.logFilePath = ensureTrailingSlash(logFilePath);
    }
    
    /**
     * 确保路径以文件分隔符结尾
     * @param path 路径字符串
     * @return 以文件分隔符结尾的路径
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
     * 验证配置有效性
     */
    @PostConstruct
    public void validate() {
        // 验证日志类型
        if (!LogType.LOG.name().toLowerCase().equals(logType) && 
            !LogType.DATABASE.name().toLowerCase().equals(logType)) {
            throw new IllegalArgumentException("Invalid logType: " + logType + ", must be 'log' or 'database'");
        }
        
        // 验证敏感头信息集合不为空
        if (sensitiveHeaders == null) {
            sensitiveHeaders = new HashSet<>(Arrays.asList("authorization", "token", "secret", "password"));
        }
        
        // 转换敏感头为小写，确保不区分大小写匹配
        sensitiveHeaders = sensitiveHeaders.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        
        // 验证采样配置的合理性
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
            
            // 确保火焰图路径正确
            flameGraph.setSavePath(ensureTrailingSlash(flameGraph.getSavePath()));
        }
    }

    /**
     * 数据库配置内部类
     */
    public static class DatabaseConfig {
        /**
         * 是否启用数据库存储
         */
        private boolean enabled = false;

        /**
         * 数据库表名前缀
         */
        private String tablePrefix = "api_";

        /**
         * 是否自动创建表
         */
        private boolean autoCreateTable = true;

        // getter和setter方法
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
     * 火焰图配置内部类
     */
    public static class FlameGraphConfig {
        /**
         * 是否启用火焰图生成
         */
        private boolean enabled = false;

        /**
         * 火焰图保存路径
         */
        private String savePath = "./flamegraphs";

        /**
         * 火焰图采样时长（毫秒）
         */
        private int samplingDuration = 1000;
        
        /**
         * 火焰图采样率（毫秒）
         */
        private int samplingRate = 50;
        
        /**
         * 火焰图输出格式，支持 html、svg、json，默认为 html
         */
        private String format = "html";

        // getter和setter方法
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
    }
}