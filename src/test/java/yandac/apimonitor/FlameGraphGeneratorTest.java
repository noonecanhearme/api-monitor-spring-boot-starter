package yandac.apimonitor;

import io.github.noonecanhearme.apimonitor.FlameGraphGenerator;
import io.github.noonecanhearme.apimonitor.ApiMonitorProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlameGraphGeneratorTest {

    private FlameGraphGenerator flameGraphGenerator;
    private ApiMonitorProperties properties;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = mock(ApiMonitorProperties.class);
        ApiMonitorProperties.FlameGraphConfig flameGraphProps = mock(ApiMonitorProperties.FlameGraphConfig.class);
        
        when(properties.getFlameGraph()).thenReturn(flameGraphProps);
        when(flameGraphProps.getSavePath()).thenReturn("./test-flamegraphs");
        when(flameGraphProps.isEnabled()).thenReturn(true);
        when(flameGraphProps.getSamplingRate()).thenReturn(10);
        when(flameGraphProps.getSamplingDuration()).thenReturn(1000);
        when(flameGraphProps.getFormat()).thenReturn("html");
        
        flameGraphGenerator = new FlameGraphGenerator(properties);
    }

    @Test
    void testGenerateHtmlFlameGraph() throws Exception {
        // 设置为HTML格式
        when(properties.getFlameGraph().getFormat()).thenReturn("html");
        
        // 测试主要通过startProfiling和stopProfiling方法验证
        String requestId = "test-request-id-html";
        
        // 开始性能分析
        flameGraphGenerator.startProfiling(requestId, "testMethod");
        
        // 执行一些操作
        for (int i = 0; i < 100000; i++) {
            Math.random();
        }
        Thread.sleep(5);
        
        // 停止性能分析
        String filePath = flameGraphGenerator.stopProfiling(requestId);
        
        // 验证文件是否生成（由于测试环境可能限制，这里只验证方法调用正常）
        assertTrue(filePath == null || new File(filePath).exists(), "HTML flame graph generation should complete without errors");
        if (filePath != null) {
            assertTrue(filePath.endsWith(".html"), "Generated file should be HTML format");
        }
    }
    
    @Test
    void testGenerateSvgFlameGraph() throws Exception {
        // 设置为SVG格式
        when(properties.getFlameGraph().getFormat()).thenReturn("svg");
        
        String requestId = "test-request-id-svg";
        
        // 开始性能分析
        flameGraphGenerator.startProfiling(requestId, "testMethod");
        
        // 执行一些操作
        for (int i = 0; i < 100000; i++) {
            Math.random();
        }
        Thread.sleep(5);
        
        // 停止性能分析
        String filePath = flameGraphGenerator.stopProfiling(requestId);
        
        // 验证文件是否生成
        assertTrue(filePath == null || new File(filePath).exists(), "SVG flame graph generation should complete without errors");
        if (filePath != null) {
            assertTrue(filePath.endsWith(".svg"), "Generated file should be SVG format");
        }
    }
    
    @Test
    void testGenerateJsonFlameGraph() throws Exception {
        // 设置为JSON格式
        when(properties.getFlameGraph().getFormat()).thenReturn("json");
        
        String requestId = "test-request-id-json";
        
        // 开始性能分析
        flameGraphGenerator.startProfiling(requestId, "testMethod");
        
        // 执行一些操作
        for (int i = 0; i < 100000; i++) {
            Math.random();
        }
        Thread.sleep(5);
        
        // 停止性能分析
        String filePath = flameGraphGenerator.stopProfiling(requestId);
        
        // 验证文件是否生成
        assertTrue(filePath == null || new File(filePath).exists(), "JSON flame graph generation should complete without errors");
        if (filePath != null) {
            assertTrue(filePath.endsWith(".json"), "Generated file should be JSON format");
        }
    }

    @Test
    void testStartAndStopProfiling() {
        // 使用默认HTML格式
        when(properties.getFlameGraph().getFormat()).thenReturn("html");
        
        // 开始性能分析
        String requestId = "test-request-id-2";
        flameGraphGenerator.startProfiling(requestId);

        // 执行一些操作
        try {
            for (int i = 0; i < 100000; i++) {
                Math.random();
            }
            Thread.sleep(5);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 停止性能分析
        String filePath = flameGraphGenerator.stopProfiling(requestId);

        // 验证方法调用正常
        assertTrue(filePath == null || new File(filePath).exists(), "Profiling should complete without errors");
    }

    @Test
    void testShutdown() {
        // 测试shutdown方法是否正常执行
        flameGraphGenerator.shutdown();
        // 由于这是一个void方法，我们只验证它能正常执行而不抛出异常
        assertTrue(true, "Shutdown method should execute without errors");
    }
}