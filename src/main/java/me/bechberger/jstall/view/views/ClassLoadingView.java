package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.ClassLoadingAnalyzer;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for ClassLoadingAnalyzer results.
 * Shows class loading activity from JFR data.
 */
public class ClassLoadingView extends HandlebarsViewRenderer {

    public ClassLoadingView() {
        super("class-loading", "class-loading", ClassLoadingAnalyzer.ClassLoadingResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof ClassLoadingAnalyzer.ClassLoadingResult classResult) {
            ClassLoadingAnalyzer.ClassLoadingSummary summary = classResult.getClassLoadingSummary();

            // Summary
            context.put("totalClasses", summary.totalClasses());
            context.put("totalDurationMs", summary.totalDuration().toMillis());
            context.put("slowLoadCount", classResult.getSlowLoads().size());
            context.put("excessiveLoading", summary.excessiveLoading());
            context.put("slowLoading", summary.slowLoading());
            context.put("hasIssues", summary.excessiveLoading() || summary.slowLoading());
            context.put("hasData", summary.totalClasses() > 0);

            // Temporal analysis (multi-dump)
            ClassLoadingAnalyzer.LoadingRateTemporal temporal = classResult.getTemporal();
            if (temporal != null && temporal.hasLoadingRateTrend()) {
                context.put("hasTemporal", true);
                context.put("isMultiDump", true);

                // Loading rate trend
                var loadingTrend = temporal.loadingRateTrend();
                Map<String, Object> trendInfo = new LinkedHashMap<>();
                trendInfo.put("direction", loadingTrend.direction().name());
                trendInfo.put("changeRate", String.format(Locale.US, "%.1f", loadingTrend.changeRate()));
                trendInfo.put("isIncreasing", loadingTrend.isIncreasing());
                trendInfo.put("isDecreasing", loadingTrend.isDecreasing());
                trendInfo.put("isWarmup", temporal.isWarmupPattern());
                context.put("loadingRateTrend", trendInfo);

                // Duration trend
                if (temporal.hasDurationTrend()) {
                    var durationTrend = temporal.durationTrend();
                    Map<String, Object> durInfo = new LinkedHashMap<>();
                    durInfo.put("direction", durationTrend.direction().name());
                    durInfo.put("changeRate", String.format(Locale.US, "%.1f", durationTrend.changeRate()));
                    durInfo.put("isIncreasing", durationTrend.isIncreasing());
                    durInfo.put("isDecreasing", durationTrend.isDecreasing());
                    context.put("durationTrend", durInfo);
                }

                // Per-dump data for charts
                List<Integer> dumpLabels = new ArrayList<>();
                for (int i = 1; i <= temporal.classesLoadedPerDump().size(); i++) {
                    dumpLabels.add(i);
                }
                context.put("dumpLabels", dumpLabels);
                context.put("classesPerDump", temporal.classesLoadedPerDump());
                context.put("durationPerDump", temporal.durationMsPerDump());

                // Per-loader evolution (top 5 loaders)
                List<Map<String, Object>> loaderEvos = new ArrayList<>();
                for (Map.Entry<String, List<Integer>> entry : temporal.loaderCountsPerDump().entrySet()) {
                    if (loaderEvos.size() >= 5) break;

                    Map<String, Object> loaderEvo = new LinkedHashMap<>();
                    loaderEvo.put("name", entry.getKey());
                    loaderEvo.put("counts", entry.getValue());

                    int first = entry.getValue().isEmpty() ? 0 : entry.getValue().getFirst();
                    int last = entry.getValue().isEmpty() ? 0 : entry.getValue().getLast();
                    loaderEvo.put("change", last - first);
                    loaderEvo.put("isIncreasing", last > first);

                    loaderEvos.add(loaderEvo);
                }
                context.put("loaderEvolutions", loaderEvos);

            } else {
                context.put("hasTemporal", false);
                context.put("isMultiDump", false);
            }

            // Class load events
            List<Map<String, Object>> events = new ArrayList<>();
            for (var e : classResult.getClassLoadEvents().stream().limit(options.getMaxThreads()).toList()) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("className", truncateClass(e.loadedClass()));
                event.put("fullClassName", e.loadedClass());
                event.put("threadName", e.threadName());
                event.put("classLoader", e.classLoader() != null ? e.classLoader() : "bootstrap");
                event.put("durationMs", e.duration() != null ? e.duration().toMillis() : 0);
                event.put("isSlow", e.duration() != null && e.duration().toMillis() > 50);
                events.add(event);
            }
            context.put("classLoadEvents", events);
            context.put("hasMoreEvents", classResult.getClassLoadEvents().size() > options.getMaxThreads());
            context.put("moreEventCount", classResult.getClassLoadEvents().size() - events.size());

            // By class loader breakdown (from JFR summary if available)
            if (classResult.getJfrSummary() != null) {
                var jfrSummary = classResult.getJfrSummary();
                Map<String, Long> byLoader = new LinkedHashMap<>();
                // Group by class loader
                for (var e : classResult.getClassLoadEvents()) {
                    String loader = e.classLoader() != null ? e.classLoader() : "bootstrap";
                    byLoader.merge(loader, 1L, Long::sum);
                }
                List<Map<String, Object>> loaders = new ArrayList<>();
                for (var entry : byLoader.entrySet()) {
                    Map<String, Object> loader = new LinkedHashMap<>();
                    loader.put("name", entry.getKey());
                    loader.put("count", entry.getValue());
                    loaders.add(loader);
                }
                loaders.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));
                context.put("byClassLoader", loaders);

                // Chart data
                context.put("loaderNames", loaders.stream().map(l -> l.get("name")).toList());
                context.put("loaderCounts", loaders.stream().map(l -> l.get("count")).toList());
            }
        }

        return context;
    }

    private String truncateClass(String className) {
        if (className == null) return "?";
        int lastDot = className.lastIndexOf('.');
        if (lastDot > 0) {
            return className.substring(lastDot + 1);
        }
        return className;
    }
}