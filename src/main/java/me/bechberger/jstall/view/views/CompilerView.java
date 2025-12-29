package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.CompilerAnalyzer;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for CompilerAnalyzer results.
 * Shows JIT compiler activity including compilations, deoptimizations, and compiler threads.
 */
public class CompilerView extends HandlebarsViewRenderer {

    public CompilerView() {
        super("compiler", "compiler", CompilerAnalyzer.CompilerResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof CompilerAnalyzer.CompilerResult compilerResult) {
            CompilerAnalyzer.CompilerSummary summary = compilerResult.getCompilerSummary();
            CompilerAnalyzer.CompilerThreadAnalysis threadAnalysis = compilerResult.getThreadAnalysis();
            CompilerAnalyzer.CompilationAnalysis compilationAnalysis = compilerResult.getCompilationAnalysis();

            // Summary
            context.put("compilerThreadCount", summary.compilerThreadCount());
            context.put("activeCompilerThreads", summary.activeCompilerThreads());
            context.put("totalCompilations", summary.totalCompilations());
            context.put("totalDeoptimizations", summary.totalDeoptimizations());
            context.put("hasIssues", summary.hasIssues());
            context.put("hasData", summary.compilerThreadCount() > 0);
            context.put("hasJfrData", compilationAnalysis != null);

            // C1/C2 breakdown
            if (threadAnalysis != null) {
                context.put("activeC1Threads", threadAnalysis.activeC1Threads());
                context.put("activeC2Threads", threadAnalysis.activeC2Threads());
                context.put("hasC1Activity", threadAnalysis.hasC1Activity());
                context.put("hasC2Activity", threadAnalysis.hasC2Activity());
            }

            // Compiler thread details
            if (threadAnalysis != null && !threadAnalysis.compilerThreads().isEmpty()) {
                List<Map<String, Object>> threads = new ArrayList<>();
                for (var thread : threadAnalysis.compilerThreads().stream()
                        .limit(options.getMaxThreads()).toList()) {
                    Map<String, Object> threadMap = new LinkedHashMap<>();
                    threadMap.put("name", thread.name());
                    threadMap.put("state", thread.state().name());
                    threadMap.put("isActive", thread.state() == Thread.State.RUNNABLE);
                    threads.add(threadMap);
                }
                context.put("compilerThreads", threads);
                context.put("hasCompilerThreads", true);
            } else {
                context.put("hasCompilerThreads", false);
            }

            // Compilation events (if JFR data available)
            if (compilationAnalysis != null) {
                if (!compilationAnalysis.topCompilations().isEmpty()) {
                    List<Map<String, Object>> compilations = new ArrayList<>();
                    for (var comp : compilationAnalysis.topCompilations()) {
                        Map<String, Object> compMap = new LinkedHashMap<>();
                        compMap.put("method", formatMethod(comp.method()));
                        compMap.put("fullMethod", comp.method());
                        compMap.put("level", "C" + comp.level());
                        compMap.put("durationMs", comp.durationMs());
                        compMap.put("success", comp.success());
                        compilations.add(compMap);
                    }
                    context.put("topCompilations", compilations);
                    context.put("hasCompilations", true);
                } else {
                    context.put("hasCompilations", false);
                }

                if (!compilationAnalysis.topDeoptimizations().isEmpty()) {
                    List<Map<String, Object>> deopts = new ArrayList<>();
                    for (var deopt : compilationAnalysis.topDeoptimizations()) {
                        Map<String, Object> deoptMap = new LinkedHashMap<>();
                        deoptMap.put("method", formatMethod(deopt.method()));
                        deoptMap.put("fullMethod", deopt.method());
                        deoptMap.put("reason", deopt.reason());
                        deoptMap.put("action", deopt.action());
                        deopts.add(deoptMap);
                    }
                    context.put("topDeoptimizations", deopts);
                    context.put("hasDeopts", true);
                } else {
                    context.put("hasDeopts", false);
                }
            }

            // Temporal analysis (multi-dump)
            if (compilerResult.hasTemporal()) {
                CompilerAnalyzer.CompilerTemporal temporal = compilerResult.getTemporal();
                context.put("hasTemporal", true);
                context.put("isMultiDump", true);

                // Compilation trend
                if (temporal.hasCompilationTrend()) {
                    var compilationTrend = temporal.compilationTrend();
                    Map<String, Object> compTrendInfo = new LinkedHashMap<>();
                    compTrendInfo.put("direction", compilationTrend.direction().name());
                    compTrendInfo.put("changeRate", String.format(Locale.US, "%.1f", compilationTrend.changeRate()));
                    compTrendInfo.put("isIncreasing", compilationTrend.isIncreasing());
                    compTrendInfo.put("isDecreasing", compilationTrend.isDecreasing());
                    context.put("compilationTrend", compTrendInfo);
                }

                // Deoptimization trend
                if (temporal.hasDeoptTrend()) {
                    var deoptTrend = temporal.deoptTrend();
                    Map<String, Object> deoptTrendInfo = new LinkedHashMap<>();
                    deoptTrendInfo.put("direction", deoptTrend.direction().name());
                    deoptTrendInfo.put("changeRate", String.format(Locale.US, "%.1f", deoptTrend.changeRate()));
                    deoptTrendInfo.put("isIncreasing", deoptTrend.isIncreasing());
                    deoptTrendInfo.put("isDecreasing", deoptTrend.isDecreasing());
                    context.put("deoptTrend", deoptTrendInfo);
                }

                // Active threads trend
                if (temporal.hasActiveThreadsTrend()) {
                    var activeThreadsTrend = temporal.activeThreadsTrend();
                    Map<String, Object> threadsTrendInfo = new LinkedHashMap<>();
                    threadsTrendInfo.put("direction", activeThreadsTrend.direction().name());
                    threadsTrendInfo.put("changeRate", String.format(Locale.US, "%.1f", activeThreadsTrend.changeRate()));
                    threadsTrendInfo.put("isIncreasing", activeThreadsTrend.isIncreasing());
                    threadsTrendInfo.put("isDecreasing", activeThreadsTrend.isDecreasing());
                    context.put("activeThreadsTrend", threadsTrendInfo);
                }

                // Per-dump data for charts
                List<Integer> dumpLabels = new ArrayList<>();
                int maxSize = Math.max(temporal.compilationsPerDump().size(),
                        Math.max(temporal.deoptimizationsPerDump().size(),
                                temporal.activeCompilerThreadsPerDump().size()));
                for (int i = 1; i <= maxSize; i++) {
                    dumpLabels.add(i);
                }
                context.put("dumpLabels", dumpLabels);
                context.put("compilationsPerDump", temporal.compilationsPerDump());
                context.put("deoptsPerDump", temporal.deoptimizationsPerDump());
                context.put("activeThreadsPerDump", temporal.activeCompilerThreadsPerDump());

            } else {
                context.put("hasTemporal", false);
                context.put("isMultiDump", false);
            }
        }

        return context;
    }

    private String formatMethod(String method) {
        if (method == null) return "unknown";
        int lastDot = method.lastIndexOf('.');
        if (lastDot > 0) {
            int prevDot = method.lastIndexOf('.', lastDot - 1);
            if (prevDot > 0) return "..." + method.substring(prevDot + 1);
        }
        return method;
    }
}