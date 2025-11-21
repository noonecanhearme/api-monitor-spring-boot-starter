package io.github.noonecanhearme.apimonitor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FlameGraphGeneratorTest {

    private static final Logger logger = LoggerFactory.getLogger(FlameGraphGeneratorTest.class);
    private FlameGraphGenerator flameGraphGenerator;
    private ApiMonitorProperties properties;
    private File outputDir;

    @BeforeEach
    public void setUp() {
        // 配置属性
        properties = new ApiMonitorProperties();
        // 设置项目目录下的flamegraphs子目录作为火焰图保存路径
        outputDir = new File("flamegraphs");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        properties.getFlameGraph().setSavePath(outputDir.getAbsolutePath());
        properties.getFlameGraph().setEnabled(true);
        properties.getFlameGraph().setFormat("html");
        properties.getFlameGraph().setSamplingDuration(1000);
        properties.getFlameGraph().setSamplingRate(50);
        properties.getFlameGraph().setEventType(ApiMonitorProperties.FlameGraphEventType.CPU);

        // 创建FlameGraphGenerator实例
        flameGraphGenerator = new FlameGraphGenerator(properties);
    }

    @AfterEach
    public void tearDown() {
        // 关闭资源
        flameGraphGenerator.shutdown();
    }

    @Test
    public void testGenerateHtmlFlameGraph() {
        // 准备测试数据 - 模拟堆栈跟踪
        Map<String, Integer> stackTraces = createSampleStackTraces();
        String fileName = "test-flamegraph-" + System.currentTimeMillis();

        // 使用反射调用generateHtmlFlameGraph方法
        String filePath = generateHtmlFlameGraph(fileName, stackTraces);

        // 验证生成的文件
        assertNotNull(filePath, "HTML flame graph file path should not be null");
        File htmlFile = new File(filePath);
        assertTrue(htmlFile.exists(), "HTML flame graph file should exist");
        assertTrue(htmlFile.length() > 0, "HTML flame graph file should not be empty");

        logger.info("Generated HTML flame graph file: {}", filePath);
        logger.info("File size: {} bytes", htmlFile.length());
    }

    /**
     * 创建模拟的堆栈跟踪数据
     */
    private Map<String, Integer> createSampleStackTraces() {
        Map<String, Integer> stackTraces = new HashMap<>();

        // 模拟多级调用堆栈
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"", 100);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"com.example.Service.process\"", 80);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"com.example.Service.process\"com.example.Repository.find\"", 60);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"com.example.Service.process\"com.example.Repository.save\"", 40);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"com.example.Service.validate\"", 20);
        
        // 添加带有不同事件类型的堆栈
        stackTraces.put("main;\"ALLOC|java.lang.Thread.run\"ALLOC|java.util.HashMap.resize\"ALLOC|java.util.HashMap.put\"", 30);
        stackTraces.put("main;\"LOCK|java.lang.Thread.run\"LOCK|java.util.concurrent.locks.ReentrantLock.lock\"LOCK|com.example.Cache.get\"", 15);
        stackTraces.put("main;\"WALL|java.lang.Thread.run\"WALL|java.net.SocketInputStream.read\"WALL|com.example.Client.connect\"", 25);

        // 添加更多的方法调用层次
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"com.example.Controller.handle\"com.example.Service.process\"com.example.Repository.find\"java.sql.PreparedStatement.execute\"", 30);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"com.example.Controller.handle\"com.example.Service.process\"com.example.Validator.validate\"", 10);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"com.example.Controller.handle\"com.example.Service.log\"org.slf4j.Logger.info\"", 15);

        // 添加一些JDK内部方法调用
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"java.lang.StringBuilder.toString\"", 5);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"java.util.regex.Pattern.matcher\"java.util.regex.Matcher.find\"", 8);

        return stackTraces;
    }

    /**
     * 为了直接使用private方法，需要通过反射或修改访问级别
     * 这里我们使用反射来调用generateHtmlFlameGraph方法
     */
    private String generateHtmlFlameGraph(String fileName, Map<String, Integer> stackTraces) {
        try {
            java.lang.reflect.Method method = FlameGraphGenerator.class.getDeclaredMethod("generateHtmlFlameGraph", String.class, Map.class);
            method.setAccessible(true);
            return (String) method.invoke(flameGraphGenerator, fileName, stackTraces);
        } catch (Exception e) {
            logger.error("Error invoking generateHtmlFlameGraph method", e);
            fail("Failed to invoke generateHtmlFlameGraph method: " + e.getMessage());
            return null;
        }
    }

    /**
     * 测试processStackTraces方法是否正确处理多层嵌套的堆栈跟踪
     */
    @Test
    public void testProcessStackTracesMultiLevel() throws Exception {
        // 创建模拟的堆栈跟踪数据，包含更清晰的多层嵌套结构
        Map<String, Integer> stackTraces = new HashMap<>();
        stackTraces.put("CPU|main;\\java.lang.Thread.run;\\com.example.App.main;\\com.example.Service.process;\\com.example.Repository.findById", 100);
        stackTraces.put("CPU|main;\\java.lang.Thread.run;\\com.example.App.main;\\com.example.Service.process;\\com.example.Cache.get", 50);
        stackTraces.put("CPU|main;\\java.lang.Thread.run;\\com.example.App.main;\\com.example.Service.validate;\\com.example.Validator.isValid", 30);
        
        // 创建一个StringWriter来捕获输出
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        
        // 使用Java反射调用私有方法
        Method processStackTracesMethod = FlameGraphGenerator.class.getDeclaredMethod("processStackTraces", PrintWriter.class, Map.class);
        processStackTracesMethod.setAccessible(true);
        processStackTracesMethod.invoke(flameGraphGenerator, writer, stackTraces);
        
        // 获取输出
        String output = stringWriter.toString();
        
        logger.info("Processed stack traces output: {}", output);
        
        // 验证输出中包含正确的f()调用，特别是不同层级的调用
        assertTrue(output.contains("f(0, 0, 180, 3, 'all')"), "应该包含根节点调用");
        assertTrue(output.contains("CPU|main"), "应该包含线程名称节点");
        
        // 验证多个层级的数据
        // 检查不同级别的f()调用是否存在
        int level1Count = 0;
        int level2Count = 0;
        int level3Count = 0;
        int level4Count = 0;
        
        for (int i = 0; i < output.length() - 2; i++) {
            if (output.charAt(i) == 'f' && output.charAt(i + 1) == '(') {
                int j = i + 2;
                StringBuilder levelStr = new StringBuilder();
                while (j < output.length() && output.charAt(j) != ',') {
                    levelStr.append(output.charAt(j));
                    j++;
                }
                try {
                    int level = Integer.parseInt(levelStr.toString());
                    switch (level) {
                        case 1: level1Count++;
                                break;
                        case 2: level2Count++;
                                break;
                        case 3: level3Count++;
                                break;
                        case 4: level4Count++;
                                break;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        
        logger.info("Level counts: 1={}, 2={}, 3={}, 4={}", level1Count, level2Count, level3Count, level4Count);
        
        // 验证至少存在多个层级的数据
        assertTrue(level1Count > 0, "应该至少有一个level 1的调用");
        assertTrue(level2Count > 0, "应该至少有一个level 2的调用");
        assertTrue(level3Count > 0, "应该至少有一个level 3的调用");
        assertTrue(level4Count > 0, "应该至少有一个level 4的调用");
    }

    /**
     * 测试generateFrameCalls方法是否正确处理多层嵌套的帧层次结构
     */
    @Test
    public void testGenerateFrameCallsMultiLevel() throws Exception {
        // 创建一个简单的帧层次结构
        Map<String, Map<String, Integer>> frameHierarchy = new HashMap<>();
        
        // 根节点的子节点
        Map<String, Integer> rootChildren = new HashMap<>();
        rootChildren.put("level1a", 100);
        rootChildren.put("level1b", 50);
        frameHierarchy.put("root", rootChildren);
        
        // level1a的子节点
        Map<String, Integer> level1aChildren = new HashMap<>();
        level1aChildren.put("level2a", 70);
        level1aChildren.put("level2b", 30);
        frameHierarchy.put("level1a", level1aChildren);
        
        // level1b的子节点
        Map<String, Integer> level1bChildren = new HashMap<>();
        level1bChildren.put("level2c", 50);
        frameHierarchy.put("level1b", level1bChildren);
        
        // level2a的子节点
        Map<String, Integer> level2aChildren = new HashMap<>();
        level2aChildren.put("level3a", 50);
        level2aChildren.put("level3b", 20);
        frameHierarchy.put("level2a", level2aChildren);
        
        // 创建一个StringWriter来捕获输出
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        
        // 使用Java反射调用私有方法
        Method generateFrameCallsMethod = FlameGraphGenerator.class.getDeclaredMethod(
                "generateFrameCalls", 
                PrintWriter.class, 
                Map.class, 
                String.class, 
                int.class, 
                int.class, 
                int.class
        );
        generateFrameCallsMethod.setAccessible(true);
        int totalWidth = (int) generateFrameCallsMethod.invoke(
                flameGraphGenerator, 
                writer, 
                frameHierarchy, 
                "root", 
                0, 
                0, 
                150
        );
        
        // 获取输出
        String output = stringWriter.toString();
        
        logger.info("Generated frame calls output: {}", output);
        logger.info("Total width: {}", totalWidth);
        
        // 验证输出和返回值
        assertEquals(150, totalWidth, "总宽度应该等于所有样本数的总和");
        assertTrue(output.contains("f(0, 0, 150, 3, 'all')"), "应该包含根节点调用");
        assertTrue(output.contains("f(1, 0, 100, 3, 'level1a')"), "应该包含level1a节点调用");
        assertTrue(output.contains("f(1, 100, 50, 3, 'level1b')"), "应该包含level1b节点调用");
        assertTrue(output.contains("f(2, 0, 70, 3, 'level2a')"), "应该包含level2a节点调用");
        assertTrue(output.contains("f(2, 70, 30, 3, 'level2b')"), "应该包含level2b节点调用");
        assertTrue(output.contains("f(2, 100, 50, 3, 'level2c')"), "应该包含level2c节点调用");
        assertTrue(output.contains("f(3, 0, 50, 3, 'level3a')"), "应该包含level3a节点调用");
        assertTrue(output.contains("f(3, 50, 20, 3, 'level3b')"), "应该包含level3b节点调用");
    }

    /**
     * 测试escapeString方法是否正确转义特殊字符
     */
    @Test
    public void testEscapeString() throws Exception {
        // 使用Java反射调用私有方法
        Method escapeStringMethod = FlameGraphGenerator.class.getDeclaredMethod("escapeString", String.class);
        escapeStringMethod.setAccessible(true);
        String result = (String) escapeStringMethod.invoke(
                flameGraphGenerator, 
                "This string contains 'quotes', \\, \n, \r, and \t characters"
        );
        
        // 验证字符串转义正确性
        assertEquals(
                "This string contains \\u0027quotes\\u0027, \\\\u005c, \\n, \\r, and \\t characters", 
                result,
                "字符串应该被正确转义"
        );
    }
}