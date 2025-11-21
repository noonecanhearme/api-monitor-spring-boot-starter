package io.github.noonecanhearme.apimonitor;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * 简单的多层级火焰图生成测试类
 */
public class SimpleMultiLevelTest {

    private static final Logger logger = LoggerFactory.getLogger(SimpleMultiLevelTest.class);

    @Test
    public void testMultiLevelFlameGraph() throws Exception {
        // 创建属性配置
        ApiMonitorProperties properties = new ApiMonitorProperties();
        properties.getFlameGraph().setEnabled(true);
        properties.getFlameGraph().setSavePath("flamegraphs");
        properties.getFlameGraph().setFormat("html");
        
        // 创建FlameGraphGenerator实例
        FlameGraphGenerator generator = new FlameGraphGenerator(properties);
        
        // 创建测试数据 - 确保堆栈层级清晰
        Map<String, Integer> stackTraces = new HashMap<>();
        
        // 添加多层嵌套的堆栈跟踪数据 - 使用正确的格式
        stackTraces.put("CPU|main;Level1;Level2a;Level3a", 100);
        stackTraces.put("CPU|main;Level1;Level2a;Level3b", 50);
        stackTraces.put("CPU|main;Level1;Level2b", 30);
        
        // 生成火焰图
        String fileName = "simple-multi-level-" + System.currentTimeMillis();
        String filePath = null;
        try {
            // 使用反射调用私有方法generateHtmlFlameGraph
            java.lang.reflect.Method method = FlameGraphGenerator.class.getDeclaredMethod("generateHtmlFlameGraph", String.class, Map.class);
            method.setAccessible(true);
            filePath = (String) method.invoke(generator, fileName, stackTraces);
            logger.info("Generated flame graph at: {}", filePath);
            
            // 读取生成的文件内容进行验证
            File file = new File(filePath);
            assertTrue(file.exists(), "火焰图文件应该存在");
            assertTrue(file.length() > 0, "火焰图文件应该不为空");
            
            // 读取文件内容验证多层级结构
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                StringBuilder content = new StringBuilder();
                boolean hasLevel1 = false;
                boolean hasLevel2 = false;
                boolean hasLevel3 = false;
                
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                    if (line.contains("f(1,")) hasLevel1 = true;
                    if (line.contains("f(2,")) hasLevel2 = true;
                    if (line.contains("f(3,")) hasLevel3 = true;
                }
                
                logger.info("Flame graph content preview: {}", content.substring(0, Math.min(500, content.length())));
                assertTrue(hasLevel1, "火焰图应该包含level 1");
                assertTrue(hasLevel2, "火焰图应该包含level 2");
                assertTrue(hasLevel3, "火焰图应该包含level 3");
                logger.info("Multi-level flame graph test passed!");
            }
        } catch (Exception e) {
            logger.error("Error during flame graph generation", e);
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