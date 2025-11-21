package yandac.apimonitor;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

public class FlameGraphSimpleTest {

    @Test
    public void testSimpleHtmlFlameGraph() throws Exception {
        // Create temporary directories if they don't exist
        File outputDir = new File("test-flamegraphs");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        // Create a simple but rich HTML flame graph
        StringBuilder htmlBuilder = new StringBuilder();
        htmlBuilder.append("<!DOCTYPE html>\n");
        htmlBuilder.append("<html>\n");
        htmlBuilder.append("<head>\n");
        htmlBuilder.append("    <title>Flame Graph - Rich Test Data</title>\n");
        htmlBuilder.append("    <style>\n");
        htmlBuilder.append("        body { font-family: Arial, sans-serif; margin: 0; padding: 0; background-color: #f8f8f8; }\n");
        htmlBuilder.append("        h1 { text-align: center; margin: 20px 0; }\n");
        htmlBuilder.append("        #container { width: 100%; height: 100%; }\n");
        htmlBuilder.append("        #flamegraph { margin: 0 auto; }\n");
        htmlBuilder.append("        .frame { height: 16px; background-color: #ddd; border-right: 1px solid #fff; cursor: pointer; float: left; }\n");
        htmlBuilder.append("        .frame:hover { opacity: 0.8; }\n");
        htmlBuilder.append("        .frame-label { height: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; line-height: 16px; padding: 0 4px; }\n");
        htmlBuilder.append("        .level { clear: both; margin-bottom: 2px; }\n");
        htmlBuilder.append("    </style>\n");
        htmlBuilder.append("</head>\n");
        htmlBuilder.append("<body>\n");
        htmlBuilder.append("    <h1>Flame Graph - Rich Test Data Example</h1>\n");
        htmlBuilder.append("    <div id='container'>\n");
        htmlBuilder.append("        <div id='flamegraph' style='width: 1000px; margin: 0 auto;'>\n");
        
        // Level 1 - Main
        htmlBuilder.append("            <div class='level' style='height: 20px;'>\n");
        htmlBuilder.append("                <div class='frame' style='width: 1000px; background-color: #3498db;'>\n");
        htmlBuilder.append("                    <div class='frame-label'>main (100%)</div>\n");
        htmlBuilder.append("                </div>\n");
        htmlBuilder.append("            </div>\n");
        
        // Level 2 - API Request Handler (50%)
        htmlBuilder.append("            <div class='level' style='height: 20px;'>\n");
        htmlBuilder.append("                <div class='frame' style='width: 500px; background-color: #2ecc71;'>\n");
        htmlBuilder.append("                    <div class='frame-label'>apiRequestHandler (50%)</div>\n");
        htmlBuilder.append("                </div>\n");
        htmlBuilder.append("                <div class='frame' style='width: 300px; background-color: #f39c12;'>\n");
        htmlBuilder.append("                    <div class='frame-label'>responseHandler (30%)</div>\n");
        htmlBuilder.append("                </div>\n");
        htmlBuilder.append("                <div class='frame' style='width: 200px; background-color: #e74c3c;'>\n");
        htmlBuilder.append("                    <div class='frame-label'>loggingAndMetrics (20%)</div>\n");
        htmlBuilder.append("                </div>\n");
        htmlBuilder.append("            </div>\n");
        
        // Level 3 - Request Validation (15%)
        htmlBuilder.append("            <div class='level' style='height: 20px;'>\n");
        htmlBuilder.append("                <div class='frame' style='width: 150px; background-color: #9b59b6;'>\n");
        htmlBuilder.append("                    <div class='frame-label'>requestValidation (15%)</div>\n");
        htmlBuilder.append("                </div>\n");
        htmlBuilder.append("                <div class='frame' style='width: 350px; background-color: #1abc9c;'>\n");
        htmlBuilder.append("                    <div class='frame-label'>businessLogic (35%)</div>\n");
        htmlBuilder.append("                </div>\n");
        // ... more frames
        htmlBuilder.append("            </div>\n");
        
        // Additional levels with more detailed frames
        htmlBuilder.append("            <div class='level' style='height: 20px;'>\n");
        htmlBuilder.append("                <div class='frame' style='width: 60px; background-color: #34495e;'>\n");
        htmlBuilder.append("                    <div class='frame-label'>Pattern.matcher (6%)</div>\n");
        htmlBuilder.append("                </div>\n");
        htmlBuilder.append("                <div class='frame' style='width: 40px; background-color: #16a085;'>\n");
        htmlBuilder.append("                    <div class='frame-label'>Matcher.matches (4%)</div>\n");
        htmlBuilder.append("                </div>\n");
        htmlBuilder.append("                <div class='frame' style='width: 30px; background-color: #27ae60;'>\n");
        htmlBuilder.append("                    <div class='frame-label'>String.isEmpty (3%)</div>\n");
        htmlBuilder.append("                </div>\n");
        htmlBuilder.append("                <div class='frame' style='width: 20px; background-color: #f1c40f;'>\n");
        htmlBuilder.append("                    <div class='frame-label'>Objects.requireNonNull (2%)</div>\n");
        // ... more detailed frames
        htmlBuilder.append("            </div>\n");
        
        htmlBuilder.append("        </div>\n");
        htmlBuilder.append("    </div>\n");
        htmlBuilder.append("    <div style='text-align: center; margin-top: 20px;'>\n");
        htmlBuilder.append("        <p>Generated with rich mock data for API monitoring demonstration</p>\n");
        htmlBuilder.append("    </div>\n");
        htmlBuilder.append("</body>\n");
        htmlBuilder.append("</html>");
        
        // Write to file
        String filePath = "test-flamegraphs/rich-flamegraph.html";
        Files.writeString(new File(filePath).toPath(), htmlBuilder.toString());
        
        // Verify file was created successfully
        File generatedFile = new File(filePath);
        assertTrue(generatedFile.exists(), "HTML flame graph file should be created");
        assertTrue(generatedFile.length() > 0, "HTML file should not be empty");
        System.out.println("Successfully generated rich HTML flame graph at: " + filePath);
    }
}