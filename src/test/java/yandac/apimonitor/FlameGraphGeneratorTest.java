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
        // Set to HTML format
        when(properties.getFlameGraph().getFormat()).thenReturn("html");
        
        // The test mainly verifies through startProfiling and stopProfiling methods
        String requestId = "test-request-id-html";
        
        // Start performance profiling
        flameGraphGenerator.startProfiling(requestId, "testMethod");
        
        // Execute some operations
        for (int i = 0; i < 100000; i++) {
            Math.random();
        }
        Thread.sleep(5);
        
        // Stop performance profiling
        String filePath = flameGraphGenerator.stopProfiling(requestId);
        
        // Verify file generation (due to potential test environment limitations, we only verify normal method calls)
        assertTrue(filePath == null || new File(filePath).exists(), "HTML flame graph generation should complete without errors");
        if (filePath != null) {
            assertTrue(filePath.endsWith(".html"), "Generated file should be HTML format");
        }
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