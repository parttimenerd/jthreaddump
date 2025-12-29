package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.jfr.JfrAnalyzer;
import me.bechberger.jstall.jfr.JfrParser;
import me.bechberger.jstall.model.ThreadInfo;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;

/**
 * Analyzer that uses JFR data to provide method profiling insights.
 * Shows hottest methods, threads, and correlates with thread dump data.
 */
public class JfrProfilingAnalyzer implements Analyzer<JfrProfilingAnalyzer.JfrProfilingResult> {

    private static final String NAME = "JfrProfilingAnalyzer";
    private static final int DEFAULT_TOP_LIMIT = 10;

    private final int topLimit;

    public JfrProfilingAnalyzer() {
        this(DEFAULT_TOP_LIMIT);
    }

    public JfrProfilingAnalyzer(int topLimit) {
        this.topLimit = topLimit;
    }

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Analyzes JFR profiling data to show hottest methods, threads, and I/O patterns";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        return context.hasJfr();
    }

    @Override
    public boolean requiresJfr() {
        return true;
    }

    @Override
    public int getPriority() {
        return 70;
    }

    @Override
    public @NotNull JfrProfilingResult analyze(@NotNull AnalysisContext context) {
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;

        JfrParser.JfrData jfrData = context.getJfrDataForDumpRange();
        if (jfrData == null || jfrData.isEmpty()) {
            return new JfrProfilingResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    null,
                    new JfrProfilingSummary(0, 0, 0, 0, 0),
                    null  // No temporal analysis
            );
        }

        JfrAnalyzer analyzer = new JfrAnalyzer();

        // Get hottest methods
        List<JfrAnalyzer.MethodProfile> hottestMethods = analyzer.getHottestMethods(jfrData, topLimit);

        // Get hottest threads
        List<JfrAnalyzer.ThreadProfile> hottestThreads = analyzer.getHottestThreads(jfrData, topLimit);

        // Get I/O summary
        JfrAnalyzer.IOSummary ioSummary = analyzer.getIOSummary(jfrData);

        // Get allocation hotspots
        List<JfrAnalyzer.AllocationProfile> allocationHotspots =
                analyzer.getAllocationHotspots(jfrData, topLimit);

        // Get lock contention
        JfrAnalyzer.LockContentionSummary lockContention = analyzer.getLockContentionSummary(jfrData);

        // Get I/O hotspots
        List<JfrAnalyzer.IOTargetProfile> ioHotspots = analyzer.getIOHotspots(jfrData, topLimit);

        // Multi-dump temporal analysis
        TemporalProfilingAnalysis temporal = null;
        if (context.isMultiDump()) {
            temporal = analyzeTemporalProfiling(context, analyzer);

            // Add findings for temporal trends
            if (temporal.hasHotMethodEvolution()) {
                for (MethodEvolution evo : temporal.methodEvolutions()) {
                    if (evo.isEmerging() && evo.lastPercentage() > 10) {
                        findings.add(AnalysisResult.Finding.builder(
                                AnalysisResult.Severity.WARNING, "emerging-hotspot",
                                String.format("Method %s is emerging hotspot: %.1f%% → %.1f%% CPU",
                                        formatMethod(evo.method()), evo.firstPercentage(), evo.lastPercentage()))
                                .detail("method", evo.method())
                                .detail("changePercent", evo.changePercent())
                                .build());

                        if (worstSeverity.getLevel() < AnalysisResult.Severity.WARNING.getLevel()) {
                            worstSeverity = AnalysisResult.Severity.WARNING;
                        }
                    }
                }
            }

            if (temporal.hasAllocationTrend() && temporal.allocationTrend().isIncreasing()) {
                double rate = temporal.allocationTrend().changeRate();
                if (Math.abs(rate) > 20) {
                    findings.add(AnalysisResult.Finding.builder(
                            AnalysisResult.Severity.INFO, "allocation-trend",
                            String.format("Allocation rate %s by %.1f%%",
                                    rate > 0 ? "increased" : "decreased", Math.abs(rate)))
                            .detail("changeRate", rate)
                            .build());
                }
            }
        }

        // Correlate with thread dump data - find threads that are both hot in JFR and appear stuck
        List<ThreadCorrelation> correlations = new ArrayList<>();
        for (JfrAnalyzer.ThreadProfile hotThread : hottestThreads) {
            // Look for this thread in thread dumps
            for (ThreadInfo threadInfo : context.getFilteredThreads(context.getLastDump())) {
                if (hotThread.threadName().equals(threadInfo.name())) {
                    // Get methods for this specific thread
                    List<JfrAnalyzer.MethodProfile> threadMethods =
                            analyzer.getHottestMethodsForThread(jfrData, hotThread.threadName(), 5);

                    correlations.add(new ThreadCorrelation(
                            threadInfo,
                            hotThread.sampleCount(),
                            hotThread.percentage(),
                            threadMethods
                    ));
                    break;
                }
            }
        }

        // Generate findings

        // Report top CPU consumer
        if (!hottestMethods.isEmpty()) {
            JfrAnalyzer.MethodProfile top = hottestMethods.getFirst();
            if (top.percentage() >= 20) {
                AnalysisResult.Severity severity = top.percentage() >= 50
                        ? AnalysisResult.Severity.WARNING
                        : AnalysisResult.Severity.INFO;

                if (severity.isWorseThan(worstSeverity)) {
                    worstSeverity = severity;
                }

                findings.add(AnalysisResult.Finding.builder(severity, "cpu-hotspot",
                        String.format("Method %s consumes %.1f%% of CPU samples",
                                formatMethod(top.method()), top.percentage()))
                        .detail("method", top.method())
                        .detail("sampleCount", top.sampleCount())
                        .detail("percentage", top.percentage())
                        .build());
            }
        }

        // Report high allocation activity
        if (!allocationHotspots.isEmpty()) {
            JfrAnalyzer.AllocationProfile topAlloc = allocationHotspots.getFirst();
            if (topAlloc.totalBytes() > 100 * 1024 * 1024) { // > 100MB
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.INFO, "high-allocation",
                        String.format("High allocation at %s: %s allocated (%d objects)",
                                formatMethod(topAlloc.allocationSite()), topAlloc.getFormattedSize(), topAlloc.count()))
                        .detail("site", topAlloc.allocationSite())
                        .detail("totalBytes", topAlloc.totalBytes())
                        .detail("count", topAlloc.count())
                        .build());
            }
        }

        // Report high I/O activity
        if (ioSummary.totalEvents() > 100) {
            Duration totalIOTime = ioSummary.totalDuration();
            if (totalIOTime.toMillis() > 1000) {
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.INFO, "high-io-activity",
                        String.format("High I/O activity: %d events, %s transferred, %dms total I/O time",
                                ioSummary.totalEvents(), ioSummary.getFormattedSize(), totalIOTime.toMillis()))
                        .detail("eventCount", ioSummary.totalEvents())
                        .detail("totalBytes", ioSummary.totalBytes())
                        .detail("totalDurationMs", totalIOTime.toMillis())
                        .build());
            }
        }

        // Report lock contention
        if (lockContention.totalBlockedTime().toMillis() > 1000) {
            AnalysisResult.Severity severity = lockContention.totalBlockedTime().toMillis() > 5000
                    ? AnalysisResult.Severity.WARNING
                    : AnalysisResult.Severity.INFO;

            if (severity.isWorseThan(worstSeverity)) {
                worstSeverity = severity;
            }

            findings.add(AnalysisResult.Finding.builder(severity, "jfr-lock-contention",
                    String.format("Lock contention detected: %d events, %dms total blocked time",
                            lockContention.totalEvents(), lockContention.totalBlockedTime().toMillis()))
                    .detail("eventCount", lockContention.totalEvents())
                    .detail("uniqueLocks", lockContention.uniqueLocks())
                    .detail("totalBlockedMs", lockContention.totalBlockedTime().toMillis())
                    .build());
        }

        // Report threads that are both hot and appear stuck
        for (ThreadCorrelation correlation : correlations) {
            if (correlation.cpuPercentage() >= 10 &&
                correlation.thread().state() == Thread.State.RUNNABLE) {
                // High CPU and currently running - normal
            } else if (correlation.cpuPercentage() >= 5 &&
                       (correlation.thread().state() == Thread.State.BLOCKED ||
                        correlation.thread().state() == Thread.State.WAITING)) {
                // Was hot but now blocked - potential issue
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.INFO, "hot-thread-now-blocked",
                        String.format("Thread '%s' consumed %.1f%% CPU but is now %s",
                                correlation.thread().name(), correlation.cpuPercentage(),
                                correlation.thread().state()))
                        .affectedThread(correlation.thread())
                        .detail("cpuPercentage", correlation.cpuPercentage())
                        .detail("currentState", correlation.thread().state().name())
                        .build());
            }
        }

        JfrProfilingSummary summary = new JfrProfilingSummary(
                jfrData.executionSamples().size(),
                jfrData.lockEvents().size(),
                jfrData.allocationEvents().size(),
                jfrData.ioEvents().size(),
                lockContention.totalBlockedTime().toMillis()
        );

        return new JfrProfilingResult(
                worstSeverity,
                findings,
                hottestMethods,
                hottestThreads,
                correlations,
                allocationHotspots,
                ioHotspots,
                ioSummary,
                lockContention,
                summary,
                temporal
        );
    }

    /**
     * Analyze temporal profiling trends across multiple dumps
     */
    private TemporalProfilingAnalysis analyzeTemporalProfiling(AnalysisContext context, JfrAnalyzer analyzer) {
        List<MethodEvolution> methodEvolutions = new ArrayList<>();
        Map<String, List<Double>> methodCpuPerDump = new LinkedHashMap<>();
        List<Long> allocationPerDump = new ArrayList<>();
        List<Long> ioEventsPerDump = new ArrayList<>();

        // Analyze each dump's JFR data
        for (int i = 0; i < context.getDumpCount(); i++) {
            JfrParser.JfrData dumpJfr = context.getJfrDataForDump(i);
            if (dumpJfr == null || dumpJfr.isEmpty()) {
                continue;
            }

            // Track method CPU percentages
            List<JfrAnalyzer.MethodProfile> methods = analyzer.getHottestMethods(dumpJfr, topLimit);
            for (JfrAnalyzer.MethodProfile method : methods) {
                methodCpuPerDump.computeIfAbsent(method.method(), k -> new ArrayList<>())
                        .add(method.percentage());
            }

            // Track allocation bytes
            long totalAllocation = dumpJfr.allocationEvents().stream()
                    .mapToLong(e -> e.allocationSize())
                    .sum();
            allocationPerDump.add(totalAllocation);

            // Track I/O events
            ioEventsPerDump.add((long) dumpJfr.ioEvents().size());
        }

        // Build method evolutions
        for (Map.Entry<String, List<Double>> entry : methodCpuPerDump.entrySet()) {
            List<Double> percentages = entry.getValue();
            if (percentages.size() >= 2) {
                double firstPct = percentages.getFirst();
                double lastPct = percentages.getLast();
                double change = lastPct - firstPct;
                double changePercent = firstPct > 0 ? (change / firstPct) * 100 : 0;

                boolean isEmerging = firstPct < 5 && lastPct > 10;
                boolean isIncreasing = change > 5;
                boolean isDecreasing = change < -5;

                methodEvolutions.add(new MethodEvolution(
                        entry.getKey(),
                        percentages,
                        firstPct,
                        lastPct,
                        change,
                        changePercent,
                        isEmerging,
                        isIncreasing,
                        isDecreasing
                ));
            }
        }

        // Analyze allocation trend using TrendAnalyzer
        me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> allocationTrend = null;
        if (!allocationPerDump.isEmpty()) {
            allocationTrend = me.bechberger.jstall.analysis.util.TrendAnalyzer.analyzeTrendNumeric(
                    allocationPerDump.stream().map(Long::doubleValue).toList()
            );
        }

        // Analyze I/O trend
        me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> ioTrend = null;
        if (!ioEventsPerDump.isEmpty()) {
            ioTrend = me.bechberger.jstall.analysis.util.TrendAnalyzer.analyzeTrendNumeric(
                    ioEventsPerDump.stream().map(Long::doubleValue).toList()
            );
        }

        // Sort method evolutions by absolute change
        methodEvolutions.sort((a, b) -> Double.compare(
                Math.abs(b.change()), Math.abs(a.change())
        ));

        return new TemporalProfilingAnalysis(
                methodEvolutions,
                allocationPerDump,
                ioEventsPerDump,
                allocationTrend,
                ioTrend
        );
    }

    private String formatMethod(String method) {
        if (method == null) return "unknown";
        // Shorten class name
        int lastDot = method.lastIndexOf('.');
        if (lastDot > 0) {
            int prevDot = method.lastIndexOf('.', lastDot - 1);
            if (prevDot > 0) {
                return "..." + method.substring(prevDot + 1);
            }
        }
        return method;
    }

    /**
     * Tracks evolution of a method's CPU usage across dumps
     */
    public record MethodEvolution(
            String method,
            List<Double> cpuPercentagesPerDump,
            double firstPercentage,
            double lastPercentage,
            double change,              // Absolute change
            double changePercent,       // Percentage change
            boolean isEmerging,         // Went from < 5% to > 10%
            boolean isIncreasing,       // Increased by > 5%
            boolean isDecreasing        // Decreased by > 5%
    ) {
        public MethodEvolution {
            cpuPercentagesPerDump = cpuPercentagesPerDump != null ?
                    List.copyOf(cpuPercentagesPerDump) : List.of();
        }

        public String getTrend() {
            if (isEmerging) return "EMERGING";
            if (isIncreasing) return "INCREASING";
            if (isDecreasing) return "DECREASING";
            return "STABLE";
        }
    }

    /**
     * Temporal analysis of JFR profiling data across multiple dumps
     */
    public record TemporalProfilingAnalysis(
            List<MethodEvolution> methodEvolutions,
            List<Long> allocationBytesPerDump,
            List<Long> ioEventsPerDump,
            me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> allocationTrend,
            me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> ioTrend
    ) {
        public TemporalProfilingAnalysis {
            methodEvolutions = methodEvolutions != null ? List.copyOf(methodEvolutions) : List.of();
            allocationBytesPerDump = allocationBytesPerDump != null ?
                    List.copyOf(allocationBytesPerDump) : List.of();
            ioEventsPerDump = ioEventsPerDump != null ? List.copyOf(ioEventsPerDump) : List.of();
        }

        public boolean hasHotMethodEvolution() {
            return !methodEvolutions.isEmpty();
        }

        public boolean hasAllocationTrend() {
            return allocationTrend != null;
        }

        public boolean hasIOTrend() {
            return ioTrend != null;
        }
    }

    /**
     * Correlation between JFR profiling data and thread dump thread
     */
    public record ThreadCorrelation(
            ThreadInfo thread,
            long sampleCount,
            double cpuPercentage,
            List<JfrAnalyzer.MethodProfile> topMethods
    ) {
        public ThreadCorrelation {
            topMethods = topMethods != null ? List.copyOf(topMethods) : List.of();
        }
    }

    /**
     * Summary of JFR profiling analysis
     */
    public record JfrProfilingSummary(
            int totalSamples,
            int lockEvents,
            int allocationEvents,
            int ioEvents,
            long totalBlockedTimeMs
    ) {}

    /**
     * Result of JFR profiling analysis
     */
    public static class JfrProfilingResult extends AnalysisResult {
        private final List<JfrAnalyzer.MethodProfile> hottestMethods;
        private final List<JfrAnalyzer.ThreadProfile> hottestThreads;
        private final List<ThreadCorrelation> correlations;
        private final List<JfrAnalyzer.AllocationProfile> allocationHotspots;
        private final List<JfrAnalyzer.IOTargetProfile> ioHotspots;
        private final JfrAnalyzer.IOSummary ioSummary;
        private final JfrAnalyzer.LockContentionSummary lockContention;
        private final JfrProfilingSummary summary;
        private final TemporalProfilingAnalysis temporal;

        public JfrProfilingResult(Severity severity, List<Finding> findings,
                                  List<JfrAnalyzer.MethodProfile> hottestMethods,
                                  List<JfrAnalyzer.ThreadProfile> hottestThreads,
                                  List<ThreadCorrelation> correlations,
                                  JfrAnalyzer.IOSummary ioSummary,
                                  JfrAnalyzer.LockContentionSummary lockContention,
                                  JfrProfilingSummary summary) {
            this(severity, findings, hottestMethods, hottestThreads, correlations,
                 List.of(), List.of(), ioSummary, lockContention, summary, null);
        }

        public JfrProfilingResult(Severity severity, List<Finding> findings,
                                  List<JfrAnalyzer.MethodProfile> hottestMethods,
                                  List<JfrAnalyzer.ThreadProfile> hottestThreads,
                                  List<ThreadCorrelation> correlations,
                                  List<JfrAnalyzer.AllocationProfile> allocationHotspots,
                                  List<JfrAnalyzer.IOTargetProfile> ioHotspots,
                                  JfrAnalyzer.IOSummary ioSummary,
                                  JfrAnalyzer.LockContentionSummary lockContention,
                                  JfrProfilingSummary summary,
                                  TemporalProfilingAnalysis temporal) {
            super(NAME, severity, findings);
            this.hottestMethods = hottestMethods != null ? List.copyOf(hottestMethods) : List.of();
            this.hottestThreads = hottestThreads != null ? List.copyOf(hottestThreads) : List.of();
            this.correlations = correlations != null ? List.copyOf(correlations) : List.of();
            this.allocationHotspots = allocationHotspots != null ? List.copyOf(allocationHotspots) : List.of();
            this.ioHotspots = ioHotspots != null ? List.copyOf(ioHotspots) : List.of();
            this.ioSummary = ioSummary;
            this.lockContention = lockContention;
            this.summary = summary;
            this.temporal = temporal;
        }

        public List<JfrAnalyzer.MethodProfile> getHottestMethods() {
            return hottestMethods;
        }

        public List<JfrAnalyzer.ThreadProfile> getHottestThreads() {
            return hottestThreads;
        }

        public List<ThreadCorrelation> getCorrelations() {
            return correlations;
        }

        public List<JfrAnalyzer.AllocationProfile> getAllocationHotspots() {
            return allocationHotspots;
        }

        public List<JfrAnalyzer.IOTargetProfile> getIoHotspots() {
            return ioHotspots;
        }

        public JfrAnalyzer.IOSummary getIoSummary() {
            return ioSummary;
        }

        public JfrAnalyzer.LockContentionSummary getLockContention() {
            return lockContention;
        }

        public JfrProfilingSummary getProfilingSummary() {
            return summary;
        }

        public TemporalProfilingAnalysis getTemporal() {
            return temporal;
        }

        public boolean hasTemporal() {
            return temporal != null && temporal.hasHotMethodEvolution();
        }

        @Override
        public String getSummary() {
            if (summary.totalSamples() == 0) {
                return "No JFR profiling data available";
            }
            return String.format("JFR: %d samples, %d lock events, %d I/O events",
                    summary.totalSamples(), summary.lockEvents(), summary.ioEvents());
        }
    }
}