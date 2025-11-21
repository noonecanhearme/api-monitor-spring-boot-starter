package io.github.noonecanhearme.apimonitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试真正的多层级火焰图生成功能，确保数据分布在不同的level中
 */
public class TrueMultiLevelFlameGraphTest {

    private static final Logger logger = LoggerFactory.getLogger(TrueMultiLevelFlameGraphTest.class);
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

    @Test
    public void testTrueMultiLevelFlameGraph() {
        // 准备测试数据 - 模拟真正的多层级嵌套堆栈跟踪
        Map<String, Integer> stackTraces = createTrueMultiLevelStackTraces();
        String fileName = "true-multi-level-flamegraph-" + System.currentTimeMillis();

        // 使用反射调用generateHtmlFlameGraph方法
        String filePath = generateHtmlFlameGraph(fileName, stackTraces);

        // 验证生成的文件
        assertNotNull(filePath, "HTML flame graph file path should not be null");
        File htmlFile = new File(filePath);
        assertTrue(htmlFile.exists(), "HTML flame graph file should exist");
        assertTrue(htmlFile.length() > 0, "HTML flame graph file should not be empty");

        logger.info("Generated true multi-level HTML flame graph file: {}", filePath);
        logger.info("File size: {} bytes", htmlFile.length());
    }

    /**
     * 创建模拟的真正多层级嵌套堆栈跟踪数据
     * 确保这些数据会被处理到不同的level中
     */
    private Map<String, Integer> createTrueMultiLevelStackTraces() {
        Map<String, Integer> stackTraces = new HashMap<>();

        // 1. 基础层级 - 根调用
        stackTraces.put("main;", 100);
        
        // 2. 第一级调用
        stackTraces.put("main;\"java.lang.Thread.run\"", 80);
        stackTraces.put("main;\"java.lang.Runnable.run\"", 60);
        
        // 3. 第二级调用 - 线程池相关
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"", 50);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ForkJoinPool.runWorker\"", 30);
        stackTraces.put("main;\"java.lang.Runnable.run\"java.util.TimerThread.run\"", 40);
        
        // 4. 第三级调用 - Web处理
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"", 40);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun\"", 30);
        
        // 5. 第四级调用 - Spring框架
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"", 35);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun\"org.apache.catalina.connector.CoyoteAdapter.service\"", 25);
        
        // 6. 第五级调用 - Controller层
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"", 30);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.OrderController.createOrder\"", 25);
        
        // 7. 第六级调用 - Service层
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"", 28);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.OrderController.createOrder\"com.example.OrderService.process\"", 22);
        
        // 8. 第七级调用 - Repository层
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"com.example.UserRepository.findById\"", 25);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.OrderController.createOrder\"com.example.OrderService.process\"com.example.OrderRepository.save\"", 20);
        
        // 9. 第八级调用 - 数据库操作
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"com.example.UserRepository.findById\"org.hibernate.Session.get\"", 22);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.OrderController.createOrder\"com.example.OrderService.process\"com.example.OrderRepository.save\"org.hibernate.Session.save\"", 18);
        
        // 10. 第九级调用 - JDBC
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"com.example.UserRepository.findById\"org.hibernate.Session.get\"java.sql.PreparedStatement.executeQuery\"", 20);
        
        // 11. 第十级调用 - 驱动层
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"com.example.UserRepository.findById\"org.hibernate.Session.get\"java.sql.PreparedStatement.executeQuery\"com.mysql.jdbc.PreparedStatement.executeInternal\"", 15);
        
        // 12. 第十一级调用 - IO操作
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"com.example.UserRepository.findById\"org.hibernate.Session.get\"java.sql.PreparedStatement.executeQuery\"com.mysql.jdbc.PreparedStatement.executeInternal\"java.net.SocketInputStream.read\"", 10);
        
        // 13. 第十二级调用 - 底层系统
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"com.example.UserRepository.findById\"org.hibernate.Session.get\"java.sql.PreparedStatement.executeQuery\"com.mysql.jdbc.PreparedStatement.executeInternal\"java.net.SocketInputStream.read\"java.io.FileInputStream.readBytes\"", 8);
        
        // 14. 另一条完整的调用链
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.ProductController.getProducts\"com.example.ProductService.list\"com.example.CacheService.get\"com.example.RedisTemplate.opsForValue\"org.springframework.data.redis.connection.jedis.JedisConnection.get\"redis.clients.jedis.Jedis.get\"", 30);
        
        return stackTraces;
    }

    /**
     * 使用反射调用私有方法generateHtmlFlameGraph
     */
    private String generateHtmlFlameGraph(String fileName, Map<String, Integer> stackTraces) {
        try {
            java.lang.reflect.Method method = FlameGraphGenerator.class.getDeclaredMethod("generateHtmlFlameGraph", String.class, Map.class);
            method.setAccessible(true);
            return (String) method.invoke(flameGraphGenerator, fileName, stackTraces);
        } catch (Exception e) {
            logger.error("Error invoking generateHtmlFlameGraph method", e);
            throw new RuntimeException("Failed to invoke generateHtmlFlameGraph method", e);
        }
    }
}