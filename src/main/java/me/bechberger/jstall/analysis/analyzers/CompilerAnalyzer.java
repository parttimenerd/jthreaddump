package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.jfr.JfrAnalyzer;
import me.bechberger.jstall.jfr.JfrParser;
import me.bechberger.jstall.model.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzer for JIT compiler activity.
 * Detects compilation hotspots, deoptimizations, and compiler thread activity.
 * Requires JFR data for detailed analysis.
 */
public class CompilerAnalyzer implements Analyzer<CompilerAnalyzer.CompilerResult> {

    private static final String NAME = "CompilerAnalyzer";
    private static final int EXCESSIVE_COMPILATION_THRESHOLD = 100;
    private static final int HIGH_DEOPT_THRESHOLD = 50;

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Analyzes JIT compiler activity including compilations, deoptimizations, and compiler threads";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        // Can analyze even without JFR (basic compiler thread detection)
        return true;
    }

    @Override
    public boolean requiresJfr() {
        return false; // Optional JFR for detailed analysis
    }

    @Override
    public int getPriority() {
        return 35;
    }

    @Override
    public @NotNull CompilerResult analyze(@NotNull AnalysisContext context) {
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;

        // Analyze compiler threads from thread dumps
        CompilerThreadAnalysis threadAnalysis = analyzeCompilerThreads(context);

        // Check for high compiler activity from thread dumps
        if (threadAnalysis.activeCompilerThreads() > 2) {
            AnalysisResult.Severity severity = threadAnalysis.activeCompilerThreads() > 4
                    ? AnalysisResult.Severity.WARNING
                    : AnalysisResult.Severity.INFO;

            findings.add(AnalysisResult.Finding.builder(severity, "high-compiler-activity",
                    String.format("High compiler activity: %d compiler threads active",
                            threadAnalysis.activeCompilerThreads()))
                    .detail("activeThreads", threadAnalysis.activeCompilerThreads())
                    .detail("c1Active", threadAnalysis.activeC1Threads())
                    .detail("c2Active", threadAnalysis.activeC2Threads())
                    .build());

            if (severity.isWorseThan(worstSeverity)) worstSeverity = severity;
        }

        // JFR-based compilation analysis (if available)
        CompilationAnalysis compilationAnalysis = null;
        if (context.hasJfr()) {
            JfrParser.JfrData jfrData = context.getJfrDataForDumpRange();
            if (jfrData != null) {
                compilationAnalysis = analyzeCompilations(jfrData);

                // Check for excessive compilation
                if (compilationAnalysis.totalCompilations > EXCESSIVE_COMPILATION_THRESHOLD) {
                    AnalysisResult.Severity severity = compilationAnalysis.totalCompilations > 500
                            ? AnalysisResult.Severity.WARNING
                            : AnalysisResult.Severity.INFO;

                    findings.add(AnalysisResult.Finding.builder(severity, "excessive-compilation",
                            String.format("High compilation activity: %d compilations detected",
                                    compilationAnalysis.totalCompilations))
                            .detail("compilations", compilationAnalysis.totalCompilations)
                            .build());

                    if (severity.isWorseThan(worstSeverity)) worstSeverity = severity;
                }

                // Check for excessive deoptimization
                if (compilationAnalysis.totalDeoptimizations > HIGH_DEOPT_THRESHOLD) {
                    AnalysisResult.Severity severity = AnalysisResult.Severity.WARNING;

                    findings.add(AnalysisResult.Finding.builder(severity, "excessive-deoptimization",
                            String.format("High deoptimization rate: %d deoptimizations detected",
                                    compilationAnalysis.totalDeoptimizations))
                            .detail("deoptimizations", compilationAnalysis.totalDeoptimizations)
                            .build());

                    if (severity.isWorseThan(worstSeverity)) worstSeverity = severity;
                }
            }
        }

        // Multi-dump temporal analysis
        CompilerTemporal temporal = null;
        if (context.isMultiDump()) {
            temporal = analyzeCompilerTemporal(context);

            // Add findings for compilation trends
            if (temporal.hasCompilationTrend() && temporal.compilationTrend().isIncreasing()) {
                double rate = temporal.compilationTrend().changeRate();
                if (Math.abs(rate) > 50) {
                    findings.add(AnalysisResult.Finding.builder(
                            AnalysisResult.Severity.INFO, "increasing-compilation",
                            String.format("Compilation rate increasing by %.1f%%", Math.abs(rate)))
                            .detail("changeRate", rate)
                            .build());
                }
            }

            if (temporal.hasDeoptTrend() && temporal.deoptTrend().isIncreasing()) {
                double rate = temporal.deoptTrend().changeRate();
                if (Math.abs(rate) > 30) {
                    findings.add(AnalysisResult.Finding.builder(
                            AnalysisResult.Severity.WARNING, "increasing-deoptimization",
                            String.format("Deoptimization rate increasing by %.1f%%", Math.abs(rate)))
                            .detail("changeRate", rate)
                            .build());

                    if (worstSeverity.getLevel() < AnalysisResult.Severity.WARNING.getLevel()) {
                        worstSeverity = AnalysisResult.Severity.WARNING;
                    }
                }
            }
        }

        CompilerSummary summary = new CompilerSummary(
                threadAnalysis.compilerThreadCount,
                threadAnalysis.activeCompilerThreads,
                compilationAnalysis != null ? compilationAnalysis.totalCompilations : 0,
                compilationAnalysis != null ? compilationAnalysis.totalDeoptimizations : 0,
                compilationAnalysis != null &&
                        (compilationAnalysis.totalCompilations > EXCESSIVE_COMPILATION_THRESHOLD ||
                         compilationAnalysis.totalDeoptimizations > HIGH_DEOPT_THRESHOLD)
        );

        return new CompilerResult(worstSeverity, findings, threadAnalysis,
                compilationAnalysis, summary, temporal);
    }

    private CompilerThreadAnalysis analyzeCompilerThreads(AnalysisContext context) {
        Map<String, ThreadInfo> uniqueCompilerThreads = new LinkedHashMap<>();
        int totalActive = 0;
        int totalC1Active = 0;
        int totalC2Active = 0;

        // Collect all compiler threads across dumps
        for (ThreadDump dump : context.getDumps()) {
            for (ThreadInfo thread : dump.threads()) {
                String name = thread.name();
                if (name != null && isCompilerThread(name)) {
                    uniqueCompilerThreads.putIfAbsent(name, thread);

                    if (thread.state() == Thread.State.RUNNABLE) {
                        totalActive++;
                        if (isC1CompilerThread(name)) {
                            totalC1Active++;
                        } else if (isC2CompilerThread(name)) {
                            totalC2Active++;
                        }
                    }
                }
            }
        }

        List<ThreadInfo> compilerThreads = new ArrayList<>(uniqueCompilerThreads.values());

        // Calculate averages for multi-dump
        int dumpCount = context.getDumpCount();
        int avgActive = totalActive / dumpCount;

        return new CompilerThreadAnalysis(
                compilerThreads.size(),
                avgActive,
                compilerThreads,
                totalC1Active / dumpCount,
                totalC2Active / dumpCount
        );
    }

    private boolean isCompilerThread(String threadName) {
        return threadName.contains("C1 CompilerThread") ||
               threadName.contains("C2 CompilerThread") ||
               threadName.contains("JVMCI CompilerThread") ||
               threadName.contains("GraalCompilerThread") ||
               threadName.startsWith("CompilerThread");
    }

    private boolean isC1CompilerThread(String threadName) {
        return threadName.contains("C1 CompilerThread");
    }

    private boolean isC2CompilerThread(String threadName) {
        return threadName.contains("C2 CompilerThread");
    }

    private CompilationAnalysis analyzeCompilations(JfrParser.JfrData jfrData) {
        // Note: JFR compilation events would require specific event parsing
        // For now, we'll create placeholder logic
        // In a real implementation, you'd parse jdk.Compilation events

        int totalCompilations = 0;
        int totalDeoptimizations = 0;
        List<CompilationEvent> topCompilations = new ArrayList<>();
        List<DeoptimizationEvent> topDeopts = new ArrayList<>();

        // Placeholder: In real implementation, parse JFR events
        // jfrData would need methods like getCompilationEvents(), getDeoptEvents()

        return new CompilationAnalysis(totalCompilations, totalDeoptimizations,
                topCompilations, topDeopts);
    }

    private CompilerTemporal analyzeCompilerTemporal(AnalysisContext context) {
        List<Integer> compilationsPerDump = new ArrayList<>();
        List<Integer> deoptsPerDump = new ArrayList<>();
        List<Integer> activeCompilerThreadsPerDump = new ArrayList<>();

        for (int i = 0; i < context.getDumpCount(); i++) {
            ThreadDump dump = context.getDumps().get(i);

            // Count active compiler threads
            int activeCompilers = 0;
            for (ThreadInfo thread : dump.threads()) {
                String name = thread.name();
                if (name != null && isCompilerThread(name) &&
                    thread.state() == Thread.State.RUNNABLE) {
                    activeCompilers++;
                }
            }
            activeCompilerThreadsPerDump.add(activeCompilers);

            // JFR data per dump (if available)
            JfrParser.JfrData dumpJfr = context.getJfrDataForDump(i);
            if (dumpJfr != null) {
                // Placeholder for compilation counts
                compilationsPerDump.add(0);
                deoptsPerDump.add(0);
            } else {
                compilationsPerDump.add(0);
                deoptsPerDump.add(0);
            }
        }

        // Analyze trends
        me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> compilationTrend = null;
        if (!compilationsPerDump.isEmpty() && compilationsPerDump.stream().anyMatch(c -> c > 0)) {
            compilationTrend = me.bechberger.jstall.analysis.util.TrendAnalyzer.analyzeTrendNumeric(
                    compilationsPerDump.stream().map(Integer::doubleValue).toList()
            );
        }

        me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> deoptTrend = null;
        if (!deoptsPerDump.isEmpty() && deoptsPerDump.stream().anyMatch(d -> d > 0)) {
            deoptTrend = me.bechberger.jstall.analysis.util.TrendAnalyzer.analyzeTrendNumeric(
                    deoptsPerDump.stream().map(Integer::doubleValue).toList()
            );
        }

        me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> activeThreadsTrend = null;
        if (!activeCompilerThreadsPerDump.isEmpty()) {
            activeThreadsTrend = me.bechberger.jstall.analysis.util.TrendAnalyzer.analyzeTrendNumeric(
                    activeCompilerThreadsPerDump.stream().map(Integer::doubleValue).toList()
            );
        }

        return new CompilerTemporal(
                compilationsPerDump,
                deoptsPerDump,
                activeCompilerThreadsPerDump,
                compilationTrend,
                deoptTrend,
                activeThreadsTrend
        );
    }

    // Records

    public record CompilerThreadAnalysis(
            int compilerThreadCount,
            int activeCompilerThreads,
            List<ThreadInfo> compilerThreads,
            int activeC1Threads,
            int activeC2Threads
    ) {
        public CompilerThreadAnalysis {
            compilerThreads = compilerThreads != null ? List.copyOf(compilerThreads) : List.of();
        }

        public boolean hasC1Activity() {
            return activeC1Threads > 0;
        }

        public boolean hasC2Activity() {
            return activeC2Threads > 0;
        }
    }

    public record CompilationEvent(
            String method,
            int level,          // Compilation level (C1, C2, etc.)
            long durationMs,
            boolean success
    ) {}

    public record DeoptimizationEvent(
            String method,
            String reason,
            String action
    ) {}

    public record CompilationAnalysis(
            int totalCompilations,
            int totalDeoptimizations,
            List<CompilationEvent> topCompilations,
            List<DeoptimizationEvent> topDeoptimizations
    ) {
        public CompilationAnalysis {
            topCompilations = topCompilations != null ? List.copyOf(topCompilations) : List.of();
            topDeoptimizations = topDeoptimizations != null ? List.copyOf(topDeoptimizations) : List.of();
        }
    }

    public record CompilerTemporal(
            List<Integer> compilationsPerDump,
            List<Integer> deoptimizationsPerDump,
            List<Integer> activeCompilerThreadsPerDump,
            me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> compilationTrend,
            me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> deoptTrend,
            me.bechberger.jstall.analysis.util.TrendAnalyzer.Trend<Double> activeThreadsTrend
    ) {
        public CompilerTemporal {
            compilationsPerDump = compilationsPerDump != null ? List.copyOf(compilationsPerDump) : List.of();
            deoptimizationsPerDump = deoptimizationsPerDump != null ? List.copyOf(deoptimizationsPerDump) : List.of();
            activeCompilerThreadsPerDump = activeCompilerThreadsPerDump != null ?
                    List.copyOf(activeCompilerThreadsPerDump) : List.of();
        }

        public boolean hasCompilationTrend() {
            return compilationTrend != null;
        }

        public boolean hasDeoptTrend() {
            return deoptTrend != null;
        }

        public boolean hasActiveThreadsTrend() {
            return activeThreadsTrend != null;
        }
    }

    public record CompilerSummary(
            int compilerThreadCount,
            int activeCompilerThreads,
            int totalCompilations,
            int totalDeoptimizations,
            boolean hasIssues
    ) {}

    public static class CompilerResult extends AnalysisResult {
        private final CompilerThreadAnalysis threadAnalysis;
        private final CompilationAnalysis compilationAnalysis;
        private final CompilerSummary summary;
        private final CompilerTemporal temporal;

        public CompilerResult(Severity severity, List<Finding> findings,
                             CompilerThreadAnalysis threadAnalysis,
                             CompilationAnalysis compilationAnalysis,
                             CompilerSummary summary) {
            this(severity, findings, threadAnalysis, compilationAnalysis, summary, null);
        }

        public CompilerResult(Severity severity, List<Finding> findings,
                             CompilerThreadAnalysis threadAnalysis,
                             CompilationAnalysis compilationAnalysis,
                             CompilerSummary summary,
                             CompilerTemporal temporal) {
            super(NAME, severity, findings);
            this.threadAnalysis = threadAnalysis;
            this.compilationAnalysis = compilationAnalysis;
            this.summary = summary;
            this.temporal = temporal;
        }

        public CompilerThreadAnalysis getThreadAnalysis() {
            return threadAnalysis;
        }

        public CompilationAnalysis getCompilationAnalysis() {
            return compilationAnalysis;
        }

        public CompilerSummary getCompilerSummary() {
            return summary;
        }

        public CompilerTemporal getTemporal() {
            return temporal;
        }

        public boolean hasTemporal() {
            return temporal != null && (temporal.hasCompilationTrend() ||
                                       temporal.hasDeoptTrend() ||
                                       temporal.hasActiveThreadsTrend());
        }

        @Override
        public String getSummary() {
            if (summary.compilerThreadCount() == 0) {
                return "No compiler threads detected";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%d compiler thread(s)", summary.compilerThreadCount()));
            if (summary.activeCompilerThreads() > 0) {
                sb.append(String.format(", %d active", summary.activeCompilerThreads()));
            }
            if (summary.totalCompilations() > 0) {
                sb.append(String.format(", %d compilation(s)", summary.totalCompilations()));
            }
            if (summary.totalDeoptimizations() > 0) {
                sb.append(String.format(", %d deopt(s)", summary.totalDeoptimizations()));
            }
            return sb.toString();
        }
    }
}