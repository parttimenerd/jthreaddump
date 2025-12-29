package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.IOBlockAnalyzer;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputFormat;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for IOBlockAnalyzer results.
 * Shows threads blocked on I/O operations.
 */
public class IOBlockView extends HandlebarsViewRenderer {

    public IOBlockView() {
        super("io-block", "io-block", IOBlockAnalyzer.IOBlockResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof IOBlockAnalyzer.IOBlockResult ioResult) {
            IOBlockAnalyzer.IOBlockSummary summary = ioResult.getIOSummary();

            // Summary data
            context.put("totalBlocked", summary.totalBlocked());
            context.put("hasBlocked", summary.totalBlocked() > 0);
            context.put("mostCommonType", summary.mostCommonType() != null
                    ? summary.mostCommonType().getDisplayName() : "None");

            // By type breakdown
            List<Map<String, Object>> byType = new ArrayList<>();
            for (Map.Entry<IOBlockAnalyzer.IOType, Integer> entry : summary.countsByType().entrySet()) {
                Map<String, Object> typeInfo = new LinkedHashMap<>();
                typeInfo.put("type", entry.getKey().getDisplayName());
                typeInfo.put("typeKey", entry.getKey().name());
                typeInfo.put("count", entry.getValue());
                byType.add(typeInfo);
            }
            // Sort by count descending
            byType.sort((a, b) -> ((Integer) b.get("count")).compareTo((Integer) a.get("count")));
            context.put("byType", byType);
            context.put("typeLabels", byType.stream().map(m -> m.get("type")).toList());
            context.put("typeCounts", byType.stream().map(m -> m.get("count")).toList());

            // Multi-dump support: group blocked threads by dump index
            List<IOBlockAnalyzer.IOBlockedThread> allBlocked = ioResult.getBlockedThreads();
            int maxDumpIndex = allBlocked.stream().mapToInt(IOBlockAnalyzer.IOBlockedThread::dumpIndex).max().orElse(0);
            context.put("dumpCount", maxDumpIndex + 1);
            context.put("isMultiDump", maxDumpIndex > 0);

            if (maxDumpIndex > 0) {
                // Per-dump statistics for tables and charts
                List<Map<String, Object>> perDumpStats = new ArrayList<>();
                List<Integer> dumpLabels = new ArrayList<>();
                List<Integer> blockedCounts = new ArrayList<>();
                Map<String, List<Integer>> byTypePerDump = new LinkedHashMap<>();

                // Initialize type tracking
                for (IOBlockAnalyzer.IOType type : IOBlockAnalyzer.IOType.values()) {
                    byTypePerDump.put(type.getDisplayName(), new ArrayList<>());
                }

                for (int d = 0; d <= maxDumpIndex; d++) {
                    final int dumpIdx = d;
                    dumpLabels.add(d + 1);

                    List<IOBlockAnalyzer.IOBlockedThread> dumpBlocked = allBlocked.stream()
                            .filter(bt -> bt.dumpIndex() == dumpIdx)
                            .toList();

                    blockedCounts.add(dumpBlocked.size());

                    // Count by type for this dump
                    Map<IOBlockAnalyzer.IOType, Long> typeCounts = new EnumMap<>(IOBlockAnalyzer.IOType.class);
                    for (IOBlockAnalyzer.IOBlockedThread bt : dumpBlocked) {
                        typeCounts.merge(bt.ioType(), 1L, Long::sum);
                    }

                    for (IOBlockAnalyzer.IOType type : IOBlockAnalyzer.IOType.values()) {
                        byTypePerDump.get(type.getDisplayName()).add(typeCounts.getOrDefault(type, 0L).intValue());
                    }

                    Map<String, Object> dumpStat = new LinkedHashMap<>();
                    dumpStat.put("dumpIndex", d + 1);
                    dumpStat.put("blockedCount", dumpBlocked.size());
                    dumpStat.put("socketRead", typeCounts.getOrDefault(IOBlockAnalyzer.IOType.SOCKET_READ, 0L));
                    dumpStat.put("fileRead", typeCounts.getOrDefault(IOBlockAnalyzer.IOType.FILE_READ, 0L));
                    dumpStat.put("database", typeCounts.getOrDefault(IOBlockAnalyzer.IOType.DATABASE, 0L));
                    dumpStat.put("selector", typeCounts.getOrDefault(IOBlockAnalyzer.IOType.SELECTOR, 0L));
                    perDumpStats.add(dumpStat);
                }

                context.put("perDumpStats", perDumpStats);
                context.put("dumpLabels", dumpLabels);
                context.put("blockedCountsPerDump", blockedCounts);

                // Filter to only types that have data
                List<Map<String, Object>> typeDatasets = new ArrayList<>();
                String[] colors = {"#667eea", "#f5576c", "#4facfe", "#43e97b", "#fa709a", "#fee140"};
                int colorIdx = 0;
                for (Map.Entry<String, List<Integer>> entry : byTypePerDump.entrySet()) {
                    if (entry.getValue().stream().anyMatch(v -> v > 0)) {
                        Map<String, Object> dataset = new LinkedHashMap<>();
                        dataset.put("label", entry.getKey());
                        dataset.put("data", entry.getValue());
                        dataset.put("color", colors[colorIdx % colors.length]);
                        typeDatasets.add(dataset);
                        colorIdx++;
                    }
                }
                context.put("typeDatasets", typeDatasets);

                // Trend analysis
                if (blockedCounts.size() >= 2) {
                    int first = blockedCounts.get(0);
                    int last = blockedCounts.get(blockedCounts.size() - 1);
                    int change = last - first;
                    context.put("trendFirst", first);
                    context.put("trendLast", last);
                    context.put("trendChange", change);
                    context.put("trendDirection", change > 0 ? "up" : (change < 0 ? "down" : "stable"));
                    context.put("trendDisplay", change > 0 ? "+" + change + " ↑" : (change < 0 ? change + " ↓" : "→"));
                }
            }

            // Blocked threads list
            List<Map<String, Object>> blockedThreads = new ArrayList<>();
            int maxToShow = Math.min(options.getMaxThreads(), ioResult.getBlockedThreads().size());
            for (int i = 0; i < maxToShow; i++) {
                IOBlockAnalyzer.IOBlockedThread bt = ioResult.getBlockedThreads().get(i);
                Map<String, Object> threadInfo = new LinkedHashMap<>();
                threadInfo.put("name", bt.thread().name());
                threadInfo.put("ioType", bt.ioType().getDisplayName());
                threadInfo.put("blockingFrame", bt.blockingFrame());
                threadInfo.put("state", bt.thread().state());
                threadInfo.put("dumpIndex", bt.dumpIndex() + 1);
                blockedThreads.add(threadInfo);
            }
            context.put("blockedThreads", blockedThreads);
            context.put("hasMoreBlocked", ioResult.getBlockedThreads().size() > maxToShow);
            context.put("moreBlockedCount", ioResult.getBlockedThreads().size() - maxToShow);

            // JFR analysis if available
            if (ioResult.hasJfrData()) {
                IOBlockAnalyzer.JfrIOAnalysis jfr = ioResult.getJfrAnalysis();
                Map<String, Object> jfrData = new LinkedHashMap<>();
                jfrData.put("totalEvents", jfr.summary().totalEvents());
                jfrData.put("totalDurationMs", jfr.summary().totalDuration().toMillis());
                jfrData.put("totalBytes", formatBytes(jfr.summary().totalBytes()));
                jfrData.put("slowOpCount", jfr.slowOperations().size());

                // Hotspots
                List<Map<String, Object>> hotspots = new ArrayList<>();
                for (var hotspot : jfr.hotspots().stream().limit(5).toList()) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("target", truncate(hotspot.target(), 50));
                    h.put("durationMs", hotspot.totalDuration().toMillis());
                    h.put("size", hotspot.getFormattedSize());
                    hotspots.add(h);
                }
                jfrData.put("hotspots", hotspots);
                context.put("jfrAnalysis", jfrData);
                context.put("hasJfrData", true);
            } else {
                context.put("hasJfrData", false);
            }
        }

        return context;
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