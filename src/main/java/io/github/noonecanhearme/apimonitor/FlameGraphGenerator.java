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
            logger.debug("Attempting to start profiling for requestId={}, method={}", requestId, methodSignature);
            
            // Check if flame graph feature is enabled
            if (!properties.getFlameGraph().isEnabled()) {
                logger.debug("Flame graph feature is disabled, skipping profiling for requestId={}", requestId);
                return;
            }

            // Check if CPU time analysis is supported
            if (!threadMXBean.isThreadCpuTimeSupported()) {
                logger.warn("JVM does not support CPU time analysis, unable to generate flame graph for requestId={}", requestId);
                return;
            }

            // Enable CPU time analysis
            if (!threadMXBean.isThreadCpuTimeEnabled()) {
                logger.debug("Enabling CPU time analysis for JVM");
                threadMXBean.setThreadCpuTimeEnabled(true);
            }

            // Check if request is already being profiled
            if (activeProfilerTasks.containsKey(requestId)) {
                logger.warn("Profiling already active for requestId={}, skipping duplicate request", requestId);
                return;
            }

            // Get and log current configuration
            int samplingRate = properties.getFlameGraph().getSamplingRate();
            int duration = properties.getFlameGraph().getSamplingDuration();
            String format = properties.getFlameGraph().getFormat();
            ApiMonitorProperties.FlameGraphEventType eventType = properties.getFlameGraph().getEventType();
            
            logger.debug("Profiler configuration for requestId={}: samplingRate={}ms, duration={}ms, format={}, eventType={}",
                        requestId, samplingRate, duration, format, eventType);

            // Create profiling task
            ProfilerTask task = new ProfilerTask(requestId, methodSignature);
            activeProfilerTasks.put(requestId, task);

            // Schedule the task
            logger.debug("Scheduling profiling task for requestId={} with initial delay 0ms and period {}ms", 
                        requestId, samplingRate);
            scheduler.scheduleAtFixedRate(task, 0, samplingRate, TimeUnit.MILLISECONDS);

            logger.info("Started profiling for request [{}] {}", 
                       requestId, methodSignature != null ? "(" + methodSignature + ")" : "");
            logger.debug("Active profiling tasks count: {}", activeProfilerTasks.size());
        } catch (Exception e) {
            logger.error("Failed to start profiling for requestId={}: {}", requestId, e.getMessage(), e);
        }
    }

    /**
     * Stop profiling and generate flame graph
     * @return Flame graph file path, null if not generated
     */
    public String stopProfiling(String requestId) {
        try {
            logger.debug("Attempting to stop profiling for requestId={}", requestId);
            
            ProfilerTask task = activeProfilerTasks.remove(requestId);
            if (task != null) {
                logger.debug("Found active profiling task for requestId={}, stopping it", requestId);
                task.stop();
                
                // Wait for sampling task to complete
                int waitTime = 100;
                logger.debug("Waiting {}ms for sampling task to complete for requestId={}", waitTime, requestId);
                Thread.sleep(waitTime);
                
                // Get collected stack traces
                Map<String, Integer> stackTraces = task.getStackTraces();
                logger.debug("Task for requestId={} collected {} stack trace samples", 
                           requestId, stackTraces.size());
                
                // Generate flame graph
                String flameGraphPath = generateFlameGraph(requestId, stackTraces, task.getMethodSignature());
                
                logger.info("Profiling for request [{}] completed, flame graph generated", requestId);
                logger.debug("Active profiling tasks count after stopping: {}", activeProfilerTasks.size());
                return flameGraphPath;
            } else {
                logger.warn("No active profiling task found for requestId={}", requestId);
            }
        } catch (Exception e) {
            logger.error("Failed to stop profiling for requestId={}: {}", requestId, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Generate flame graph file
     */
    private String generateFlameGraph(String requestId, Map<String, Integer> stackTraces, String methodSignature) {
        try {
            logger.debug("Starting to generate flame graph for requestId={}, method={}", requestId, methodSignature);
            
            if (stackTraces.isEmpty()) {
                logger.warn("Not enough stack information collected for requestId={}, unable to generate flame graph", requestId);
                return null;
            }
            
            logger.debug("Collected {} stack trace samples for requestId={}", stackTraces.size(), requestId);
            
            // Calculate total samples before filtering
            int totalSamples = stackTraces.values().stream().mapToInt(Integer::intValue).sum();
            logger.debug("Total stack trace samples count: {} for requestId={}", totalSamples, requestId);

            // Filter low-frequency samples to reduce noise
            Map<String, Integer> filteredStacks = stackTraces.entrySet().stream()
                    .filter(entry -> entry.getValue() >= MIN_SAMPLE_COUNT)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
            logger.debug("After filtering, remaining {} stack trace samples for requestId={}", filteredStacks.size(), requestId);
            
            if (filteredStacks.isEmpty()) {
                logger.warn("Not enough stack information after filtering (min count: {}) for requestId={}, unable to generate flame graph", 
                           MIN_SAMPLE_COUNT, requestId);
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
            
            logger.debug("Generated file name: {} for requestId={}", fileName, requestId);
            
            // Generate folded stack format file (raw data)
            File flameGraphFile = new File(flameGraphDir, fileName + ".txt");
            logger.debug("Saving raw stack data to: {} for requestId={}", flameGraphFile.getAbsolutePath(), requestId);
            
            int writtenLines = 0;
            try (FileWriter writer = new FileWriter(flameGraphFile)) {
                for (Map.Entry<String, Integer> entry : filteredStacks.entrySet()) {
                    writer.write(entry.getKey() + " " + entry.getValue() + "\n");
                    writtenLines++;
                }
            }
            
            logger.debug("Successfully wrote {} lines of stack data for requestId={}", writtenLines, requestId);
            
            // Generate flame graph according to configured format
            String format = properties.getFlameGraph().getFormat().toLowerCase();
            logger.debug("Generating {} format flame graph for requestId={}", format, requestId);
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
                    logger.warn("Unsupported flame graph format: {}, generating HTML format by default for requestId={}", 
                               format, requestId);
                    outputPath = generateHtmlFlameGraph(fileName, filteredStacks);
                    break;
            }

            logger.info("Flame graph raw data saved to: {}", flameGraphFile.getAbsolutePath());
            if (outputPath != null) {
                logger.info("{} format flame graph saved to: {}", format.toUpperCase(), outputPath);
                logger.debug("Flame graph generation completed successfully for requestId={}", requestId);
                return outputPath;
            } else {
                logger.warn("Failed to generate formatted flame graph, only raw data is available for requestId={}", requestId);
            }
            
            return flameGraphFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("Failed to generate flame graph for requestId={}: {}", requestId, e.getMessage(), e);
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
                // Generate HTML file exactly matching the reference format
                writer.println("<!DOCTYPE html>");
                writer.println("<html lang='en'>");
                writer.println("<head>");
                writer.println("<meta charset='utf-8'>");
                writer.println("<style>");
                writer.println("\tbody {margin: 0; padding: 10px; background-color: #ffffff}");
                writer.println("\th1 {margin: 5px 0 0 0; font-size: 18px; font-weight: normal; text-align: center}");
                writer.println("\theader {margin: -24px 0 5px 0; line-height: 24px}");
                writer.println("\tbutton {font: 12px sans-serif; cursor: pointer}");
                writer.println("\tp {margin: 5px 0 5px 0}");
                writer.println("\ta {color: #0366d6}");
                writer.println("\t#hl {position: absolute; display: none; overflow: hidden; white-space: nowrap; pointer-events: none; background-color: #ffffe0; outline: 1px solid #ffc000; height: 15px}");
                writer.println("\t#hl span {padding: 0 3px 0 3px}");
                writer.println("\t#status {overflow: hidden; white-space: nowrap}");
                writer.println("\t#match {overflow: hidden; white-space: nowrap; display: none; float: right; text-align: right}");
                writer.println("\t#reset {cursor: pointer}");
                writer.println("\t#canvas {width: 100%; height: 4224px}");
                writer.println("</style>");
                writer.println("</head>");
                writer.println("<body style='font: 12px Verdana, sans-serif'>");
                writer.println("<h1>CPU profile</h1>");
                writer.println("<header style='text-align: left'><button id='reverse' title='Reverse'>&#x1f53b;</button>&nbsp;&nbsp;<button id='search' title='Search'>&#x1f50d;</button></header>");
                writer.println("<header style='text-align: right'>Produced by <a href='https://github.com/jvm-profiling-tools/async-profiler'>async-profiler</a></header>");
                writer.println("<canvas id='canvas'></canvas>");
                writer.println("<div id='hl'><span></span></div>");
                writer.println("<p id='match'>Matched: <span id='matchval'></span> <span id='reset' title='Clear'>&#x274c;</span></p>");
                writer.println("<p id='status'>&nbsp;</p>");
                writer.println("<script>");
                writer.println("\t// Copyright 2020 Andrei Pangin");
                writer.println("\t// Licensed under the Apache License, Version 2.0.");
                writer.println("\t'use strict';");
                writer.println("\tvar root, rootLevel, px, pattern;");
                writer.println("\tvar reverse = false;");
                writer.println("\tconst levels = Array(264);");
                writer.println("\tfor (let h = 0; h < levels.length; h++) {");
                writer.println("\t\tlevels[h] = [];");
                writer.println("\t}");
                writer.println();
                writer.println("\tconst canvas = document.getElementById('canvas');");
                writer.println("\tconst c = canvas.getContext('2d');");
                writer.println("\tconst hl = document.getElementById('hl');");
                writer.println("\tconst status = document.getElementById('status');");
                writer.println();
                writer.println("\tconst canvasWidth = canvas.offsetWidth;");
                writer.println("\tconst canvasHeight = canvas.offsetHeight;");
                writer.println("\tcanvas.style.width = canvasWidth + 'px';");
                writer.println("\tcanvas.width = canvasWidth * (devicePixelRatio || 1);");
                writer.println("\tcanvas.height = canvasHeight * (devicePixelRatio || 1);");
                writer.println("\tif (devicePixelRatio) c.scale(devicePixelRatio, devicePixelRatio);");
                writer.println("\tc.font = document.body.style.font;");
                writer.println();
                writer.println("\tconst palette = [");
                writer.println("\t\t[0xb2e1b2, 20, 20, 20],");
                writer.println("\t\t[0x50e150, 30, 30, 30],");
                writer.println("\t\t[0x50cccc, 30, 30, 30],");
                writer.println("\t\t[0xe15a5a, 30, 40, 40],");
                writer.println("\t\t[0xc8c83c, 30, 30, 10],");
                writer.println("\t\t[0xe17d00, 30, 30,  0],");
                writer.println("\t\t[0xcce880, 20, 20, 20],");
                writer.println("\t];");
                writer.println();
                writer.println("\tfunction getColor(p) {");
                writer.println("\t\tconst v = Math.random();");
                writer.println("\t\treturn '#' + (p[0] + ((p[1] * v) << 16 | (p[2] * v) << 8 | (p[3] * v))).toString(16);");
                writer.println("\t}");
                writer.println();
                writer.println("\tfunction f(level, left, width, type, title, inln, c1, int) {");
                writer.println("\t\tlevels[level].push({left: left, width: width, color: getColor(palette[type]), title: title,");
                writer.println("\t\t\tdetails: (int ? ', int=' + int : '') + (c1 ? ', c1=' + c1 : '') + (inln ? ', inln=' + inln : '')");
                writer.println("\t\t});");
                writer.println("\t}");
                writer.println();
                writer.println("\tfunction samples(n) {");
                writer.println("\t\treturn n === 1 ? '1 sample' : n.toString().replace(/\\B(?=(\\d{3})+(?!\\d))/g, ',') + ' samples';");
                writer.println("\t}");
                writer.println();
                writer.println("\tfunction pct(a, b) {");
                writer.println("\t\treturn a >= b ? '100' : (100 * a / b).toFixed(2);");
                writer.println("\t}");
                writer.println();
                writer.println("\tfunction findFrame(frames, x) {");
                writer.println("\t\tlet left = 0;");
                writer.println("\t\tlet right = frames.length - 1;");
                writer.println();
                writer.println("\t\twhile (left <= right) {");
                writer.println("\t\t\tconst mid = (left + right) >>> 1;");
                writer.println("\t\t\tconst f = frames[mid];");
                writer.println();
                writer.println("\t\t\tif (f.left > x) {");
                writer.println("\t\t\t\tright = mid - 1;");
                writer.println("\t\t\t} else if (f.left + f.width <= x) {");
                writer.println("\t\t\t\tleft = mid + 1;");
                writer.println("\t\t\t} else {");
                writer.println("\t\t\t\treturn f;");
                writer.println("\t\t\t}");
                writer.println("\t\t}");
                writer.println();
                writer.println("\t\tif (frames[left] && (frames[left].left - x) * px < 0.5) return frames[left];");
                writer.println("\t\tif (frames[right] && (x - (frames[right].left + frames[right].width)) * px < 0.5) return frames[right];");
                writer.println();
                writer.println("\t\treturn null;");
                writer.println("\t}");
                writer.println();
                writer.println("\tfunction search(r) {");
                writer.println("\t\tif (r === true && (r = prompt('Enter regexp to search:', '')) === null) {");
                writer.println("\t\t\treturn;");
                writer.println("\t\t}");
                writer.println();
                writer.println("\t\tpattern = r ? RegExp(r) : undefined;");
                writer.println("\t\tconst matched = render(root, rootLevel);");
                writer.println("\t\tdocument.getElementById('matchval').textContent = pct(matched, root.width) + '%';");
                writer.println("\t\tdocument.getElementById('match').style.display = r ? 'inherit' : 'none';");
                writer.println("\t}");
                writer.println();
                writer.println("\tfunction render(newRoot, newLevel) {");
                writer.println("\t\tif (root) {");
                writer.println("\t\t\tc.fillStyle = '#ffffff';");
                writer.println("\t\t\tc.fillRect(0, 0, canvasWidth, canvasHeight);");
                writer.println("\t\t}");
                writer.println();
                writer.println("\t\troot = newRoot || levels[0][0];");
                writer.println("\t\trootLevel = newLevel || 0;");
                writer.println("\t\tpx = canvasWidth / root.width;");
                writer.println();
                writer.println("\t\t// Define visible range");
                writer.println("\t\tconst x0 = root.left;");
                writer.println("\t\tconst x1 = root.left + root.width;");
                writer.println("\t\t// Properly declare marked variable as object");
                writer.println("\t\tlet marked = {};");
                writer.println();
                writer.println("\t\tfunction mark(f) {");
                writer.println("\t\t\treturn marked[f.left] >= f.width || (marked[f.left] = f.width);");
                writer.println("\t\t}");
                writer.println();
                writer.println("\t\tfunction totalMarked() {");
                writer.println("\t\t\tlet total = 0;");
                writer.println("\t\t\tlet left = 0;");
                writer.println("\t\t\tObject.keys(marked).sort(function(a, b) { return a - b; }).forEach(function(x) {");
                writer.println("\t\t\t\tif (+x >= left) {");
                writer.println("\t\t\t\t\ttotal += marked[x];");
                writer.println("\t\t\t\t\tleft = +x + marked[x];");
                writer.println("\t\t\t\t}");
                writer.println("\t\t\t});");
                writer.println("\t\t\treturn total;");
                writer.println("\t\t}");
                writer.println();
                writer.println("\t\tfunction drawFrame(f, y, alpha) {");
                writer.println("\t\t\tif (f.left < x1 && f.left + f.width > x0) {");
                writer.println("\t\t\t\tc.fillStyle = pattern && f.title.match(pattern) && mark(f) ? '#ee00ee' : f.color;");
                writer.println("\t\t\t\tc.fillRect((f.left - x0) * px, y, f.width * px, 15);");
                writer.println();
                writer.println("\t\t\t\tif (f.width * px >= 21) {");
                writer.println("\t\t\t\t\tconst chars = Math.floor(f.width * px / 7);");
                writer.println("\t\t\t\t\tconst title = f.title.length <= chars ? f.title : f.title.substring(0, chars - 2) + '..';");
                writer.println("\t\t\t\t\tc.fillStyle = '#000000';");
                writer.println("\t\t\t\t\tc.fillText(title, Math.max(f.left - x0, 0) * px + 3, y + 12, f.width * px - 6);");
                writer.println("\t\t\t\t}");
                writer.println();
                writer.println("\t\t\t\tif (alpha) {");
                writer.println("\t\t\t\t\tc.fillStyle = 'rgba(255, 255, 255, 0.5)';");
                writer.println("\t\t\t\t\tc.fillRect((f.left - x0) * px, y, f.width * px, 15);");
                writer.println("\t\t\t\t}");
                writer.println("\t\t\t}");
                writer.println("\t\t}");
                writer.println();
                writer.println("\t\tfor (let h = 0; h < levels.length; h++) {");
                writer.println("\t\t\tconst y = reverse ? h * 16 : canvasHeight - (h + 1) * 16;");
                writer.println("\t\t\tconst frames = levels[h];");
                writer.println("\t\t\tfor (let i = 0; i < frames.length; i++) {");
                writer.println("\t\t\t\tdrawFrame(frames[i], y, h < rootLevel);");
                writer.println("\t\t\t}");
                writer.println("\t\t}");
                writer.println();
                writer.println("\t\treturn totalMarked();");
                writer.println("\t}");
                writer.println();
                writer.println("\tcanvas.onmousemove = function() {");
                writer.println("\t\tconst h = Math.floor((reverse ? event.offsetY : (canvasHeight - event.offsetY)) / 16);");
                writer.println("\t\tif (h >= 0 && h < levels.length) {");
                writer.println("\t\t\tconst f = findFrame(levels[h], event.offsetX / px + root.left);");
                writer.println("\t\t\tif (f) {");
                writer.println("\t\t\t\thl.style.left = (Math.max(f.left - root.left, 0) * px + canvas.offsetLeft) + 'px';");
                writer.println("\t\t\t\thl.style.width = (Math.min(f.width, root.width) * px) + 'px';");
                writer.println("\t\t\t\thl.style.top = ((reverse ? h * 16 : canvasHeight - (h + 1) * 16) + canvas.offsetTop) + 'px';");
                writer.println("\t\t\t\thl.firstChild.textContent = f.title;");
                writer.println("\t\t\t\thl.style.display = 'block';");
                writer.println("\t\t\t\tcanvas.title = f.title + '\\n(' + samples(f.width) + f.details + ', ' + pct(f.width, levels[0][0].width) + '%)';");
                writer.println("\t\t\t\tcanvas.style.cursor = 'pointer';");
                writer.println("\t\t\t\tcanvas.onclick = function() {");
                writer.println("\t\t\t\t\tif (f != root) {");
                writer.println("\t\t\t\t\t\trender(f, h);");
                writer.println("\t\t\t\t\t\tcanvas.onmousemove();");
                writer.println("\t\t\t\t\t}");
                writer.println("\t\t\t\t};");
                writer.println("\t\t\t\tstatus.textContent = 'Function: ' + canvas.title;");
                writer.println("\t\t\t\treturn;");
                writer.println("\t\t\t}");
                writer.println("\t\t}");
                writer.println("\t\tcanvas.onmouseout();");
                writer.println("\t}");
                writer.println();
                writer.println("\tcanvas.onmouseout = function() {");
                writer.println("\t\thl.style.display = 'none';");
                writer.println("\t\tstatus.textContent = '\\xa0';");
                writer.println("\t\tcanvas.title = '';");
                writer.println("\t\tcanvas.style.cursor = '';");
                writer.println("\t\tcanvas.onclick = '';");
                writer.println("\t}");
                writer.println();
                writer.println("\tdocument.getElementById('reverse').onclick = function() {");
                writer.println("\t\treverse = !reverse;");
                writer.println("\t\trender();");
                writer.println("\t}");
                writer.println();
                writer.println("\tdocument.getElementById('search').onclick = function() {");
                writer.println("\t\tsearch(true);");
                writer.println("\t}");
                writer.println();
                writer.println("\tdocument.getElementById('reset').onclick = function() {");
                writer.println("\t\tsearch(false);");
                writer.println("\t}");
                writer.println();
                writer.println("\twindow.onkeydown = function() {");
                writer.println("\t\tif (event.ctrlKey && event.keyCode === 70) {");
                writer.println("\t\t\tevent.preventDefault();");
                writer.println("\t\t\tsearch(true);");
                writer.println("\t\t} else if (event.keyCode === 27) {");
                writer.println("\t\t\tsearch(false);");
                writer.println("\t\t}");
                writer.println("\t}");
                writer.println();
                
                // Process stack traces and generate f() calls
                processStackTraces(writer, stackTraces);
                
                // Initialize and render
                writer.println("\t// Initialize and render");
                writer.println("\tif (levels[0].length > 0) {");
                writer.println("\t\troot = levels[0][0];");
                writer.println("\t\trootLevel = 0;");
                writer.println("\t\tpx = canvasWidth / root.width;");
                writer.println("\t\trender();");
                writer.println("\t}");
                writer.println("</script>");
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
     * Process stack traces and generate f() function calls for JavaScript
     */
    private void processStackTraces(PrintWriter writer, Map<String, Integer> stackTraces) {
        logger.debug("Processing {} stack traces", stackTraces.size());
        
        // 计算总样本数
        int totalSamples = stackTraces.values().stream().mapToInt(Integer::intValue).sum();
        logger.debug("Total samples: {}", totalSamples);
        
        // 直接为每个堆栈跟踪生成HTML数据
        // 为了简单起见，我们将所有堆栈跟踪合并到一个层次结构中
        
        // 首先写入根节点
        writer.println("f(0, 0, " + totalSamples + ", 3, 'all');");
        
        // 处理每个堆栈跟踪
        int currentLeft = 0;
        
        for (Map.Entry<String, Integer> entry : stackTraces.entrySet()) {
            String stack = entry.getKey();
            int count = entry.getValue();
            logger.debug("Processing stack: {} with count: {}", stack, count);
            
            // 按分号分割堆栈帧
            String[] frames = stack.split(";\\s*");
            
            if (frames.length > 0) {
                // 处理第一帧，提取类型（如CPU|main -> main）
                String firstFrame = frames[0];
                if (firstFrame.contains("|")) {
                    firstFrame = firstFrame.substring(firstFrame.indexOf("|") + 1);
                }
                
                // 写入第一帧（通常是main）
                writer.println("f(1, " + currentLeft + ", " + count + ", 3, '" + escapeString(firstFrame) + "');");
                
                // 写入所有子帧
                for (int i = 1; i < frames.length; i++) {
                    String frame = frames[i].trim();
                    if (!frame.isEmpty()) {
                        writer.println("f(" + (i + 1) + ", " + currentLeft + ", " + count + ", 3, '" + escapeString(frame) + "');");
                    }
                }
                
                currentLeft += count;
            }
        }
        
        logger.debug("Completed generating frame calls for all stack traces");
    }
    
    /**
     * Recursively generate f() function calls for frames
     */
    private int generateFrameCalls(PrintWriter writer, Map<String, Map<String, Integer>> frameHierarchy, 
                                  String parentKey, int level, int left, int totalSamples) {
        if (!frameHierarchy.containsKey(parentKey)) {
            logger.debug("No children for frame: {}", parentKey);
            return 0;
        }
        
        Map<String, Integer> children = frameHierarchy.get(parentKey);
        int currentPosition = left;
        
        // For root, create a level 0 frame that represents the total
        if (parentKey.equals("root")) {
            // Write f() call for the root level (level 0)
            writer.println(String.format("\tf(%d, %d, %d, %d, '%s');", 
                0, left, totalSamples, 3, "all"));
            logger.debug("Generated root frame with total samples: {}", totalSamples);
        } else {
            // For non-root frames, find the count from parent's children map
            int frameCount = 0;
            for (Map.Entry<String, Map<String, Integer>> entry : frameHierarchy.entrySet()) {
                if (entry.getValue().containsKey(parentKey)) {
                    frameCount = entry.getValue().get(parentKey);
                    break;
                }
            }
            
            // If we can't find the count, use the sum of children's counts as fallback
            if (frameCount == 0 && !children.isEmpty()) {
                frameCount = children.values().stream().mapToInt(Integer::intValue).sum();
            }
            
            // Ensure frameCount is at least 1 to ensure it's visible
            if (frameCount == 0) {
                frameCount = 1;
            }
            
            // Write f() call for this frame at the current level
            writer.println(String.format("\tf(%d, %d, %d, %d, '%s');", 
                level, left, frameCount, 3, escapeString(parentKey)));
            logger.debug("Generated frame at level {}: {} with count: {}", level, parentKey, frameCount);
        }
        
        // Process children
        for (Map.Entry<String, Integer> entry : children.entrySet()) {
            String frame = entry.getKey();
            int count = entry.getValue();
            
            // Skip event type frames or internal frames
            if (!frame.startsWith("[not_walkable_") && !frame.equals("[unknown]")) {
                // Process nested children first to get correct width
                int childWidth = generateFrameCalls(writer, frameHierarchy, frame, level + 1, currentPosition, totalSamples);
                
                // If childWidth is 0, use the count as width (this is a leaf node)
                if (childWidth == 0) {
                    childWidth = count;
                }
                
                currentPosition += childWidth;
            }
        }
        
        // Return the total width occupied by this node and its children
        int totalWidth = currentPosition - left;
        logger.debug("Total width for frame {}: {}", parentKey, totalWidth);
        return totalWidth;
    }
    
    /**
     * Escape string for JavaScript
     */
    private String escapeString(String str) {
        return str.replace("'", "\\'")
                 .replace("\\", "\\\\")
                 .replace("\n", "\\n")
                 .replace("\r", "\\r")
                 .replace("\t", "\\t");
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
                    String[] frames = entry.getKey().split(";\\");
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
                    logger.debug("Profiler task for requestId={} is not running, skipping", requestId);
                    return;
                }

                // Check if sampling duration is reached
                if (System.currentTimeMillis() - startTime > duration) {
                    logger.debug("Profiler task for requestId={} reached duration limit, stopping", requestId);
                    stop();
                    return;
                }

                // Execute different analysis logic based on event type
                logger.trace("Executing analysis for requestId={}, eventType={}", requestId, eventType);
                switch (eventType) {
                    case CPU:
                        analyzeCpuUsage();
                        logger.trace("CPU analysis completed for requestId={}", requestId);
                        break;
                    case ALLOC:
                        analyzeMemoryAllocation();
                        logger.trace("Memory allocation analysis completed for requestId={}", requestId);
                        break;
                    case LOCK:
                        analyzeLockContention();
                        logger.trace("Lock contention analysis completed for requestId={}", requestId);
                        break;
                    case CACHE_MISSES:
                        analyzeCacheMisses();
                        logger.trace("Cache misses analysis completed for requestId={}", requestId);
                        break;
                    default:
                        logger.warn("Unknown eventType: {}, defaulting to CPU analysis for requestId={}", eventType, requestId);
                        analyzeCpuUsage(); // Use CPU analysis by default
                }
                logger.debug("Analysis iteration completed for requestId={}, collected {} stack traces", 
                            requestId, stackTraces.size());
            } catch (Exception e) {
                logger.error("Performance analysis sampling failed for requestId={}: {}", 
                           requestId, e.getMessage(), e);
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