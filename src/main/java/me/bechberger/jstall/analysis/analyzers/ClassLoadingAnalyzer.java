package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.jfr.JfrAnalyzer;
import me.bechberger.jstall.jfr.JfrParser;
import me.bechberger.jstall.model.*;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;

/**
 * Analyzer that detects excessive class loading activity.
 * Uses JFR data to identify class loading hotspots.
 */
public class ClassLoadingAnalyzer implements Analyzer<ClassLoadingAnalyzer.ClassLoadingResult> {

    private static final String NAME = "ClassLoadingAnalyzer";
    private static final int HIGH_CLASS_LOAD_THRESHOLD = 100;
    private static final long HIGH_DURATION_THRESHOLD_MS = 500;

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Detects excessive class loading activity (requires JFR data)";
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
        return 30;
    }

    @Override
    public @NotNull ClassLoadingResult analyze(@NotNull AnalysisContext context) {
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;

        JfrParser.JfrData jfrData = context.getJfrDataForDumpRange();
        if (jfrData == null || jfrData.classLoadEvents().isEmpty()) {
            return new ClassLoadingResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    null,
                    List.of(),
                    new ClassLoadingSummary(0, Duration.ZERO, 0, false, false),
                    null  // No temporal analysis
            );
        }

        // Multi-dump temporal analysis
        LoadingRateTemporal temporal = null;
        if (context.isMultiDump()) {
            temporal = analyzeLoadingRate(context);

            // Add findings for loading rate trends
            if (temporal.hasLoadingRateTrend() && temporal.loadingRateTrend().isIncreasing()) {
                double rate = temporal.loadingRateTrend().changeRate();
                if (Math.abs(rate) > 50) {
                    findings.add(AnalysisResult.Finding.builder(
                            AnalysisResult.Severity.WARNING, "increasing-class-loading",
                            String.format("Class loading rate increased by %.1f%%", Math.abs(rate)))
                            .detail("changeRate", rate)
                            .build());

                    if (worstSeverity.getLevel() < AnalysisResult.Severity.WARNING.getLevel()) {
                        worstSeverity = AnalysisResult.Severity.WARNING;
                    }
                }
            }
        }

        JfrAnalyzer analyzer = new JfrAnalyzer();
        JfrAnalyzer.ClassLoadingSummary jfrSummary = analyzer.getClassLoadingSummary(jfrData);

        // Analyze class loading patterns
        List<ClassLoadEvent> events = jfrData.classLoadEvents().stream()
                .map(e -> new ClassLoadEvent(
                        e.timestamp(),
                        e.loadedClass(),
                        e.threadName(),
                        e.classLoader(),
                        e.duration()
                ))
                .toList();

        // Group by class loader
        Map<String, List<ClassLoadEvent>> byLoader = new LinkedHashMap<>();
        for (ClassLoadEvent event : events) {
            String loader = event.classLoader() != null ? event.classLoader() : "bootstrap";
            byLoader.computeIfAbsent(loader, k -> new ArrayList<>()).add(event);
        }

        // Find slow class loads
        List<ClassLoadEvent> slowLoads = events.stream()
                .filter(e -> e.duration() != null && e.duration().toMillis() > 50)
                .sorted((a, b) -> b.duration().compareTo(a.duration()))
                .limit(10)
                .toList();

        // Detect issues
        boolean excessiveLoading = jfrSummary.totalClasses() > HIGH_CLASS_LOAD_THRESHOLD;
        boolean slowLoading = jfrSummary.totalDuration().toMillis() > HIGH_DURATION_THRESHOLD_MS;

        if (excessiveLoading) {
            AnalysisResult.Severity severity = jfrSummary.totalClasses() > 500
                    ? AnalysisResult.Severity.WARNING
                    : AnalysisResult.Severity.INFO;

            if (severity.isWorseThan(worstSeverity)) {
                worstSeverity = severity;
            }

            findings.add(AnalysisResult.Finding.builder(severity, "excessive-class-loading",
                    String.format("Excessive class loading: %d classes loaded",
                            jfrSummary.totalClasses()))
                    .detail("totalClasses", jfrSummary.totalClasses())
                    .detail("totalDurationMs", jfrSummary.totalDuration().toMillis())
                    .build());
        }

        if (slowLoading) {
            AnalysisResult.Severity severity = jfrSummary.totalDuration().toMillis() > 2000
                    ? AnalysisResult.Severity.WARNING
                    : AnalysisResult.Severity.INFO;

            if (severity.isWorseThan(worstSeverity)) {
                worstSeverity = severity;
            }

            findings.add(AnalysisResult.Finding.builder(severity, "slow-class-loading",
                    String.format("Slow class loading: %dms total for %d classes",
                            jfrSummary.totalDuration().toMillis(), jfrSummary.totalClasses()))
                    .detail("totalDurationMs", jfrSummary.totalDuration().toMillis())
                    .detail("totalClasses", jfrSummary.totalClasses())
                    .build());
        }

        // Report slowest individual class loads
        if (!slowLoads.isEmpty()) {
            ClassLoadEvent slowest = slowLoads.getFirst();
            if (slowest.duration().toMillis() > 100) {
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.INFO, "slow-class-load",
                        String.format("Slowest class load: %s took %dms",
                                slowest.loadedClass(), slowest.duration().toMillis()))
                        .detail("className", slowest.loadedClass())
                        .detail("durationMs", slowest.duration().toMillis())
                        .detail("classLoader", slowest.classLoader())
                        .build());
            }
        }

        // Report class loader distribution if skewed
        if (byLoader.size() > 1) {
            int maxFromLoader = byLoader.values().stream()
                    .mapToInt(List::size)
                    .max()
                    .orElse(0);

            if (maxFromLoader > jfrSummary.totalClasses() * 0.8) {
                String dominantLoader = byLoader.entrySet().stream()
                        .max(Comparator.comparingInt(e -> e.getValue().size()))
                        .map(Map.Entry::getKey)
                        .orElse("unknown");

                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.INFO, "dominant-classloader",
                        String.format("Class loader '%s' loaded %d/%d classes (%.0f%%)",
                                dominantLoader, maxFromLoader, jfrSummary.totalClasses(),
                                (maxFromLoader * 100.0) / jfrSummary.totalClasses()))
                        .detail("classLoader", dominantLoader)
                        .detail("count", maxFromLoader)
                        .build());
            }
        }

        ClassLoadingSummary summary = new ClassLoadingSummary(
                jfrSummary.totalClasses(),
                jfrSummary.totalDuration(),
                byLoader.size(),
                excessiveLoading,
                slowLoading
        );

        return new ClassLoadingResult(worstSeverity, findings, jfrSummary, slowLoads, summary, temporal);
    }

    /**
     * Analyze class loading rate across multiple dumps
     */
    private LoadingRateTemporal analyzeLoadingRate(AnalysisContext context) {
        List<Integer> classesPerDump = new ArrayList<>();
        List<Long> durationPerDump = new ArrayList<>();
        Map<String, List<Integer>> loaderCountsPerDump = new LinkedHashMap<>();

        // Analyze each dump's JFR data
        for (int i = 0; i < context.getDumpCount(); i++) {
            JfrParser.JfrData dumpJfr = context.getJfrDataForDump(i);
            if (dumpJfr == null || dumpJfr.classLoadEvents().isEmpty()) {
                classesPerDump.add(0);
                durationPerDump.add(0L);
                continue;
            }

            // Count classes loaded
            classesPerDump.add(dumpJfr.classLoadEvents().size());

            // Sum duration
            long totalMs = dumpJfr.classLoadEvents().stream()
                    .mapToLong(e -> e.duration() != null ? e.duration().toMillis() : 0)
                    .sum();
            durationPerDump.add(totalMs);

            // Track per-loader counts
            Map<String, Integer> loaderCounts = new HashMap<>();
            for (var event : dumpJfr.classLoadEvents()) {
                String loader = event.classLoader() != null ? event.classLoader() : "bootstrap";
                loaderCounts.merge(loader, 1, Integer::sum);
            }

            for (Map.Entry<String, Integer> entry : loaderCounts.entrySet()) {
                loaderCountsPerDump.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(entry.getValue());
            }
        }

        // Analyze trends using TrendAnalyzer
        me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> loadingRateTrend = null;
        if (!classesPerDump.isEmpty()) {
            loadingRateTrend = me.bechberger.jstall.analysis.util.TrendAnalyzer.analyzeTrendNumeric(
                    classesPerDump.stream().map(Integer::doubleValue).toList()
            );
        }

        me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> durationTrend = null;
        if (!durationPerDump.isEmpty()) {
            durationTrend = me.bechberger.jstall.analysis.util.TrendAnalyzer.analyzeTrendNumeric(
                    durationPerDump.stream().map(Long::doubleValue).toList()
            );
        }

        // Detect warmup pattern (high initial loading, then decreases)
        boolean isWarmup = false;
        if (classesPerDump.size() >= 3) {
            int first = classesPerDump.getFirst();
            int last = classesPerDump.getLast();
            isWarmup = first > 100 && last < first * 0.3; // > 70% reduction
        }

        return new LoadingRateTemporal(
                classesPerDump,
                durationPerDump,
                loaderCountsPerDump,
                loadingRateTrend,
                durationTrend,
                isWarmup
        );
    }

    public record ClassLoadEvent(
            java.time.Instant timestamp,
            String loadedClass,
            String threadName,
            String classLoader,
            Duration duration
    ) {}

    /**
     * Temporal analysis of class loading rates across multiple dumps
     */
    public record LoadingRateTemporal(
            List<Integer> classesLoadedPerDump,
            List<Long> durationMsPerDump,
            Map<String, List<Integer>> loaderCountsPerDump,
            me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> loadingRateTrend,
            me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> durationTrend,
            boolean isWarmupPattern
    ) {
        public LoadingRateTemporal {
            classesLoadedPerDump = classesLoadedPerDump != null ?
                    List.copyOf(classesLoadedPerDump) : List.of();
            durationMsPerDump = durationMsPerDump != null ?
                    List.copyOf(durationMsPerDump) : List.of();
            loaderCountsPerDump = loaderCountsPerDump != null ?
                    Map.copyOf(loaderCountsPerDump) : Map.of();
        }

        public boolean hasLoadingRateTrend() {
            return loadingRateTrend != null;
        }

        public boolean hasDurationTrend() {
            return durationTrend != null;
        }
    }

    public record ClassLoadingSummary(
            int totalClasses,
            Duration totalDuration,
            int classLoaderCount,
            boolean excessiveLoading,
            boolean slowLoading
    ) {}

    public static class ClassLoadingResult extends AnalysisResult {
        private final JfrAnalyzer.ClassLoadingSummary jfrSummary;
        private final List<ClassLoadEvent> slowLoads;
        private final ClassLoadingSummary summary;
        private final LoadingRateTemporal temporal;

        public ClassLoadingResult(Severity severity, List<Finding> findings,
                                  JfrAnalyzer.ClassLoadingSummary jfrSummary,
                                  List<ClassLoadEvent> slowLoads,
                                  ClassLoadingSummary summary) {
            this(severity, findings, jfrSummary, slowLoads, summary, null);
        }

        public ClassLoadingResult(Severity severity, List<Finding> findings,
                                  JfrAnalyzer.ClassLoadingSummary jfrSummary,
                                  List<ClassLoadEvent> slowLoads,
                                  ClassLoadingSummary summary,
                                  LoadingRateTemporal temporal) {
            super(NAME, severity, findings);
            this.jfrSummary = jfrSummary;
            this.slowLoads = slowLoads != null ? List.copyOf(slowLoads) : List.of();
            this.summary = summary;
            this.temporal = temporal;
        }

        public JfrAnalyzer.ClassLoadingSummary getJfrSummary() {
            return jfrSummary;
        }

        public List<ClassLoadEvent> getSlowLoads() {
            return slowLoads;
        }

        public List<ClassLoadEvent> getClassLoadEvents() {
            return slowLoads;  // For compatibility with existing view code
        }

        public ClassLoadingSummary getClassLoadingSummary() {
            return summary;
        }

        public LoadingRateTemporal getTemporal() {
            return temporal;
        }

        public boolean hasTemporal() {
            return temporal != null && temporal.hasLoadingRateTrend();
        }

        @Override
        public String getSummary() {
            if (summary.totalClasses() == 0) {
                return "No class loading events";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%d classes loaded in %dms",
                    summary.totalClasses(), summary.totalDuration().toMillis()));
            if (summary.excessiveLoading()) {
                sb.append(" [excessive]");
            }
            if (summary.slowLoading()) {
                sb.append(" [slow]");
            }
            return sb.toString();
        }
    }
}