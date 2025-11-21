package io.github.noonecanhearme.apimonitor;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * 直接测试帧调用生成的测试类
 */
public class DirectFrameCallsTest {

    private static final Logger logger = LoggerFactory.getLogger(DirectFrameCallsTest.class);

    @Test
    public void testFrameCallsGeneration() throws Exception {
        // 创建属性配置
        ApiMonitorProperties properties = new ApiMonitorProperties();
        properties.getFlameGraph().setEnabled(true);
        
        // 创建FlameGraphGenerator实例
        FlameGraphGenerator generator = new FlameGraphGenerator(properties);
        
        try {
            // 创建模拟的frameHierarchy，使用正确的数据结构
            Map<String, Map<String, Integer>> frameHierarchy = new HashMap<>();
            
            // 构建多层级的帧结构 - 按照方法期望的格式
            Map<String, Integer> rootChildren = new HashMap<>();
            rootChildren.put("Level1", 180);
            frameHierarchy.put("root", rootChildren);
            
            Map<String, Integer> level1Children = new HashMap<>();
            level1Children.put("Level2a", 150);
            level1Children.put("Level2b", 30);
            frameHierarchy.put("Level1", level1Children);
            
            Map<String, Integer> level2aChildren = new HashMap<>();
            level2aChildren.put("Level3a", 100);
            level2aChildren.put("Level3b", 50);
            frameHierarchy.put("Level2a", level2aChildren);
            
            // 叶节点没有子节点
            frameHierarchy.put("Level2b", new HashMap<>());
            frameHierarchy.put("Level3a", new HashMap<>());
            frameHierarchy.put("Level3b", new HashMap<>());
            
            // 使用StringWriter来捕获输出
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            
            // 使用反射调用generateFrameCalls方法 - 使用正确的签名
            java.lang.reflect.Method method = FlameGraphGenerator.class.getDeclaredMethod("generateFrameCalls", 
                    PrintWriter.class, Map.class, String.class, int.class, int.class, int.class);
            method.setAccessible(true);
            
            int totalSamples = 180;
            int totalWidth = (Integer) method.invoke(generator, printWriter, 
                    frameHierarchy, "root", 0, 0, totalSamples);
            
            // 确保所有输出都被刷新
            printWriter.flush();
            String result = stringWriter.toString();
            
            logger.info("Generated frame calls: {}", result);
            logger.info("Total width: {}", totalWidth);
            
            // 验证生成的帧调用字符串包含多层级的f()调用
            assertTrue(result.contains("f(0,"), "Should contain root frame call at level 0");
            assertTrue(result.contains("f(1,"), "Should contain Level1 frame call at level 1");
            assertTrue(result.contains("f(2,"), "Should contain Level2a/Level2b frame call at level 2");
            assertTrue(result.contains("f(3,"), "Should contain Level3a/Level3b frame call at level 3");
            
            // 验证总宽度正确
            assertTrue(totalWidth == totalSamples, "Total width should be " + totalSamples);
            
            logger.info("Multi-level frame calls generation test passed!");
        } catch (Exception e) {
            logger.error("Error during test", e);
            throw e;
        } finally {
            generator.shutdown();
        }
    }
    
    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            logger.error("Assertion failed: {}", message);
            throw new AssertionError(message);
        }
    }
}