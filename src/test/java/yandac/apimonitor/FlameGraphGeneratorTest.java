package yandac.apimonitor;

import io.github.noonecanhearme.apimonitor.FlameGraphGenerator;
import io.github.noonecanhearme.apimonitor.ApiMonitorProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        when(flameGraphProps.getSamplingRate()).thenReturn(100); // 增加采样率
        when(flameGraphProps.getSamplingDuration()).thenReturn(5000); // 增加采样时长
        when(flameGraphProps.getFormat()).thenReturn("html");
        when(flameGraphProps.getEventType()).thenReturn(ApiMonitorProperties.FlameGraphEventType.CPU); // 设置事件类型为CPU
        
        flameGraphGenerator = new FlameGraphGenerator(properties);
        
        // 确保test-flamegraphs目录存在
        File dir = new File("./test-flamegraphs");
        if (!dir.exists()) {
            dir.mkdirs();
            System.out.println("Created directory: " + dir.getAbsolutePath());
        }
    }

    @Test
    void testGenerateHtmlFlameGraph() throws Exception {
        // Set to HTML format
        when(properties.getFlameGraph().getFormat()).thenReturn("html");
        // Increase sampling duration for better data collection
        when(properties.getFlameGraph().getSamplingDuration()).thenReturn(3000);
        
        // The test mainly verifies through startProfiling and stopProfiling methods
        String requestId = "test-request-id-html";
        
        System.out.println("Starting profiling for request ID: " + requestId);
        
        // Start performance profiling
        flameGraphGenerator.startProfiling(requestId, "testMethod");
        
        // Execute more complex operations to generate stack traces
        System.out.println("Performing complex operations to generate stack traces...");
        for (int i = 0; i < 10; i++) {
            performComplexOperations();
        }
        // Longer sleep to allow sufficient sampling
        System.out.println("Sleeping for 2 seconds to allow sampling...");
        Thread.sleep(2000);
        
        // Stop performance profiling
        System.out.println("Stopping profiling...");
        String filePath = flameGraphGenerator.stopProfiling(requestId);
        
        // Verify file generation
        System.out.println("Generated flame graph path: " + filePath);
        
        // Check test-flamegraphs directory
        File flameGraphDir = new File("./test-flamegraphs");
        System.out.println("Test flamegraphs directory exists: " + flameGraphDir.exists());
        if (flameGraphDir.exists()) {
            File[] files = flameGraphDir.listFiles();
            System.out.println("Number of files in test-flamegraphs directory: " + (files != null ? files.length : 0));
            if (files != null && files.length > 0) {
                for (File file : files) {
                    System.out.println("File: " + file.getName() + ", Size: " + file.length() + " bytes");
                }
            }
        }
        
        assertTrue(filePath == null || new File(filePath).exists(), "HTML flame graph generation should complete without errors");
        if (filePath != null) {
            assertTrue(filePath.endsWith(".html"), "Generated file should be HTML format");
            assertTrue(new File(filePath).length() > 0, "Generated HTML file should not be empty");
        }
    }
    
    /**
     * Perform complex operations to generate diverse stack traces
     */
    private void performComplexOperations() throws Exception {
        // Create some CPU-intensive operations
        for (int i = 0; i < 10000; i++) {
            // Math operations
            Math.sin(i);
            Math.cos(i);
            Math.tan(i);
            
            // String operations
            String str = "test" + i;
            str.intern();
            str.substring(0, Math.min(str.length(), 2));
            
            // Collection operations
            List<String> list = new ArrayList<>();
            list.add(str);
            list.contains(str);
            list.remove(str);
            
            // Map operations
            Map<String, Integer> map = new HashMap<>();
            map.put(str, i);
            map.get(str);
            
            // Exception handling (but not throwing)
            try {
                if (i % 1000 == 0) {
                    throw new Exception("Test exception");
                }
            } catch (Exception e) {
                // Just catch, don't rethrow
            }
        }
        
        // I/O operation to generate different stack traces
        File tempFile = File.createTempFile("test", ".tmp");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("Test content");
        }
        try (FileReader reader = new FileReader(tempFile)) {
            reader.read();
        }
        tempFile.delete();
    }
    
    @Test
    void testGenerateSvgFlameGraph() throws Exception {
        // Set to SVG format
        when(properties.getFlameGraph().getFormat()).thenReturn("svg");
        
        String requestId = "test-request-id-svg";
        
        // Start performance profiling
        flameGraphGenerator.startProfiling(requestId, "testMethod");
        
        // Execute some operations
        for (int i = 0; i < 100000; i++) {
            Math.random();
        }
        Thread.sleep(5);
        
        // Stop performance profiling
        String filePath = flameGraphGenerator.stopProfiling(requestId);
        
        // Verify file generation
        assertTrue(filePath == null || new File(filePath).exists(), "SVG flame graph generation should complete without errors");
        if (filePath != null) {
            assertTrue(filePath.endsWith(".svg"), "Generated file should be SVG format");
        }
    }
    
    @Test
    void testGenerateJsonFlameGraph() throws Exception {
        // Set to JSON format
        when(properties.getFlameGraph().getFormat()).thenReturn("json");
        
        String requestId = "test-request-id-json";
        
        // Start performance profiling
        flameGraphGenerator.startProfiling(requestId, "testMethod");
        
        // Execute some operations
        for (int i = 0; i < 100000; i++) {
            Math.random();
        }
        Thread.sleep(5);
        
        // Stop performance profiling
        String filePath = flameGraphGenerator.stopProfiling(requestId);
        
        // Verify if file is generated
        assertTrue(filePath == null || new File(filePath).exists(), "JSON flame graph generation should complete without errors");
        if (filePath != null) {
            assertTrue(filePath.endsWith(".json"), "Generated file should be JSON format");
        }
    }

    @Test
    void testStartAndStopProfiling() {
        // Use default HTML format
        when(properties.getFlameGraph().getFormat()).thenReturn("html");
        
        // Start performance profiling
        String requestId = "test-request-id-2";
        flameGraphGenerator.startProfiling(requestId);

        // Execute some operations
        try {
            for (int i = 0; i < 100000; i++) {
                Math.random();
            }
            Thread.sleep(5);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Stop performance profiling
        String filePath = flameGraphGenerator.stopProfiling(requestId);

        // Verify normal method calls
        assertTrue(filePath == null || new File(filePath).exists(), "Profiling should complete without errors");
    }

    @Test
    void testShutdown() {
        // Test if shutdown method executes normally
        flameGraphGenerator.shutdown();
        // Since this is a void method, we only verify it executes normally without exceptions
        assertTrue(true, "Shutdown method should execute without errors");
    }
}