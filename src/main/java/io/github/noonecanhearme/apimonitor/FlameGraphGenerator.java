package io.github.noonecanhearme.apimonitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 火焰图生成器
 */
public class FlameGraphGenerator {

    private static final Logger logger = LoggerFactory.getLogger(FlameGraphGenerator.class);
    private static final int MAX_STACK_DEPTH = 50;
    private static final int MIN_SAMPLE_COUNT = 2;
    
    private final ApiMonitorProperties properties;
    private final ThreadMXBean threadMXBean;
    private final Map<String, ProfilerTask> activeProfilerTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final File flameGraphDir;
    
    // 常量定义
    private static final int MAX_STACK_DEPTH = 30;
    private static final int MIN_SAMPLE_COUNT = 10;

    public FlameGraphGenerator(ApiMonitorProperties properties) {
        this.properties = properties;
        this.threadMXBean = ManagementFactory.getThreadMXBean();
        
        // 确保火焰图保存目录存在
        this.flameGraphDir = new File(properties.getFlameGraph().getSavePath());
        if (!flameGraphDir.exists()) {
            flameGraphDir.mkdirs();
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
            
            // 生成折叠堆栈格式文件
            File flameGraphFile = new File(flameGraphDir, fileName + ".txt");
            
            try (FileWriter writer = new FileWriter(flameGraphFile)) {
                for (Map.Entry<String, Integer> entry : filteredStacks.entrySet()) {
                    writer.write(entry.getKey() + " " + entry.getValue() + "\n");
                }
            }
            
            // 如果配置了生成SVG，则尝试生成SVG
            String svgPath = null;
            if (properties.getFlameGraph().isGenerateSvg()) {
                svgPath = generateSvgFlameGraph(fileName, filteredStacks);
            }

            logger.info("火焰图已保存至: {}", flameGraphFile.getAbsolutePath());
            if (svgPath != null) {
                logger.info("SVG火焰图已保存至: {}", svgPath);
                return svgPath;
            }
            return flameGraphFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("生成火焰图失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 生成SVG格式火焰图
     */
    private String generateSvgFlameGraph(String fileName, Map<String, Integer> stackTraces) {
        try {
            // 简单的SVG生成逻辑
            File svgFile = new File(flameGraphDir, fileName + ".svg");
            
            try (PrintWriter writer = new PrintWriter(svgFile)) {
                // SVG头部
                writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
                writer.println("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"600\" viewBox=\"0 0 800 600\">");
                writer.println("<title>Flame Graph</title>");
                writer.println("<style>");
                writer.println(".stackframe { cursor: pointer; opacity: 0.9; }");
                writer.println(".stackframe:hover { opacity: 1; }");
                writer.println("</style>");
                
                // 简单统计信息
                int totalSamples = stackTraces.values().stream().mapToInt(Integer::intValue).sum();
                writer.println("<text x=\"10\" y=\"20\" font-family=\"monospace\" font-size=\"12\">Total Samples: " + totalSamples + "</text>");
                writer.println("<text x=\"10\" y=\"40\" font-family=\"monospace\" font-size=\"12\">Stack Frames: " + stackTraces.size() + "</text>");
                
                // 提示信息
                writer.println("<text x=\"10\" y=\"60\" font-family=\"monospace\" font-size=\"12\" fill=\"#333\">Note: This is a simple SVG representation.</text>");
                writer.println("<text x=\"10\" y=\"80\" font-family=\"monospace\" font-size=\"12\" fill=\"#333\">Use folded stack file with flamegraph.pl for interactive visualization.</text>");
                
                writer.println("</svg>");
            }
            
            return svgFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("生成SVG火焰图失败: {}", e.getMessage(), e);
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
}