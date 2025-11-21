package io.github.noonecanhearme.apimonitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.text.SimpleDateFormat;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 火焰图生成器
 * 负责生成API调用的性能分析火焰图，支持HTML、SVG和JSON格式
 */
public class FlameGraphGenerator implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(FlameGraphGenerator.class);
    
    // 常量定义
    private static final int MAX_STACK_DEPTH = 50;
    private static final int MIN_SAMPLE_COUNT = 10;
    private static final DateTimeFormatter JSON_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final DateTimeFormatter HTML_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private final ApiMonitorProperties properties;
    private final ThreadMXBean threadMXBean;
    private final Map<String, ProfilerTask> activeProfilerTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final File flameGraphDir;

    public FlameGraphGenerator(ApiMonitorProperties properties) {
        this.properties = properties;
        this.threadMXBean = ManagementFactory.getThreadMXBean();
        
        // 优化线程池配置
        int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.scheduler = new ScheduledThreadPoolExecutor(
                corePoolSize,
                runnable -> {
                    Thread thread = new Thread(runnable, "flamegraph-profiler");
                    thread.setDaemon(true); // 设置为守护线程
                    thread.setPriority(Thread.NORM_PRIORITY);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略
        );
        // 设置线程池属性
        ((ThreadPoolExecutor) this.scheduler).setKeepAliveTime(60L, TimeUnit.SECONDS);
        // Queue capacity is set when creating the thread pool, not via setQueueCapacity
        
        // 确保火焰图保存目录存在
        this.flameGraphDir = new File(properties.getFlameGraph().getSavePath());
        if (!flameGraphDir.exists()) {
            boolean created = flameGraphDir.mkdirs();
            if (!created) {
                logger.error("无法创建火焰图保存目录: {}", flameGraphDir.getAbsolutePath());
            }
        }
    }

    /**
     * 开始性能分析
     */
    public void startProfiling(String requestId) {
        startProfiling(requestId, null);
    }
    
    /**
     * 开始性能分析（支持方法签名）
     */
    public void startProfiling(String requestId, String methodSignature) {
        try {
            // 检查是否启用了火焰图功能
            if (!properties.getFlameGraph().isEnabled()) {
                return;
            }

            // 检查是否支持CPU时间分析
            if (!threadMXBean.isThreadCpuTimeSupported()) {
                logger.warn("JVM不支持CPU时间分析，无法生成火焰图");
                return;
            }

            // 启用CPU时间分析
            if (!threadMXBean.isThreadCpuTimeEnabled()) {
                threadMXBean.setThreadCpuTimeEnabled(true);
            }

            // 创建性能分析任务
            ProfilerTask task = new ProfilerTask(requestId, methodSignature);
            activeProfilerTasks.put(requestId, task);

            // 获取配置的采样率
            int samplingRate = properties.getFlameGraph().getSamplingRate();
            scheduler.scheduleAtFixedRate(task, 0, samplingRate, TimeUnit.MILLISECONDS);

            logger.info("开始对请求 [{}] {} 进行性能分析", requestId, methodSignature != null ? "(" + methodSignature + ")" : "");
        } catch (Exception e) {
            logger.error("启动性能分析失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 停止性能分析并生成火焰图
     * @return 火焰图文件路径，如果没有生成则返回null
     */
    public String stopProfiling(String requestId) {
        try {
            ProfilerTask task = activeProfilerTasks.remove(requestId);
            if (task != null) {
                task.stop();
                
                // 等待采样任务完成
                Thread.sleep(100);
                
                // 生成火焰图
                String flameGraphPath = generateFlameGraph(requestId, task.getStackTraces(), task.getMethodSignature());
                
                logger.info("对请求 [{}] 的性能分析已完成，火焰图已生成", requestId);
                return flameGraphPath;
            }
        } catch (Exception e) {
            logger.error("停止性能分析失败: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * 生成火焰图文件
     */
    private String generateFlameGraph(String requestId, Map<String, Integer> stackTraces, String methodSignature) {
        try {
            if (stackTraces.isEmpty()) {
                logger.warn("没有采集到足够的堆栈信息，无法生成火焰图");
                return null;
            }

            // 过滤低频采样，减少噪音
            Map<String, Integer> filteredStacks = stackTraces.entrySet().stream()
                    .filter(entry -> entry.getValue() >= MIN_SAMPLE_COUNT)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
            if (filteredStacks.isEmpty()) {
                logger.warn("过滤后没有足够的堆栈信息，无法生成火焰图");
                return null;
            }

            // 生成火焰图文件名
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
            String fileName = "flamegraph_" + requestId;
            if (methodSignature != null) {
                String sanitizedSignature = methodSignature.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_");
                fileName += "_" + sanitizedSignature;
            }
            fileName += "_" + timestamp;
            
            // 生成折叠堆栈格式文件（原始数据）
            File flameGraphFile = new File(flameGraphDir, fileName + ".txt");
            
            try (FileWriter writer = new FileWriter(flameGraphFile)) {
                for (Map.Entry<String, Integer> entry : filteredStacks.entrySet()) {
                    writer.write(entry.getKey() + " " + entry.getValue() + "\n");
                }
            }
            
            // 根据配置的格式生成对应的火焰图
            String format = properties.getFlameGraph().getFormat().toLowerCase();
            String outputPath = null;
            
            switch (format) {
                case "html":
                    outputPath = generateHtmlFlameGraph(fileName, filteredStacks);
                    break;
                case "svg":
                    outputPath = generateSvgFlameGraph(fileName, filteredStacks);
                    break;
                case "json":
                    outputPath = generateJsonFlameGraph(fileName, filteredStacks);
                    break;
                default:
                    logger.warn("不支持的火焰图格式: {}, 默认生成HTML格式", format);
                    outputPath = generateHtmlFlameGraph(fileName, filteredStacks);
                    break;
            }

            logger.info("火焰图原始数据已保存至: {}", flameGraphFile.getAbsolutePath());
            if (outputPath != null) {
                logger.info("{} 格式火焰图已保存至: {}", format.toUpperCase(), outputPath);
                return outputPath;
            }
            
            return flameGraphFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("生成火焰图失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 生成HTML格式火焰图
     */
    private String generateHtmlFlameGraph(String fileName, Map<String, Integer> stackTraces) {
        try {
            File htmlFile = new File(flameGraphDir, fileName + ".html");
            
            try (PrintWriter writer = new PrintWriter(htmlFile)) {
                // HTML头部
                writer.println("<!DOCTYPE html>");
                writer.println("<html lang=\"zh-CN\">");
                writer.println("<head>");
                writer.println("    <meta charset=\"UTF-8\">");
                writer.println("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
                writer.println("    <title>API监控 - 火焰图</title>");
                writer.println("    <style>");
                writer.println("        body { font-family: 'Consolas', 'Monaco', monospace; margin: 0; padding: 20px; background-color: #f5f5f5; }");
                writer.println("        .container { max-width: 1200px; margin: 0 auto; background-color: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }");
                writer.println("        h1 { color: #333; font-size: 24px; margin-bottom: 20px; }");
                writer.println("        .stats { margin-bottom: 20px; padding: 10px; background-color: #f0f0f0; border-radius: 4px; }");
                writer.println("        .stats p { margin: 5px 0; color: #666; }");
                writer.println("        .stack-traces { max-height: 600px; overflow-y: auto; border: 1px solid #ddd; border-radius: 4px; padding: 10px; }");
                writer.println("        .stack-item { margin-bottom: 10px; padding: 10px; background-color: #f9f9f9; border-left: 4px solid #4CAF50; }");
                writer.println("        .stack-count { font-weight: bold; color: #4CAF50; margin-right: 10px; }");
                writer.println("        .stack-content { word-break: break-all; }");
                writer.println("        .stack-frame { display: block; margin-left: 20px; padding-left: 10px; border-left: 2px dotted #ccc; }");
                writer.println("        .footer { margin-top: 20px; text-align: center; color: #999; font-size: 12px; }");
                writer.println("    </style>");
                writer.println("</head>");
                writer.println("<body>");
                writer.println("    <div class=\"container\">");
                writer.println("        <h1>API调用火焰图分析</h1>");
                
                // 统计信息
                int totalSamples = stackTraces.values().stream().mapToInt(Integer::intValue).sum();
                writer.println("        <div class=\"stats\">");
                writer.println("            <p><strong>总采样次数:</strong> " + totalSamples + "</p>");
                writer.println("            <p><strong>堆栈帧数:</strong> " + stackTraces.size() + "</p>");
                writer.println("            <p><strong>生成时间:</strong> " + HTML_DATE_TIME_FORMATTER.format(LocalDateTime.now()) + "</p>");
                writer.println("        </div>");
                
                // 堆栈信息
                writer.println("        <h2>堆栈详情</h2>");
                writer.println("        <div class=\"stack-traces\">");
                
                // 按采样次数排序并显示
                stackTraces.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(entry -> {
                        writer.println("            <div class=\"stack-item\">");
                        writer.println("                <span class=\"stack-count\">" + entry.getValue() + "次</span>");
                        writer.println("                <div class=\"stack-content\">");
                        
                        // 将堆栈拆分为帧并显示
                        String[] frames = entry.getKey().split(";\\\\\\\\");
                        // 过滤掉空帧
                        for (String frame : frames) {
                            if (!frame.isEmpty()) {
                                writer.println("                <span class=\"stack-frame\">" + frame + "</span>");
                            }
                        }
                        
                        writer.println("                </div>");
                        writer.println("            </div>");
                    });
                
                writer.println("        </div>");
                
                writer.println("        <div class=\"footer\">");
                writer.println("            <p>此报告由 API Monitor Spring Boot Starter 自动生成</p>");
                writer.println("        </div>");
                writer.println("    </div>");
                writer.println("</body>");
                writer.println("</html>");
            }
            
            return htmlFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("生成HTML火焰图失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 生成SVG格式火焰图
     */
    private String generateSvgFlameGraph(String fileName, Map<String, Integer> stackTraces) {
        try {
            File svgFile = new File(flameGraphDir, fileName + ".svg");
            
            try (PrintWriter writer = new PrintWriter(svgFile)) {
                // SVG头部
                writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
                writer.println("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"600\" viewBox=\"0 0 800 600\">");
                writer.println("<title>Flame Graph</title>");
                writer.println("<style>");
                writer.println(".stackframe { cursor: pointer; opacity: 0.9; }");
                writer.println(".stackframe:hover { opacity: 1; }");
                writer.println("text { font-family: monospace; font-size: 12px; }");
                writer.println("</style>");
                
                // 统计信息
                int totalSamples = stackTraces.values().stream().mapToInt(Integer::intValue).sum();
                writer.println("<text x=\"10\" y=\"20\" font-family=\"monospace\" font-size=\"12\">Total Samples: " + totalSamples + "</text>");
                writer.println("<text x=\"10\" y=\"40\" font-family=\"monospace\" font-size=\"12\">Stack Frames: " + stackTraces.size() + "</text>");
                
                // 火焰图标题
                writer.println("<text x=\"400\" y=\"80\" font-family=\"monospace\" font-size=\"16\" text-anchor=\"middle\">API Monitor Flame Graph</text>");
                
                // 简单的火焰图表示
                int startY = 100;
                int barHeight = 20;
                int maxWidth = 780;
                
                // 按采样次数排序并显示前10个
                List<Map.Entry<String, Integer>> sortedEntries = stackTraces.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .collect(Collectors.toList());
                
                int index = 0;
                for (Map.Entry<String, Integer> entry : sortedEntries) {
                    int width = (int)((double)entry.getValue() / totalSamples * maxWidth);
                    int y = startY + index * (barHeight + 5);
                    
                    // 生成随机颜色
                    String color = String.format("#%02x%02x%02x", 
                        64 + (index * 30) % 128, 
                        128 + (index * 40) % 128, 
                        200 + (index * 20) % 55);
                    
                    // 绘制条形
                    writer.println("<rect x=\"10\" y=\"" + y + "\" width=\"" + width + "\" height=\"" + barHeight + "\" fill=\"" + color + "\" opacity=\"0.8\" class=\"stackframe\"/>");
                    
                    // 添加标签（显示方法名）
                    String frameLabel = entry.getKey().split(";\\\\\\\\")[0];
                    // 修复方法名显示 // 只显示第一个帧
                    if (frameLabel.length() > 50) {
                        frameLabel = frameLabel.substring(0, 47) + "...";
                    }
                    writer.println("<text x=\"15\" y=\"" + (y + 14) + "\" font-size=\"12\" fill=\"black\">" + frameLabel + " (" + entry.getValue() + ")</text>");
                    
                    index++;
                }
                
                writer.println("<text x=\"10\" y=\"" + (startY + index * (barHeight + 5) + 20) + "\" font-size=\"12\" fill=\"#666\">注：显示采样次数最多的前10个堆栈</text>");
                writer.println("</svg>");
            }
            
            return svgFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("生成SVG火焰图失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 生成JSON格式火焰图数据
     */
    private String generateJsonFlameGraph(String fileName, Map<String, Integer> stackTraces) {
        try {
            File jsonFile = new File(flameGraphDir, fileName + ".json");
            
            try (PrintWriter writer = new PrintWriter(jsonFile)) {
                writer.println("{");
                writer.println("  \"metadata\": {");
                String generatedAt = JSON_DATE_TIME_FORMATTER.format(LocalDateTime.now());
                writer.println("    \"generatedAt\": \"" + generatedAt + "\",");
                int totalSamples = stackTraces.values().stream().mapToInt(Integer::intValue).sum();
                writer.println("    \"totalSamples\": " + totalSamples + ",");
                writer.println("    \"stackCount\": " + stackTraces.size());
                writer.println("  },");
                writer.println("  \"stacks\": [");
                
                // 按采样次数排序并转换为JSON
                List<Map.Entry<String, Integer>> sortedEntries = stackTraces.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toList());
                
                for (int i = 0; i < sortedEntries.size(); i++) {
                    Map.Entry<String, Integer> entry = sortedEntries.get(i);
                    String[] frames = entry.getKey().split(";\\\\");
                    // 确保正确分割堆栈帧
                    
                    writer.println("    {");
                    writer.println("      \"count\": " + entry.getValue() + ",");
                    writer.println("      \"frames\": [");
                    
                    for (int j = 0; j < frames.length; j++) {
                        if (!frames[j].isEmpty()) {
                            String frame = frames[j].replace("\\", "\\\\").replace("\"", "\\\"");
                            writer.print("        \"" + frame);
                            if (j < frames.length - 1 && !frames[j + 1].isEmpty()) {
                                writer.println("\",");
                            } else {
                                writer.println("\"");
                            }
                        }
                    }
                    
                    writer.println("      ]");
                    if (i < sortedEntries.size() - 1) {
                        writer.println("    },");
                    } else {
                        writer.println("    }");
                    }
                }
                
                writer.println("  ]");
                writer.println("}");
            }
            
            return jsonFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("生成JSON火焰图数据失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 性能分析任务内部类
     */
    private class ProfilerTask implements Runnable {
        private final String requestId;
        private final String methodSignature;
        private final Map<String, Integer> stackTraces = new HashMap<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final long startTime;
        private final int duration;

        public ProfilerTask(String requestId, String methodSignature) {
            this.requestId = requestId;
            this.methodSignature = methodSignature;
            this.startTime = System.currentTimeMillis();
            this.duration = properties.getFlameGraph().getSamplingDuration();
        }

        @Override
        public void run() {
            try {
                if (!running.get()) {
                    return;
                }

                // 检查是否达到采样时长
                if (System.currentTimeMillis() - startTime > duration) {
                    stop();
                    return;
                }

                // 获取所有活跃线程
                Thread[] allThreads = new Thread[Thread.activeCount() * 2];
                int threadCount = Thread.enumerate(allThreads);
                
                for (int i = 0; i < threadCount; i++) {
                    Thread thread = allThreads[i];
                    // 跳过守护线程和死亡线程
                    if (thread.isDaemon() || !thread.isAlive()) {
                        continue;
                    }
                    
                    // 获取线程堆栈
                    StackTraceElement[] stackTraceElements = thread.getStackTrace();
                    if (stackTraceElements == null || stackTraceElements.length < 3) {
                        continue;
                    }
                    
                    // 构建堆栈字符串，限制深度
                    StringBuilder stackBuilder = new StringBuilder();
                    int maxDepth = Math.min(stackTraceElements.length - 1, MAX_STACK_DEPTH + 3);
                    
                    for (int j = maxDepth; j >= 3; j--) {
                        StackTraceElement element = stackTraceElements[j];
                        String className = element.getClassName();
                        String methodName = element.getMethodName();
                        int lineNumber = element.getLineNumber();
                        
                        // 构建完整方法名（包括类名、方法名和行号）
                        String fullMethodName = className + "." + methodName;
                        if (lineNumber > 0) {
                            fullMethodName += ":" + lineNumber;
                        }
                        
                        stackBuilder.append(fullMethodName).append(";");
                    }
                    
                    // 添加线程名作为堆栈顶部
                    if (stackBuilder.length() > 0) {
                        stackBuilder.insert(0, thread.getName() + ";");
                        
                        // 统计堆栈出现次数
                        String stackTrace = stackBuilder.toString();
                        stackTraces.put(stackTrace, stackTraces.getOrDefault(stackTrace, 0) + 1);
                    }
                }
            } catch (Exception e) {
                logger.error("性能分析采样失败: {}", e.getMessage(), e);
            }
        }

        public void stop() {
            running.set(false);
        }

        public Map<String, Integer> getStackTraces() {
            return stackTraces;
        }
        
        public String getMethodSignature() {
            return methodSignature;
        }
    }

    /**
     * 关闭资源
     */
    public void shutdown() {
        try {
            // 停止所有活跃的性能分析任务
            for (ProfilerTask task : activeProfilerTasks.values()) {
                task.stop();
            }
            
            // 清理任务映射
            activeProfilerTasks.clear();
            
            // 关闭调度器
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            logger.info("性能分析器已成功关闭");
        } catch (Exception e) {
            logger.error("关闭性能分析器失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 实现DisposableBean接口的destroy方法，确保在Spring容器关闭时释放资源
     */
    @Override
    public void destroy() throws Exception {
        shutdown();
    }
}