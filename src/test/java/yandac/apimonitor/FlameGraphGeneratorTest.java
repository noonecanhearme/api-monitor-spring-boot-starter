package yandac.apimonitor;

import yandac.apimonitor.FlameGraphGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FlameGraphGeneratorTest {

    private FlameGraphGenerator flameGraphGenerator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        flameGraphGenerator = new FlameGraphGenerator();
        flameGraphGenerator.setSavePath("./test-flamegraphs");
        flameGraphGenerator.setDefaultSamplingDuration(1000);
    }

    @Test
    void testGenerateFlameGraph() throws Exception {
        // 创建一个简单的任务进行性能分析
        Runnable task = () -> {
            try {
                // 模拟一些计算工作
                for (int i = 0; i < 1000000; i++) {
                    Math.sqrt(i);
                }
                Thread.sleep(10); // 短暂睡眠以便有足够的样本
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        // 执行火焰图生成，使用较短的采样时间以加快测试速度
        String filePath = flameGraphGenerator.generateFlameGraph(task, "testMethod", 100);

        // 验证文件是否生成
        File file = new File(filePath);
        assertTrue(file.exists(), "Flame graph file should be generated");
        assertTrue(file.length() > 0, "Flame graph file should have content");

        // 清理测试生成的文件和目录
        file.delete();
        new File("./test-flamegraphs").delete();
    }

    @Test
    void testStartAndStopProfiling() {
        // 开始性能分析
        flameGraphGenerator.startProfiling();

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
        String stackTrace = flameGraphGenerator.stopProfiling();

        // 验证结果不为空
        assertTrue(stackTrace != null && !stackTrace.isEmpty(), "Profiling result should not be empty");
    }

    @Test
    void testFormatStackTrace() {
        // 创建一个简单的堆栈跟踪
        String threadName = "main";  
        String[] stackFrames = {
            "com.example.TestClass.methodA",
            "com.example.TestClass.methodB",
            "com.example.TestClass.methodC"
        };

        // 格式化堆栈跟踪
        String formatted = flameGraphGenerator.formatStackTrace(threadName, stackFrames);

        // 验证结果
        assertTrue(formatted.contains("main"), "Formatted stack trace should contain thread name");
        assertTrue(formatted.contains("com.example.TestClass.methodC;1"), 
                "Formatted stack trace should contain formatted stack frames");
    }
}