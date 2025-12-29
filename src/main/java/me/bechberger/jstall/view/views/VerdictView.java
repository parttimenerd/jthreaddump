package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.VerdictAnalyzer;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for VerdictAnalyzer results.
 * Shows a high-level verdict on application health.
 */
public class VerdictView extends HandlebarsViewRenderer {

    public VerdictView() {
        super("verdict", "verdict", VerdictAnalyzer.VerdictResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof VerdictAnalyzer.VerdictResult verdictResult) {
            // Status
            context.put("status", verdictResult.getStatus().name());
            context.put("statusDescription", verdictResult.getStatus().getDescription());
            context.put("isHealthy", verdictResult.getStatus() == VerdictAnalyzer.VerdictStatus.HEALTHY);
            context.put("isDeadlock", verdictResult.getStatus() == VerdictAnalyzer.VerdictStatus.DEADLOCK);
            context.put("isCritical", verdictResult.getSeverity() == AnalysisResult.Severity.CRITICAL ||
                    verdictResult.getSeverity() == AnalysisResult.Severity.ERROR);

            // Counts
            context.put("dumpCount", verdictResult.getDumpCount());
            context.put("totalThreads", verdictResult.getTotalThreads());
            context.put("isMultiDump", verdictResult.getDumpCount() > 1);

            // Verdict items
            List<Map<String, Object>> items = new ArrayList<>();
            for (VerdictAnalyzer.VerdictItem item : verdictResult.getItems()) {
                Map<String, Object> itemMap = new LinkedHashMap<>();
                itemMap.put("category", item.category());
                itemMap.put("summary", item.summary());
                itemMap.put("severity", item.severity().name());
                itemMap.put("isOk", item.severity() == AnalysisResult.Severity.OK);
                itemMap.put("isWarning", item.severity() == AnalysisResult.Severity.WARNING);
                itemMap.put("isError", item.severity() == AnalysisResult.Severity.ERROR ||
                        item.severity() == AnalysisResult.Severity.CRITICAL);
                items.add(itemMap);
            }
            context.put("verdictItems", items);

            // Time distribution
            VerdictAnalyzer.TimeDistribution dist = verdictResult.getTimeDistribution();
            context.put("runningPercent", String.format(Locale.US, "%.1f", dist.running()));
            context.put("blockedPercent", String.format(Locale.US, "%.1f", dist.blocked()));
            context.put("waitingPercent", String.format(Locale.US, "%.1f", dist.waiting()));
            context.put("ioPercent", String.format(Locale.US, "%.1f", dist.io()));
            context.put("gcPercent", String.format(Locale.US, "%.1f", dist.gc()));

            // Chart data
            context.put("timeDistLabels", List.of("Running", "Blocked", "Waiting", "I/O", "GC"));
            context.put("timeDistData", List.of(dist.running(), dist.blocked(), dist.waiting(), dist.io(), dist.gc()));

            // Smart health evolution (multi-dump)
            if (verdictResult.hasHealthEvolution()) {
                VerdictAnalyzer.HealthEvolution health = verdictResult.getHealthEvolution();
                context.put("hasHealthEvolution", true);

                // Health scores
                context.put("healthScoresPerDump", health.healthScoresPerDump());
                context.put("statusPerDump", health.statusPerDump());
                context.put("firstScore", String.format(Locale.US, "%.0f", health.firstScore()));
                context.put("lastScore", String.format(Locale.US, "%.0f", health.lastScore()));
                context.put("scoreChange", String.format(Locale.US, "%+.0f", health.scoreChange()));
                context.put("changePercent", String.format(Locale.US, "%+.1f", health.changePercent()));

                // Trend
                context.put("healthTrend", health.trend());
                context.put("isDegrading", health.isDegrading());
                context.put("isImproving", health.isImproving());
                context.put("healthStatus", health.getHealthStatus());

                // Critical changes
                context.put("hasCriticalChange", health.hasCriticalChange());
                if (health.hasCriticalChange()) {
                    context.put("criticalChangeDump", health.criticalChangeDump() + 1); // Convert to 1-based
                }

                // Problem categories
                context.put("degradingCategories", health.degradingCategories());
                context.put("improvingCategories", health.improvingCategories());
                context.put("hasDegradingCategories", !health.degradingCategories().isEmpty());
                context.put("hasImprovingCategories", !health.improvingCategories().isEmpty());

                // Per-category evolution for charts
                List<Map<String, Object>> categoryEvos = new ArrayList<>();
                for (Map.Entry<String, List<Double>> entry : health.categoryScoresPerDump().entrySet()) {
                    Map<String, Object> catMap = new LinkedHashMap<>();
                    catMap.put("name", entry.getKey());
                    catMap.put("scores", entry.getValue());

                    double first = entry.getValue().isEmpty() ? 0 : entry.getValue().getFirst();
                    double last = entry.getValue().isEmpty() ? 0 : entry.getValue().getLast();
                    double change = last - first;

                    catMap.put("change", String.format(Locale.US, "%+.0f", change));
                    catMap.put("isImproving", change > 10);
                    catMap.put("isDegrading", change < -10);

                    categoryEvos.add(catMap);
                }
                context.put("categoryEvolutions", categoryEvos);

                // Dump labels
                List<Integer> dumpLabels = new ArrayList<>();
                for (int i = 1; i <= health.healthScoresPerDump().size(); i++) {
                    dumpLabels.add(i);
                }
                context.put("dumpLabels", dumpLabels);

            } else {
                context.put("hasHealthEvolution", false);
            }
        }

        return context;
    }
}