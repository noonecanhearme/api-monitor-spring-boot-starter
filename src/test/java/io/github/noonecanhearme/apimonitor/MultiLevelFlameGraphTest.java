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
 * 测试多层嵌套节点的火焰图生成功能
 */
public class MultiLevelFlameGraphTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiLevelFlameGraphTest.class);
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
    public void testMultiLevelFlameGraph() {
        // 准备测试数据 - 模拟多层嵌套的堆栈跟踪
        Map<String, Integer> stackTraces = createMultiLevelStackTraces();
        String fileName = "multi-level-flamegraph-" + System.currentTimeMillis();

        // 使用反射调用generateHtmlFlameGraph方法
        String filePath = generateHtmlFlameGraph(fileName, stackTraces);

        // 验证生成的文件
        assertNotNull(filePath, "HTML flame graph file path should not be null");
        File htmlFile = new File(filePath);
        assertTrue(htmlFile.exists(), "HTML flame graph file should exist");
        assertTrue(htmlFile.length() > 0, "HTML flame graph file should not be empty");

        logger.info("Generated multi-level HTML flame graph file: {}", filePath);
        logger.info("File size: {} bytes", htmlFile.length());
    }

    /**
     * 创建模拟的多层嵌套堆栈跟踪数据
     */
    private Map<String, Integer> createMultiLevelStackTraces() {
        Map<String, Integer> stackTraces = new HashMap<>();

        // 1. 最外层调用链 - 基础层
        stackTraces.put("main;\"java.lang.Thread.run\"", 50);

        // 2. 二级嵌套 - Web请求处理
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"", 80);
        
        // 3. 三级嵌套 - Controller层
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"", 60);
        
        // 4. 四级嵌套 - Service层
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"", 40);
        
        // 5. 五级嵌套 - Repository层
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"com.example.UserRepository.findById\"", 30);
        
        // 6. 六级嵌套 - 数据库操作
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"com.example.UserRepository.findById\"org.hibernate.Session.get\"", 25);
        
        // 7. 七级嵌套 - JDBC操作
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"com.example.UserRepository.findById\"org.hibernate.Session.get\"java.sql.PreparedStatement.executeQuery\"", 20);
        
        // 8. 八级嵌套 - 数据库驱动
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"com.example.UserRepository.findById\"org.hibernate.Session.get\"java.sql.PreparedStatement.executeQuery\"com.mysql.jdbc.PreparedStatement.executeInternal\"", 15);
        
        // 9. 九级嵌套 - 网络IO
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.UserController.getUser\"com.example.UserService.findById\"com.example.UserRepository.findById\"org.hibernate.Session.get\"java.sql.PreparedStatement.executeQuery\"com.mysql.jdbc.PreparedStatement.executeInternal\"java.net.SocketInputStream.read\"", 10);
        
        // 10. 另一条多层调用链 - 缓存操作
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.ProductController.getProducts\"com.example.ProductService.listProducts\"com.example.CacheService.get\"com.example.RedisCacheImpl.get\"", 35);
        
        // 11. 复杂调用路径 - 业务逻辑处理
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ThreadPoolExecutor.runWorker\"java.util.concurrent.ThreadPoolExecutor$Worker.run\"org.springframework.web.servlet.DispatcherServlet.doDispatch\"com.example.OrderController.createOrder\"com.example.OrderService.create\"com.example.PaymentService.process\"com.example.TransactionManager.begin\"com.example.AccountService.deduct\"com.example.LockManager.acquire\"", 45);

        // 添加一些边缘情况的调用栈
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.concurrent.ForkJoinPool$WorkQueue.runTask\"java.util.concurrent.ForkJoinTask.doExec\"java.util.stream.ForEachOps$ForEachOp$OfRef.accept\"", 20);
        stackTraces.put("main;\"java.lang.Thread.run\"java.util.TimerThread.mainLoop\"java.util.TimerThread.run\"", 15);

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