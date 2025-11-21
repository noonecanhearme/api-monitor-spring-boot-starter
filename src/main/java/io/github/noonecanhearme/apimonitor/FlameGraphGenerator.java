package io.github.noonecanhearme.apimonitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.text.SimpleDateFormat;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Flame Graph Generator
 * Responsible for generating performance analysis flame graphs for API calls, supporting HTML, SVG and JSON formats
 */
public class FlameGraphGenerator implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(FlameGraphGenerator.class);
    
    // Constant definitions
    private static final int MAX_STACK_DEPTH = 50;
    private static final int MIN_SAMPLE_COUNT = 10;
    private static final DateTimeFormatter JSON_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final DateTimeFormatter HTML_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private final ApiMonitorProperties properties;
    private final ThreadMXBean threadMXBean;
    private final Map<String, ProfilerTask> activeProfilerTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final File flameGraphDir;

    public FlameGraphGenerator(ApiMonitorProperties properties) {
        this.properties = properties;
        this.threadMXBean = ManagementFactory.getThreadMXBean();
        
        // Optimize thread pool configuration
        int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.scheduler = new ScheduledThreadPoolExecutor(
                corePoolSize,
                runnable -> {
                    Thread thread = new Thread(runnable, "flamegraph-profiler");
                    thread.setDaemon(true); // Set as daemon thread
                    thread.setPriority(Thread.NORM_PRIORITY);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // Rejection policy
        );
        // Set thread pool properties
        ((ThreadPoolExecutor) this.scheduler).setKeepAliveTime(60L, TimeUnit.SECONDS);
        // Queue capacity is set when creating the thread pool, not via setQueueCapacity
        
        // Ensure flame graph save directory exists
        this.flameGraphDir = new File(properties.getFlameGraph().getSavePath());
        if (!flameGraphDir.exists()) {
            boolean created = flameGraphDir.mkdirs();
            if (!created) {
                logger.error("Failed to create flame graph directory: {}", flameGraphDir.getAbsolutePath());
            }
        }
    }

    /**
     * Start profiling
     */
    public void startProfiling(String requestId) {
        startProfiling(requestId, null);
    }
    
    /**
     * Start profiling (with method signature support)
     */
    public void startProfiling(String requestId, String methodSignature) {
        try {
            // Check if flame graph feature is enabled
            if (!properties.getFlameGraph().isEnabled()) {
                return;
            }

            // Check if CPU time analysis is supported
            if (!threadMXBean.isThreadCpuTimeSupported()) {
                logger.warn("JVM does not support CPU time analysis, unable to generate flame graph");
                return;
            }

            // Enable CPU time analysis
            if (!threadMXBean.isThreadCpuTimeEnabled()) {
                threadMXBean.setThreadCpuTimeEnabled(true);
            }

            // Create profiling task
            ProfilerTask task = new ProfilerTask(requestId, methodSignature);
            activeProfilerTasks.put(requestId, task);

            // Get configured sampling rate
            int samplingRate = properties.getFlameGraph().getSamplingRate();
            scheduler.scheduleAtFixedRate(task, 0, samplingRate, TimeUnit.MILLISECONDS);

            logger.info("Started profiling for request [{}] {}", requestId, methodSignature != null ? "(" + methodSignature + ")" : "");
        } catch (Exception e) {
            logger.error("Failed to start profiling: {}", e.getMessage(), e);
        }
    }

    /**
     * Stop profiling and generate flame graph
     * @return Flame graph file path, null if not generated
     */
    public String stopProfiling(String requestId) {
        try {
            ProfilerTask task = activeProfilerTasks.remove(requestId);
            if (task != null) {
                task.stop();
                
                // Wait for sampling task to complete
                Thread.sleep(100);
                
                // Generate flame graph
                String flameGraphPath = generateFlameGraph(requestId, task.getStackTraces(), task.getMethodSignature());
                
                logger.info("Profiling for request [{}] completed, flame graph generated", requestId);
                return flameGraphPath;
            }
        } catch (Exception e) {
            logger.error("Failed to stop profiling: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Generate flame graph file
     */
    private String generateFlameGraph(String requestId, Map<String, Integer> stackTraces, String methodSignature) {
        try {
            if (stackTraces.isEmpty()) {
                logger.warn("Not enough stack information collected, unable to generate flame graph");
                return null;
            }

            // Filter low-frequency samples to reduce noise
            Map<String, Integer> filteredStacks = stackTraces.entrySet().stream()
                    .filter(entry -> entry.getValue() >= MIN_SAMPLE_COUNT)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
            if (filteredStacks.isEmpty()) {
                logger.warn("Not enough stack information after filtering, unable to generate flame graph");
                return null;
            }

            // Generate flame graph file name
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
            String fileName = "flamegraph_" + requestId;
            if (methodSignature != null) {
                String sanitizedSignature = methodSignature.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_");
                fileName += "_" + sanitizedSignature;
            }
            fileName += "_" + timestamp;
            
            // Generate folded stack format file (raw data)
            File flameGraphFile = new File(flameGraphDir, fileName + ".txt");
            
            try (FileWriter writer = new FileWriter(flameGraphFile)) {
                for (Map.Entry<String, Integer> entry : filteredStacks.entrySet()) {
                    writer.write(entry.getKey() + " " + entry.getValue() + "\n");
                }
            }
            
            // Generate flame graph according to configured format
            String format = properties.getFlameGraph().getFormat().toLowerCase();
            String outputPath = null;
            
            switch (format) {
                case "html":
                    outputPath = generateHtmlFlameGraph(fileName, filteredStacks);
                    break;
                case "svg":
                    outputPath = generateSvgFlameGraph(fileName, filteredStacks);
                    break;
                case "json":
                    outputPath = generateJsonFlameGraph(fileName, filteredStacks);
                    break;
                default:
                    logger.warn("Unsupported flame graph format: {}, generating HTML format by default", format);
                    outputPath = generateHtmlFlameGraph(fileName, filteredStacks);
                    break;
            }

            logger.info("Flame graph raw data saved to: {}", flameGraphFile.getAbsolutePath());
            if (outputPath != null) {
                logger.info("{} format flame graph saved to: {}", format.toUpperCase(), outputPath);
                return outputPath;
            }
            
            return flameGraphFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("Failed to generate flame graph: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Generate HTML format flame graph
     */
    private String generateHtmlFlameGraph(String fileName, Map<String, Integer> stackTraces) {
        try {
            File htmlFile = new File(flameGraphDir, fileName + ".html");
            
            try (PrintWriter writer = new PrintWriter(htmlFile)) {
                // HTML header
                writer.println("<!DOCTYPE html>");
                writer.println("<html lang=\"en-US\">");
                writer.println("<head>");
                writer.println("    <meta charset=\"UTF-8\">");
                writer.println("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
                writer.println("    <title>API Monitoring - Flame Graph</title>");
                writer.println("        <style>");
                writer.println("            body { font-family: 'Consolas', 'Monaco', monospace; margin: 0; padding: 0; background-color: #000000; color: #FFFFFF; line-height: 1.6; }");
                writer.println("            .container { max-width: 100%; margin: 0 auto; padding: 20px; }");
                writer.println("            h1 { color: #FFFFFF; font-size: 28px; margin-bottom: 20px; font-weight: 600; }");
                writer.println("            .controls { margin-bottom: 20px; display: flex; gap: 20px; align-items: center; flex-wrap: wrap; }");
                writer.println("            .control-item { display: flex; align-items: center; gap: 10px; }");
                writer.println("            button { background-color: #333; color: #fff; border: 1px solid #555; padding: 8px 16px; border-radius: 4px; cursor: pointer; transition: all 0.3s ease; }");
                writer.println("            button:hover { background-color: #555; border-color: #777; }");
                writer.println("            select { background-color: #333; color: #fff; border: 1px solid #555; padding: 8px 12px; border-radius: 4px; }");
                writer.println("            .stats { margin-bottom: 20px; padding: 15px; background-color: #1a1a1a; border-radius: 6px; display: flex; gap: 30px; flex-wrap: wrap; }");
                writer.println("            .stats p { margin: 5px 0; color: #cccccc; font-size: 14px; }");
                writer.println("            .stats strong { color: #ffffff; }");
                writer.println("            .flame-container { position: relative; width: 100%; height: 800px; overflow: auto; background-color: #1a1a1a; border-radius: 6px; padding: 20px; border: 1px solid #333; }");
                writer.println("            .flame-row { position: relative; height: 24px; margin-bottom: 1px; display: flex; align-items: center; }");
                writer.println("            .flame-cell { position: absolute; height: 100%; cursor: pointer; display: flex; align-items: center; justify-content: center; color: #000000; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; transition: all 0.2s ease; border-radius: 2px; }");
                writer.println("            .flame-cell:hover { opacity: 0.9; transform: translateY(-1px); box-shadow: 0 2px 4px rgba(0,0,0,0.3); }");
                writer.println("            .flame-cell:active { transform: translateY(0); }");
                writer.println("            .legend { margin-top: 20px; padding: 15px; background-color: #1a1a1a; border-radius: 6px; border: 1px solid #333; }");
                writer.println("            .legend h3 { margin-top: 0; color: #ffffff; font-size: 18px; margin-bottom: 15px; }");
                writer.println("            .legend-item { display: inline-block; margin-right: 30px; margin-bottom: 10px; font-size: 14px; }");
                writer.println("            .legend-color { display: inline-block; width: 20px; height: 20px; margin-right: 8px; vertical-align: middle; border-radius: 2px; box-shadow: 0 1px 3px rgba(0,0,0,0.3); }");
                writer.println("            .tooltip { position: absolute; background-color: rgba(0,0,0,0.9); color: white; padding: 12px; border-radius: 6px; font-size: 13px; pointer-events: none; z-index: 1000; max-width: 450px; display: none; box-shadow: 0 4px 12px rgba(0,0,0,0.5); border: 1px solid #444; }");
                writer.println("            .tooltip strong { color: #4CAF50; }");
                writer.println("            .footer { margin-top: 20px; text-align: center; color: #666; font-size: 12px; }");
                writer.println("            .frame-info { margin-top: 15px; padding: 10px; background-color: #252525; border-radius: 4px; font-size: 12px; }");
                writer.println("            .frame-info pre { margin: 0; white-space: pre-wrap; word-break: break-all; }");
                
                // Define colors for different event types, using gradients and higher contrast
                writer.println("            .event-cpu { background: linear-gradient(90deg, #00FF00, #00CC00); color: #000; font-weight: 500; }");
                writer.println("            .event-alloc { background: linear-gradient(90deg, #0080FF, #0066CC); color: #fff; text-shadow: 1px 1px 1px rgba(0,0,0,0.5); font-weight: 500; }");
                writer.println("            .event-lock { background: linear-gradient(90deg, #FF0000, #CC0000); color: #fff; text-shadow: 1px 1px 1px rgba(0,0,0,0.5); font-weight: 500; }");
                writer.println("            .event-cache-misses { background: linear-gradient(90deg, #FFFF00, #CCCC00); color: #000; font-weight: 500; }");
                
                // Define colors for different package levels, using more vibrant color scheme
                writer.println("            .pkg-java { background: linear-gradient(90deg, #a6cee3, #8ab8d8); }");
                writer.println("            .pkg-com { background: linear-gradient(90deg, #1f78b4, #1868a4); color: #fff; text-shadow: 0.5px 0.5px 1px rgba(0,0,0,0.3); }");
                writer.println("            .pkg-org { background: linear-gradient(90deg, #b2df8a, #98cd70); }");
                writer.println("            .pkg-io { background: linear-gradient(90deg, #33a02c, #288020); color: #fff; text-shadow: 0.5px 0.5px 1px rgba(0,0,0,0.3); }");
                writer.println("            .pkg-javax { background: linear-gradient(90deg, #fb9a99, #f87d7b); }");
                writer.println("            .pkg-other { background: linear-gradient(90deg, #fdbf6f, #e9af5d); }");
                writer.println("        </style>");
                writer.println("</head>");
                writer.println("<body>");
                writer.println("    <div class=\"container\">");
                writer.println("        <h1>API Call Flame Graph Analysis</h1>");
                
                // Control options
                writer.println("        <div class=\"controls\">");
                writer.println("            <div class=\"control-item\">");
                writer.println("                <button id=\"zoomIn\">Zoom In</button>");
                writer.println("                <button id=\"zoomOut\">Zoom Out</button>");
                writer.println("                <button id=\"resetZoom\">Reset</button>");
                writer.println("            </div>");
                writer.println("            <div class=\"control-item\">");
                writer.println("                <label for=\"filterEventType\">Event Type Filter:</label>");
                writer.println("                <select id=\"filterEventType\">");
                writer.println("                    <option value=\"all\">All</option>");
                writer.println("                    <option value=\"CPU\">CPU</option>");
                writer.println("                    <option value=\"ALLOC\">Memory Allocation</option>");
                writer.println("                    <option value=\"LOCK\">Lock Contention</option>");
                writer.println("                    <option value=\"CACHE_MISSES\">Cache Misses</option>");
                writer.println("                </select>");
                writer.println("            </div>");
                writer.println("        </div>");
                
                // Statistics information
                int totalSamples = stackTraces.values().stream().mapToInt(Integer::intValue).sum();
                writer.println("        <div class=\"stats\">");
                writer.println("            <p><strong>Total Samples:</strong> " + totalSamples + "</p>");
                writer.println("            <p><strong>Stack Frames:</strong> " + stackTraces.size() + "</p>");
                writer.println("            <p><strong>Generation Time:</strong> " + HTML_DATE_TIME_FORMATTER.format(LocalDateTime.now()) + "</p>");
                writer.println("        </div>");
                
                // Flame graph container
                writer.println("        <div class=\"flame-container\" id=\"flameContainer\">");
                
                // Build flame graph data structure
                Map<String, Integer> frameCountMap = new HashMap<>();
                Map<String, Set<String>> frameParentMap = new HashMap<>();
                
                // Parse stack traces and build hierarchy
                for (Map.Entry<String, Integer> entry : stackTraces.entrySet()) {
                    String stackTrace = entry.getKey();
                    int count = entry.getValue();
                    
                    String[] frames = stackTrace.split(";\\\\\\\\");
                    for (int i = 0; i < frames.length; i++) {
                        String frame = frames[i].trim();
                        if (!frame.isEmpty()) {
                            // Count occurrences of each frame
                            frameCountMap.put(frame, frameCountMap.getOrDefault(frame, 0) + count);
                            
                            // Record parent-child relationships
                            if (i > 0) {
                                String parent = frames[i - 1].trim();
                                if (!parent.isEmpty()) {
                                    frameParentMap.computeIfAbsent(parent, k -> new HashSet<>()).add(frame);
                                }
                            }
                        }
                    }
                }
                
                // Build flame graph HTML
                generateFlameHtml(writer, frameCountMap, frameParentMap);
                
                writer.println("        </div>");
                
                // Legend
                writer.println("        <div class=\"legend\">");
                writer.println("            <h3>Legend</h3>");
                writer.println("            <div class=\"legend-item\"><span class=\"legend-color event-cpu\"></span> CPU Events</div>");
                writer.println("            <div class=\"legend-item\"><span class=\"legend-color event-alloc\"></span> Memory Allocation Events</div>");
                writer.println("            <div class=\"legend-item\"><span class=\"legend-color event-lock\"></span> Lock Contention Events</div>");
                writer.println("            <div class=\"legend-item\"><span class=\"legend-color event-cache-misses\"></span> Cache Miss Events</div>");
                writer.println("        </div>");
                
                // Tooltip
                writer.println("        <div class=\"tooltip\" id=\"tooltip\"></div>");
                
                // Detailed information display area
                writer.println("        <div class=\"frame-info\" id=\"frameInfo\">");
                writer.println("            <pre>Click on a frame in the flame graph to view details</pre>");
                writer.println("        </div>");
                
                writer.println("        <div class=\"footer\">");
                writer.println("            <p>This report was automatically generated by API Monitor Spring Boot Starter</p>");
                writer.println("        </div>");
                writer.println("    </div>");
                
                // JavaScript functionality
                writer.println("    <script>");
                writer.println("        // Get DOM elements");
                writer.println("        const flameContainer = document.getElementById('flameContainer');");
                writer.println("        const tooltip = document.getElementById('tooltip');");
                writer.println("        const filterEventType = document.getElementById('filterEventType');");
                writer.println("        const zoomInBtn = document.getElementById('zoomIn');");
                writer.println("        const zoomOutBtn = document.getElementById('zoomOut');");
                writer.println("        const resetZoomBtn = document.getElementById('resetZoom');");
                writer.println("        const frameInfo = document.getElementById('frameInfo');");
                writer.println("        const cells = document.querySelectorAll('.flame-cell');");
                
                // Zoom functionality
                writer.println("        let currentScale = 1;");
                writer.println("        const SCALE_FACTOR = 1.2;");
                writer.println("        const MAX_SCALE = 3;");
                writer.println("        const MIN_SCALE = 0.5;");
                
                writer.println("        function applyScale() {");
                writer.println("            flameContainer.style.transform = `scale(${currentScale})`;");
                writer.println("            flameContainer.style.transformOrigin = 'top left';");
                writer.println("        }");
                
                // Frame click functionality - show details and highlight
                writer.println("        cells.forEach(cell => {");
                writer.println("            cell.addEventListener('click', () => {");
                writer.println("                const info = cell.getAttribute('data-info');");
                writer.println("                frameInfo.querySelector('pre').textContent = info;");
                
                // Highlight selected frame
                writer.println("                // Remove previous highlight");
                writer.println("                cells.forEach(c => {");
                writer.println("                    c.style.outline = 'none';");
                writer.println("                    c.style.opacity = '1';");
                writer.println("                });");
                writer.println("                // Add highlight");
                writer.println("                cell.style.outline = '2px solid #fff';");
                writer.println("                cell.style.outlineOffset = '2px';");
                writer.println("                cell.style.opacity = '0.8';");
                writer.println("                ");
                // Auto-scroll to selected frame
                writer.println("                cell.scrollIntoView({ behavior: 'smooth', block: 'center' });");
                writer.println("            });");
                
                // Enhanced hover effects
                writer.println("            cell.addEventListener('mouseenter', () => {");
                writer.println("                cell.style.opacity = '0.8';");
                writer.println("                cell.style.cursor = 'pointer';");
                writer.println("            });");
                
                writer.println("            cell.addEventListener('mouseleave', () => {");
                writer.println("                // Restore opacity only if not selected");
                writer.println("                if (!cell.style.outline) {");
                writer.println("                    cell.style.opacity = '1';");
                writer.println("                }");
                writer.println("                cell.style.cursor = 'default';");
                writer.println("            });");
                writer.println("        });");
                
                // Enhanced event type filtering functionality
                writer.println("        filterEventType.addEventListener('change', () => {");
                writer.println("            const selectedType = filterEventType.value;");
                
                writer.println("            cells.forEach(cell => {");
                writer.println("                if (selectedType === 'all') {");
                writer.println("                    cell.style.display = 'flex';");
                writer.println("                } else {");
                writer.println("                    // Check event type and text match");
                writer.println("                    const hasEventTypeClass = cell.classList.contains('event-' + selectedType.toLowerCase());");
                writer.println("                    const cellText = cell.textContent;");
                writer.println("                    const textStartsWith = cellText.startsWith(selectedType + '|');");
                
                writer.println("                    cell.style.display = (hasEventTypeClass || textStartsWith) ? 'flex' : 'none';");
                writer.println("                }");
                writer.println("            });");
                writer.println("        });");
                
                // Optimized zoom button events
                writer.println("        zoomInBtn.addEventListener('click', () => {");
                writer.println("            if (currentScale < MAX_SCALE) {");
                writer.println("                currentScale *= SCALE_FACTOR;");
                writer.println("                applyScale();");
                writer.println("            }");
                writer.println("        });");
                
                writer.println("        zoomOutBtn.addEventListener('click', () => {");
                writer.println("            if (currentScale > MIN_SCALE) {");
                writer.println("                currentScale /= SCALE_FACTOR;");
                writer.println("                applyScale();");
                writer.println("            }");
                writer.println("        });");
                
                writer.println("        resetZoomBtn.addEventListener('click', () => {");
                writer.println("            currentScale = 1;");
                writer.println("            applyScale();");
                writer.println("        });");
                
                // Preserve original tooltip functionality
                writer.println("        // Display details on mouse hover");
                writer.println("        flameContainer.addEventListener('mouseover', (e) => {");
                writer.println("            const target = e.target;");
                writer.println("            if (target.classList.contains('flame-cell')) {");
                writer.println("                const info = target.getAttribute('data-info');");
                writer.println("                tooltip.textContent = info;");
                writer.println("                tooltip.style.display = 'block';");
                writer.println("                ");
                writer.println("                // Calculate tooltip position");
                writer.println("                tooltip.style.left = (e.pageX + 10) + 'px';");
                writer.println("                tooltip.style.top = (e.pageY - 30) + 'px';");
                writer.println("            }");
                writer.println("        });");
                
                writer.println("        flameContainer.addEventListener('mouseout', (e) => {");
                writer.println("            if (e.target.classList.contains('flame-cell')) {");
                writer.println("                tooltip.style.display = 'none';");
                writer.println("            }");
                writer.println("        });");
                
                writer.println("        // Update tooltip position on mouse movement");
                writer.println("        flameContainer.addEventListener('mousemove', (e) => {");
                writer.println("            if (tooltip.style.display === 'block') {");
                writer.println("                tooltip.style.left = (e.pageX + 10) + 'px';");
                writer.println("                tooltip.style.top = (e.pageY - 30) + 'px';");
                writer.println("            }");
                writer.println("        });");
                
                // Initialization: display current event type
                writer.println("        // Set default selection to current event type");
                writer.println("        const currentEventType = '${properties.getFlameGraph().getEventType().name()}';");
                writer.println("        const option = filterEventType.querySelector('option[value=\"' + currentEventType + '\"]');");
                writer.println("        if (option) {");
                writer.println("            option.selected = true;");
                writer.println("        }");
                writer.println("    </script>");
                writer.println("</body>");
                writer.println("</html>");
            }
            
            return htmlFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("Failed to generate HTML flame graph: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Recursively generate flame graph HTML structure
     */
    private void generateFlameHtml(PrintWriter writer, Map<String, Integer> frameCountMap, Map<String, Set<String>> frameParentMap) {
        // Find root frames (frames without parent frames)
        Set<String> allFrames = new HashSet<>(frameCountMap.keySet());
        Set<String> childFrames = new HashSet<>();
        
        for (Set<String> children : frameParentMap.values()) {
            childFrames.addAll(children);
        }
        
        Set<String> rootFrames = new HashSet<>(allFrames);
        rootFrames.removeAll(childFrames);
        
        // Sort root frames by occurrence count
        List<String> sortedRootFrames = new ArrayList<>(rootFrames);
        sortedRootFrames.sort((a, b) -> Integer.compare(frameCountMap.get(b), frameCountMap.get(a)));
        
        // Calculate total width (based on sample count)
        int totalWidth = 10000; // Base width
        
        // Generate each row
        int yPosition = 0;
        
        // Recursively render flame graph
        for (String rootFrame : sortedRootFrames) {
            renderFrameRow(writer, rootFrame, 0, totalWidth, yPosition, frameCountMap, frameParentMap);
            yPosition += 23; // Line height + 1px spacing
        }
    }
    
    /**
     * Render a single frame row
     */
    private void renderFrameRow(PrintWriter writer, String frame, int x, int width, int y, 
                               Map<String, Integer> frameCountMap, Map<String, Set<String>> frameParentMap) {
        // Get frame count
        int count = frameCountMap.getOrDefault(frame, 1);
        
        // Add different CSS classes for different frame types
        String cssClass = "flame-cell";
        
        // Add class based on event type
        if (frame.startsWith("CPU|")) {
            cssClass += " event-cpu";
        } else if (frame.startsWith("ALLOC|")) {
            cssClass += " event-alloc";
        } else if (frame.startsWith("LOCK|")) {
            cssClass += " event-lock";
        } else if (frame.startsWith("CACHE_MISSES|")) {
            cssClass += " event-cache-misses";
        } else {
            // Add color class based on package name
            if (frame.contains(".java.")) {
                cssClass += " pkg-java";
            } else if (frame.contains(".com.")) {
                cssClass += " pkg-com";
            } else if (frame.contains(".org.")) {
                cssClass += " pkg-org";
            } else if (frame.contains(".io.")) {
                cssClass += " pkg-io";
            } else if (frame.contains(".javax.")) {
                cssClass += " pkg-javax";
            } else {
                cssClass += " pkg-other";
            }
        }
        
        // Generate frame HTML
        writer.println("            <div class=\"flame-row\" style=\"top: " + y + "px\">");
        writer.println("                <div class=\"" + cssClass + "\" ");
        writer.println("                     style=\"left: " + x + "px; width: " + width + "px;\" ");
        writer.println("                     data-info=\"Frame: " + frame + "\nCount: " + count + "\" ");
        writer.println("                >");
        
        // Limit displayed text length
        String displayText = frame;
        if (displayText.length() > 40) {
            displayText = displayText.substring(0, 37) + "...";
        }
        
        writer.println("                    " + displayText);
        writer.println("                </div>");
        writer.println("            </div>");
        
        // Recursively render child frames
        Set<String> children = frameParentMap.get(frame);
        if (children != null && !children.isEmpty()) {
            // Sort child frames by count
            List<String> sortedChildren = new ArrayList<>(children);
            sortedChildren.sort((a, b) -> Integer.compare(frameCountMap.get(b), frameCountMap.get(a)));
            
            // Calculate width for each child frame
            int childX = x;
            int totalChildCount = sortedChildren.stream().mapToInt(child -> frameCountMap.getOrDefault(child, 0)).sum();
            
            for (String child : sortedChildren) {
                int childCount = frameCountMap.getOrDefault(child, 1);
                int childWidth = (int)((double)childCount / totalChildCount * width);
                
                // Ensure minimum width
                if (childWidth < 2) {
                    childWidth = 2;
                }
                
                renderFrameRow(writer, child, childX, childWidth, y + 23, frameCountMap, frameParentMap);
                childX += childWidth;
            }
        }
    }
    
    /**
     * Generate SVG format flame graph
     */
    private String generateSvgFlameGraph(String fileName, Map<String, Integer> stackTraces) {
        try {
            File svgFile = new File(flameGraphDir, fileName + ".svg");
            
            try (PrintWriter writer = new PrintWriter(svgFile)) {
                // SVG header
                writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
                writer.println("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1200\" height=\"1000\" viewBox=\"0 0 1200 1000\">");
                writer.println("<title>Flame Graph - API Performance Analysis</title>");
                writer.println("<style>");
                writer.println(".stackframe { cursor: pointer; opacity: 0.9; transition: opacity 0.2s; }");
                writer.println(".stackframe:hover { opacity: 1; }");
                writer.println(".frame-label { font-family: monospace; font-size: 12px; dominant-baseline: middle; text-anchor: middle; }");
                writer.println(".event-cpu { fill: #00FF00; }");
                writer.println(".event-alloc { fill: #0080FF; }");
                writer.println(".event-lock { fill: #FF0000; }");
                writer.println(".event-cache-misses { fill: #FFFF00; }");
                writer.println(".header { font-family: sans-serif; font-weight: bold; }");
                writer.println(".stats { font-family: monospace; font-size: 12px; fill: #666; }");
                writer.println(".legend-item { font-family: monospace; font-size: 12px; }");
                writer.println("</style>");
                
                // Title
                writer.println("<text class=\"header\" x=\"600\" y=\"30\" font-size=\"24\" text-anchor=\"middle\" fill=\"#333\">API Call Flame Graph Analysis</text>");
                
                // Statistics information
                int totalSamples = stackTraces.values().stream().mapToInt(Integer::intValue).sum();
                writer.println("<text class=\"stats\" x=\"20\" y=\"60\">Total Samples: " + totalSamples + "</text>");
                writer.println("<text class=\"stats\" x=\"20\" y=\"80\">Stack Frames: " + stackTraces.size() + "</text>");
                writer.println("<text class=\"stats\" x=\"20\" y=\"100\">Generation Time: " + HTML_DATE_TIME_FORMATTER.format(LocalDateTime.now()) + "</text>");
                
                // Legend
                int legendX = 800;
                int legendY = 60;
                writer.println("<text class=\"header\" x=\"" + legendX + "\" y=\"" + (legendY - 20) + "\" font-size=\"16\" text-anchor=\"start\" fill=\"#333\">Legend</text>");
                writer.println("<rect x=\"" + legendX + "\" y=\"" + legendY + "\" width=\"16\" height=\"16\" class=\"event-cpu\"/>");
                writer.println("<text class=\"legend-item\" x=\"" + (legendX + 25) + "\" y=\"" + (legendY + 12) + "\" fill=\"#333\">CPU Events</text>");
                
                legendY += 25;
                writer.println("<rect x=\"" + legendX + "\" y=\"" + legendY + "\" width=\"16\" height=\"16\" class=\"event-alloc\"/>");
                writer.println("<text class=\"legend-item\" x=\"" + (legendX + 25) + "\" y=\"" + (legendY + 12) + "\" fill=\"#333\">Memory Allocation Events</text>");
                
                legendY += 25;
                writer.println("<rect x=\"" + legendX + "\" y=\"" + legendY + "\" width=\"16\" height=\"16\" class=\"event-lock\"/>");
                writer.println("<text class=\"legend-item\" x=\"" + (legendX + 25) + "\" y=\"" + (legendY + 12) + "\" fill=\"#333\">Lock Contention Events</text>");
                
                legendY += 25;
                writer.println("<rect x=\"" + legendX + "\" y=\"" + legendY + "\" width=\"16\" height=\"16\" class=\"event-cache-misses\"/>");
                writer.println("<text class=\"legend-item\" x=\"" + (legendX + 25) + "\" y=\"" + (legendY + 12) + "\" fill=\"#333\">Cache Miss Events</text>");
                
                // Flame graph container
                int graphX = 20;
                int graphY = 130;
                int graphWidth = 1160;
                int maxRows = 30; // Maximum display rows
                int barHeight = 20;
                
                // Build flame graph data structure
                Map<String, Integer> frameCountMap = new HashMap<>();
                Map<String, Set<String>> frameParentMap = new HashMap<>();
                
                // Parse stack traces and build hierarchy
                for (Map.Entry<String, Integer> entry : stackTraces.entrySet()) {
                    String stackTrace = entry.getKey();
                    int count = entry.getValue();
                    
                    String[] frames = stackTrace.split(";\\\\");
                    for (int i = 0; i < frames.length; i++) {
                        String frame = frames[i].trim();
                        if (!frame.isEmpty()) {
                            // Count occurrences of each frame
                            frameCountMap.put(frame, frameCountMap.getOrDefault(frame, 0) + count);
                            
                            // Record parent-child relationships
                            if (i > 0) {
                                String parent = frames[i - 1].trim();
                                if (!parent.isEmpty()) {
                                    frameParentMap.computeIfAbsent(parent, k -> new HashSet<>()).add(frame);
                                }
                            }
                        }
                    }
                }
                
                // Find root frames (frames without parent frames)
                Set<String> allFrames = new HashSet<>(frameCountMap.keySet());
                Set<String> childFrames = new HashSet<>();
                
                for (Set<String> children : frameParentMap.values()) {
                    childFrames.addAll(children);
                }
                
                Set<String> rootFrames = new HashSet<>(allFrames);
                rootFrames.removeAll(childFrames);
                
                // Sort root frames by occurrence count
                List<String> sortedRootFrames = new ArrayList<>(rootFrames);
                sortedRootFrames.sort((a, b) -> Integer.compare(frameCountMap.get(b), frameCountMap.get(a)));
                
                // Limit the number of root frames displayed to avoid oversized chart
                if (sortedRootFrames.size() > 10) {
                    sortedRootFrames = sortedRootFrames.subList(0, 10);
                }
                
                // Recursively render flame graph
                int currentY = graphY;
                for (String rootFrame : sortedRootFrames) {
                    if (currentY - graphY > (maxRows - 1) * (barHeight + 1)) {
                        break; // Reached maximum row limit
                    }
                    renderSvgFrameRow(writer, rootFrame, graphX, graphWidth, currentY, 0, frameCountMap, frameParentMap, barHeight, maxRows);
                    currentY += barHeight + 1; // Line height + 1px spacing
                }
                
                // Draw coordinate axes and borders
                writer.println("<rect x=\"" + (graphX - 1) + "\" y=\"" + (graphY - 1) + "\" width=\"" + (graphWidth + 2) + "\" height=\"" + (currentY - graphY + 1) + "\" fill=\"none\" stroke=\"#ccc\" stroke-width=\"1\"/>");
                
                // Bottom description
                writer.println("<text class=\"stats\" x=\"600\" y=\"" + (currentY + 30) + "\" text-anchor=\"middle\" fill=\"#666\">Note: Flame graph shows performance bottlenecks and call hierarchy of API calls</text>");
                
                writer.println("</svg>");
            }
            
            return svgFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("Failed to generate SVG flame graph: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Recursively render SVG flame graph frame row
     */
    private void renderSvgFrameRow(PrintWriter writer, String frame, int x, int width, int y, int depth, 
                                  Map<String, Integer> frameCountMap, Map<String, Set<String>> frameParentMap, 
                                  int barHeight, int maxDepth) {
        if (depth >= maxDepth) {
            return; // Reached maximum depth limit
        }
        
        // Get frame count
        int count = frameCountMap.getOrDefault(frame, 1);
        
        // Determine CSS class for the frame
        String cssClass = "stackframe";
        if (frame.startsWith("CPU|")) {
            cssClass += " event-cpu";
        } else if (frame.startsWith("ALLOC|")) {
            cssClass += " event-alloc";
        } else if (frame.startsWith("LOCK|")) {
            cssClass += " event-lock";
        } else if (frame.startsWith("CACHE_MISSES|")) {
            cssClass += " event-cache-misses";
        }
        
        // Draw rectangle
        writer.println("<rect x=\"" + x + "\" y=\"" + y + "\" width=\"" + width + "\" height=\"" + barHeight + "\" class=\"" + cssClass + "\" />");
        
        // Add hover tooltip
        writer.println("<title>Frame: " + frame + "\nCount: " + count + "</title>");
        
        // Add text label (if width is sufficient)
        if (width > 30) { // Add text only if width is sufficient
            String displayText = frame;
            if (displayText.length() > 50) {
                displayText = displayText.substring(0, 47) + "...";
            }
            // Calculate text position (center)
            int textX = x + width / 2;
            int textY = y + barHeight / 2;
            writer.println("<text class=\"frame-label\" x=\"" + textX + "\" y=\"" + textY + "\" fill=\"black\" font-size=\"10\">" + displayText + "</text>");
        }
        
        // Recursively render child frames
        Set<String> children = frameParentMap.get(frame);
        if (children != null && !children.isEmpty()) {
            // Sort child frames by count
            List<String> sortedChildren = new ArrayList<>(children);
            sortedChildren.sort((a, b) -> Integer.compare(frameCountMap.getOrDefault(b, 0), frameCountMap.getOrDefault(a, 0)));
            
            // Calculate width for each child frame
            int childX = x;
            int totalChildCount = sortedChildren.stream()
                    .mapToInt(child -> frameCountMap.getOrDefault(child, 0))
                    .sum();
            
            for (String child : sortedChildren) {
                int childCount = frameCountMap.getOrDefault(child, 1);
                int childWidth = Math.max(2, (int)((double)childCount / totalChildCount * width));
                
                renderSvgFrameRow(writer, child, childX, childWidth, y + barHeight + 1, depth + 1, 
                                 frameCountMap, frameParentMap, barHeight, maxDepth);
                childX += childWidth;
                
                // If remaining width is insufficient to display next child frame, exit loop
                if (childX >= x + width) {
                    break;
                }
            }
        }
    }
    
    /**
     * Generate JSON format flame graph data
     */
    private String generateJsonFlameGraph(String fileName, Map<String, Integer> stackTraces) {
        try {
            File jsonFile = new File(flameGraphDir, fileName + ".json");
            
            try (PrintWriter writer = new PrintWriter(jsonFile)) {
                writer.println("{");
                writer.println("  \"metadata\": {");
                String generatedAt = JSON_DATE_TIME_FORMATTER.format(LocalDateTime.now());
                writer.println("    \"generatedAt\": \"" + generatedAt + "\",");
                int totalSamples = stackTraces.values().stream().mapToInt(Integer::intValue).sum();
                writer.println("    \"totalSamples\": " + totalSamples + ",");
                writer.println("    \"stackCount\": " + stackTraces.size() + ",");
                writer.println("    \"eventType\": \"" + properties.getFlameGraph().getEventType().name() + "\"");
                writer.println("  },");
                writer.println("  \"stacks\": [");
                
                // Sort by sample count and convert to JSON
                List<Map.Entry<String, Integer>> sortedEntries = stackTraces.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toList());
                
                for (int i = 0; i < sortedEntries.size(); i++) {
                    Map.Entry<String, Integer> entry = sortedEntries.get(i);
                    String[] frames = entry.getKey().split(";\\\\");
                    // Ensure correct splitting of stack frames
                    
                    writer.println("    {");
                    writer.println("      \"count\": " + entry.getValue() + ",");
                    writer.println("      \"frames\": [");
                    
                    for (int j = 0; j < frames.length; j++) {
                        if (!frames[j].isEmpty()) {
                            String frame = frames[j].replace("\\", "\\\\").replace("\"", "\\\"");
                            writer.print("        \"" + frame);
                            if (j < frames.length - 1 && !frames[j + 1].isEmpty()) {
                                writer.println("\",");
                            } else {
                                writer.println("\"");
                            }
                        }
                    }
                    
                    writer.println("      ]");
                    if (i < sortedEntries.size() - 1) {
                        writer.println("    },");
                    } else {
                        writer.println("    }");
                    }
                }
                
                writer.println("  ]");
                writer.println("}");
            }
            
            return jsonFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("Failed to generate JSON flame graph data: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Performance analysis task inner class
     */
    private class ProfilerTask implements Runnable {
        private final String requestId;
        private final String methodSignature;
        private final Map<String, Integer> stackTraces = new HashMap<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final long startTime;
        private final int duration;
        private final ApiMonitorProperties.FlameGraphEventType eventType;

        public ProfilerTask(String requestId, String methodSignature) {
            this.requestId = requestId;
            this.methodSignature = methodSignature;
            this.startTime = System.currentTimeMillis();
            this.duration = properties.getFlameGraph().getSamplingDuration();
            this.eventType = properties.getFlameGraph().getEventType();
        }

        @Override
        public void run() {
            try {
                if (!running.get()) {
                    return;
                }

                // Check if sampling duration is reached
                if (System.currentTimeMillis() - startTime > duration) {
                    stop();
                    return;
                }

                // Execute different analysis logic based on event type
                switch (eventType) {
                    case CPU:
                        analyzeCpuUsage();
                        break;
                    case ALLOC:
                        analyzeMemoryAllocation();
                        break;
                    case LOCK:
                        analyzeLockContention();
                        break;
                    case CACHE_MISSES:
                        analyzeCacheMisses();
                        break;
                    default:
                        analyzeCpuUsage(); // Use CPU analysis by default
                }
            } catch (Exception e) {
                logger.error("Performance analysis sampling failed: {}", e.getMessage(), e);
            }
        }

        /**
         * Analyze CPU usage
         */
        private void analyzeCpuUsage() {
            // Get all active threads
            Thread[] allThreads = new Thread[Thread.activeCount() * 2];
            int threadCount = Thread.enumerate(allThreads);
            
            for (int i = 0; i < threadCount; i++) {
                Thread thread = allThreads[i];
                // Skip daemon threads and dead threads
                if (thread.isDaemon() || !thread.isAlive()) {
                    continue;
                }
                
                // Get thread stack
                StackTraceElement[] stackTraceElements = thread.getStackTrace();
                if (stackTraceElements == null || stackTraceElements.length < 3) {
                    continue;
                }
                
                // Build stack string, limit depth
                StringBuilder stackBuilder = new StringBuilder();
                int maxDepth = Math.min(stackTraceElements.length - 1, MAX_STACK_DEPTH + 3);
                
                for (int j = maxDepth; j >= 3; j--) {
                    StackTraceElement element = stackTraceElements[j];
                    String className = element.getClassName();
                    String methodName = element.getMethodName();
                    int lineNumber = element.getLineNumber();
                    
                    // Build complete method name (including class name, method name, and line number)
                    String fullMethodName = className + "." + methodName;
                    if (lineNumber > 0) {
                        fullMethodName += ":" + lineNumber;
                    }
                    
                    stackBuilder.append(fullMethodName).append(";\\\\\\\\");
                }
                
                // Add thread name and event type as stack top
                if (stackBuilder.length() > 0) {
                    stackBuilder.insert(0, "CPU|" + thread.getName() + ";\\\\\\\\");
                    
                    // Count stack occurrences
                    String stackTrace = stackBuilder.toString();
                    stackTraces.put(stackTrace, stackTraces.getOrDefault(stackTrace, 0) + 1);
                }
            }
        }

        /**
         * Analyze memory allocation
         */
        private void analyzeMemoryAllocation() {
            // Get all active threads
            Thread[] allThreads = new Thread[Thread.activeCount() * 2];
            int threadCount = Thread.enumerate(allThreads);
            
            for (int i = 0; i < threadCount; i++) {
                Thread thread = allThreads[i];
                // Skip daemon threads and dead threads
                if (thread.isDaemon() || !thread.isAlive()) {
                    continue;
                }
                
                // Get thread stack
                StackTraceElement[] stackTraceElements = thread.getStackTrace();
                if (stackTraceElements == null || stackTraceElements.length < 3) {
                    continue;
                }
                
                // Build stack string, limit depth
                StringBuilder stackBuilder = new StringBuilder();
                int maxDepth = Math.min(stackTraceElements.length - 1, MAX_STACK_DEPTH + 3);
                
                for (int j = maxDepth; j >= 3; j--) {
                    StackTraceElement element = stackTraceElements[j];
                    String className = element.getClassName();
                    String methodName = element.getMethodName();
                    int lineNumber = element.getLineNumber();
                    
                    // Build complete method name (including class name, method name, and line number)
                    String fullMethodName = className + "." + methodName;
                    if (lineNumber > 0) {
                        fullMethodName += ":" + lineNumber;
                    }
                    
                    stackBuilder.append(fullMethodName).append(";\\\\\\\\");
                }
                
                // Add thread name and event type as stack top
                if (stackBuilder.length() > 0) {
                    stackBuilder.insert(0, "ALLOC|" + thread.getName() + ";\\\\\\\\");
                    
                    // Count stack occurrences
                    String stackTrace = stackBuilder.toString();
                    stackTraces.put(stackTrace, stackTraces.getOrDefault(stackTrace, 0) + 1);
                }
            }
        }

        /**
         * Analyze lock contention
         */
        private void analyzeLockContention() {
            // Get all active threads
            Thread[] allThreads = new Thread[Thread.activeCount() * 2];
            int threadCount = Thread.enumerate(allThreads);
            
            for (int i = 0; i < threadCount; i++) {
                Thread thread = allThreads[i];
                // Skip daemon threads and dead threads
                if (thread.isDaemon() || !thread.isAlive()) {
                    continue;
                }
                
                // Check if thread is in blocking state (possibly lock contention)
                if (thread.getState() == Thread.State.BLOCKED) {
                    // Get thread stack
                    StackTraceElement[] stackTraceElements = thread.getStackTrace();
                    if (stackTraceElements == null || stackTraceElements.length < 3) {
                        continue;
                    }
                    
                    // Build stack string, limit depth
                    StringBuilder stackBuilder = new StringBuilder();
                    int maxDepth = Math.min(stackTraceElements.length - 1, MAX_STACK_DEPTH + 3);
                    
                    for (int j = maxDepth; j >= 3; j--) {
                        StackTraceElement element = stackTraceElements[j];
                        String className = element.getClassName();
                        String methodName = element.getMethodName();
                        int lineNumber = element.getLineNumber();
                        
                        // Build complete method name (including class name, method name, and line number)
                        String fullMethodName = className + "." + methodName;
                        if (lineNumber > 0) {
                            fullMethodName += ":" + lineNumber;
                        }
                        
                        stackBuilder.append(fullMethodName).append(";\\\\\\\\");
                    }
                    
                    // Add thread name and event type as stack top
                    if (stackBuilder.length() > 0) {
                        stackBuilder.insert(0, "LOCK|" + thread.getName() + ";\\\\\\\\");
                        
                        // Count stack occurrences, increase weight in case of lock contention
                        String stackTrace = stackBuilder.toString();
                        stackTraces.put(stackTrace, stackTraces.getOrDefault(stackTrace, 0) + 3);
                    }
                }
            }
        }

        /**
         * Analyze cache misses
         */
        private void analyzeCacheMisses() {
            // JVM does not directly provide cache miss information, using heuristic analysis based on CPU time and specific method characteristics here
            // In practical applications, more professional tools like Async-profiler may be needed
            
            // Get all active threads
            Thread[] allThreads = new Thread[Thread.activeCount() * 2];
            int threadCount = Thread.enumerate(allThreads);
            
            for (int i = 0; i < threadCount; i++) {
                Thread thread = allThreads[i];
                // Skip daemon threads and dead threads
                if (thread.isDaemon() || !thread.isAlive()) {
                    continue;
                }
                
                // Get thread stack
                StackTraceElement[] stackTraceElements = thread.getStackTrace();
                if (stackTraceElements == null || stackTraceElements.length < 3) {
                    continue;
                }
                
                // Check if it contains method patterns that may cause cache misses
                boolean potentialCacheMiss = false;
                for (StackTraceElement element : stackTraceElements) {
                    String methodName = element.getMethodName();
                    String className = element.getClassName();
                    
                    // Common method patterns that may involve a lot of memory access
                    if (methodName.contains("hashCode") || 
                        methodName.contains("equals") || 
                        className.contains("java.util.ArrayList") ||
                        className.contains("java.util.HashMap") ||
                        className.contains("java.nio") ||
                        className.contains("java.io")) {
                        potentialCacheMiss = true;
                        break;
                    }
                }
                
                if (potentialCacheMiss) {
                    // Build stack string, limit depth
                    StringBuilder stackBuilder = new StringBuilder();
                    int maxDepth = Math.min(stackTraceElements.length - 1, MAX_STACK_DEPTH + 3);
                    
                    for (int j = maxDepth; j >= 3; j--) {
                        StackTraceElement element = stackTraceElements[j];
                        String className = element.getClassName();
                        String methodName = element.getMethodName();
                        int lineNumber = element.getLineNumber();
                        
                        // Build complete method name (including class name, method name, and line number)
                        String fullMethodName = className + "." + methodName;
                        if (lineNumber > 0) {
                            fullMethodName += ":" + lineNumber;
                        }
                        
                        stackBuilder.append(fullMethodName).append(";\\\\\\\\");
                    }
                    
                    // Add thread name and event type as stack top
                    if (stackBuilder.length() > 0) {
                        stackBuilder.insert(0, "CACHE_MISSES|" + thread.getName() + ";\\\\\\\\");
                        
                        // Count stack occurrences
                        String stackTrace = stackBuilder.toString();
                        stackTraces.put(stackTrace, stackTraces.getOrDefault(stackTrace, 0) + 2);
                    }
                }
            }
        }

        public void stop() {
            running.set(false);
        }

        public Map<String, Integer> getStackTraces() {
            return stackTraces;
        }
        
        public String getMethodSignature() {
            return methodSignature;
        }
    }

    /**
     * Close resources
     */
    public void shutdown() {
        try {
            // Stop all active performance analysis tasks
            for (ProfilerTask task : activeProfilerTasks.values()) {
                task.stop();
            }
            
            // Clean up task map
            activeProfilerTasks.clear();
            
            // Close scheduler
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            logger.info("Performance analyzer closed successfully");
        } catch (Exception e) {
            logger.error("Failed to close performance analyzer: {}", e.getMessage(), e);
        }
    }

    /**
     * Implement DisposableBean interface's destroy method to ensure resource release when Spring container closes
     */
    @Override
    public void destroy() throws Exception {
        shutdown();
    }
}