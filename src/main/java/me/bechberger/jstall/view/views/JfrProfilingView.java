package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.JfrProfilingAnalyzer;
import me.bechberger.jstall.jfr.JfrAnalyzer;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for JfrProfilingAnalyzer results.
 * Shows JFR profiling insights: hot methods, threads, allocations, I/O.
 */
public class JfrProfilingView extends HandlebarsViewRenderer {

    public JfrProfilingView() {
        super("jfr-profiling", "jfr-profiling", JfrProfilingAnalyzer.JfrProfilingResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof JfrProfilingAnalyzer.JfrProfilingResult jfrResult) {
            JfrProfilingAnalyzer.JfrProfilingSummary summary = jfrResult.getProfilingSummary();

            // Summary
            context.put("totalSamples", summary.totalSamples());
            context.put("totalMethods", jfrResult.getHottestMethods().size());
            context.put("totalThreads", jfrResult.getHottestThreads().size());
            context.put("totalAllocations", summary.allocationEvents());
            context.put("totalIOTargets", summary.ioEvents());
            context.put("hasData", summary.totalSamples() > 0);

            // Hottest methods
            List<Map<String, Object>> methods = new ArrayList<>();
            for (JfrAnalyzer.MethodProfile m : jfrResult.getHottestMethods()) {
                Map<String, Object> method = new LinkedHashMap<>();
                method.put("name", formatMethodName(m.method()));
                method.put("fullName", m.method());
                method.put("percentage", String.format(Locale.US, "%.1f", m.percentage()));
                method.put("sampleCount", m.sampleCount());
                methods.add(method);
            }
            context.put("hottestMethods", methods);

            // Hottest threads
            List<Map<String, Object>> threads = new ArrayList<>();
            for (JfrAnalyzer.ThreadProfile t : jfrResult.getHottestThreads()) {
                Map<String, Object> thread = new LinkedHashMap<>();
                thread.put("name", t.threadName());
                thread.put("percentage", String.format(Locale.US, "%.1f", t.percentage()));
                thread.put("sampleCount", t.sampleCount());
                threads.add(thread);
            }
            context.put("hottestThreads", threads);

            // Allocation hotspots
            if (!jfrResult.getAllocationHotspots().isEmpty()) {
                List<Map<String, Object>> allocs = new ArrayList<>();
                for (var a : jfrResult.getAllocationHotspots()) {
                    Map<String, Object> alloc = new LinkedHashMap<>();
                    alloc.put("site", formatMethodName(a.allocationSite()));
                    alloc.put("size", formatBytes(a.totalBytes()));
                    alloc.put("count", a.count());
                    allocs.add(alloc);
                }
                context.put("allocationHotspots", allocs);
                context.put("hasAllocations", true);
            } else {
                context.put("hasAllocations", false);
            }

            // I/O hotspots
            if (!jfrResult.getIoHotspots().isEmpty()) {
                List<Map<String, Object>> ios = new ArrayList<>();
                for (var io : jfrResult.getIoHotspots()) {
                    Map<String, Object> ioInfo = new LinkedHashMap<>();
                    ioInfo.put("target", truncate(io.target(), 50));
                    ioInfo.put("durationMs", io.totalDuration().toMillis());
                    ioInfo.put("size", io.getFormattedSize());
                    ios.add(ioInfo);
                }
                context.put("ioHotspots", ios);
                context.put("hasIO", true);
            } else if (jfrResult.getIoSummary() != null && jfrResult.getIoSummary().totalEvents() > 0) {
                // Fallback to summary if no hotspots
                Map<String, Object> ioInfo = new LinkedHashMap<>();
                ioInfo.put("totalEvents", jfrResult.getIoSummary().totalEvents());
                ioInfo.put("totalDurationMs", jfrResult.getIoSummary().totalDuration().toMillis());
                ioInfo.put("totalBytes", formatBytes(jfrResult.getIoSummary().totalBytes()));
                context.put("ioSummaryInfo", ioInfo);
                context.put("hasIO", true);
            } else {
                context.put("hasIO", false);
            }

            // Temporal profiling analysis (multi-dump)
            JfrProfilingAnalyzer.TemporalProfilingAnalysis temporal = jfrResult.getTemporal();
            if (temporal != null && temporal.hasHotMethodEvolution()) {
                context.put("hasTemporal", true);
                context.put("isMultiDump", true);

                // Format method evolutions
                List<Map<String, Object>> methodEvos = new ArrayList<>();
                for (JfrProfilingAnalyzer.MethodEvolution evo : temporal.methodEvolutions()) {
                    Map<String, Object> evoInfo = new LinkedHashMap<>();
                    evoInfo.put("method", formatMethodName(evo.method()));
                    evoInfo.put("fullMethod", evo.method());
                    evoInfo.put("firstPct", String.format(Locale.US, "%.1f", evo.firstPercentage()));
                    evoInfo.put("lastPct", String.format(Locale.US, "%.1f", evo.lastPercentage()));
                    evoInfo.put("change", String.format(Locale.US, "%+.1f", evo.change()));
                    evoInfo.put("changePercent", String.format(Locale.US, "%+.1f", evo.changePercent()));
                    evoInfo.put("trend", evo.getTrend());
                    evoInfo.put("isEmerging", evo.isEmerging());
                    evoInfo.put("isIncreasing", evo.isIncreasing());
                    evoInfo.put("isDecreasing", evo.isDecreasing());
                    evoInfo.put("cpuHistory", evo.cpuPercentagesPerDump());
                    methodEvos.add(evoInfo);
                }
                context.put("methodEvolutions", methodEvos);

                // Allocation trend
                if (temporal.hasAllocationTrend()) {
                    var allocTrend = temporal.allocationTrend();
                    Map<String, Object> allocInfo = new LinkedHashMap<>();
                    allocInfo.put("direction", allocTrend.direction().name());
                    allocInfo.put("changeRate", String.format(Locale.US, "%.1f", allocTrend.changeRate()));
                    allocInfo.put("isIncreasing", allocTrend.isIncreasing());
                    allocInfo.put("isDecreasing", allocTrend.isDecreasing());
                    allocInfo.put("history", temporal.allocationBytesPerDump());
                    context.put("allocationTrend", allocInfo);
                }

                // I/O trend
                if (temporal.hasIOTrend()) {
                    var ioTrend = temporal.ioTrend();
                    Map<String, Object> ioTrendInfo = new LinkedHashMap<>();
                    ioTrendInfo.put("direction", ioTrend.direction().name());
                    ioTrendInfo.put("changeRate", String.format(Locale.US, "%.1f", ioTrend.changeRate()));
                    ioTrendInfo.put("isIncreasing", ioTrend.isIncreasing());
                    ioTrendInfo.put("isDecreasing", ioTrend.isDecreasing());
                    ioTrendInfo.put("history", temporal.ioEventsPerDump());
                    context.put("ioTrend", ioTrendInfo);
                }

                // Dump count for timeline
                int dumpCount = Math.max(
                        temporal.allocationBytesPerDump().size(),
                        temporal.ioEventsPerDump().size()
                );
                List<Integer> dumpLabels = new ArrayList<>();
                for (int i = 1; i <= dumpCount; i++) {
                    dumpLabels.add(i);
                }
                context.put("dumpLabels", dumpLabels);

            } else {
                context.put("hasTemporal", false);
                context.put("isMultiDump", false);
            }

            // Chart data
            context.put("methodNames", methods.stream().map(m -> m.get("name")).toList());
            context.put("methodPercentages", methods.stream()
                    .map(m -> Double.parseDouble((String) m.get("percentage"))).toList());
            context.put("threadNames", threads.stream().map(t -> t.get("name")).toList());
            context.put("threadPercentages", threads.stream()
                    .map(t -> Double.parseDouble((String) t.get("percentage"))).toList());
        }

        return context;
    }

    private String formatMethodName(String method) {
        if (method == null) return "unknown";
        int lastDot = method.lastIndexOf('.');
        if (lastDot > 0) {
            int prevDot = method.lastIndexOf('.', lastDot - 1);
            if (prevDot > 0) return "..." + method.substring(prevDot + 1);
        }
        return method;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
}