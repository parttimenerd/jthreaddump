package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.ThreadChurnAnalyzer;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputFormat;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for ThreadChurnAnalyzer results.
 * Shows thread creation/destruction patterns and potential leaks.
 */
public class ThreadChurnView extends HandlebarsViewRenderer {

    public ThreadChurnView() {
        super("thread-churn", "thread-churn", ThreadChurnAnalyzer.ThreadChurnResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof ThreadChurnAnalyzer.ThreadChurnResult churnResult) {
            ThreadChurnAnalyzer.ThreadChurnSummary summary = churnResult.getChurnSummary();

            // Summary data
            context.put("firstCount", summary.firstCount());
            context.put("lastCount", summary.lastCount());
            context.put("netGrowth", summary.netGrowth());
            context.put("totalCreated", summary.totalCreated());
            context.put("totalDestroyed", summary.totalDestroyed());
            context.put("potentialLeak", summary.potentialLeak());
            context.put("highChurn", summary.highChurn());
            context.put("churnRate", String.format(Locale.US, "%.2f", summary.churnRate()));
            context.put("hasIssues", summary.potentialLeak() || summary.highChurn());

            // Thread counts for chart
            List<Integer> threadCounts = churnResult.getThreadCounts();
            context.put("threadCounts", threadCounts);
            context.put("dumpCount", threadCounts.size());
            context.put("isMultiDump", threadCounts.size() > 1);

            // Churn events
            List<ThreadChurnAnalyzer.ChurnEvent> events = churnResult.getChurnEvents();
            List<Map<String, Object>> formattedEvents = new ArrayList<>();
            for (ThreadChurnAnalyzer.ChurnEvent event : events) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("fromDump", event.fromDumpIndex() + 1);
                e.put("toDump", event.toDumpIndex() + 1);
                e.put("created", event.createdCount());
                e.put("destroyed", event.destroyedCount());
                e.put("netChange", event.netChange());
                e.put("isGrowth", event.netChange() > 0);
                e.put("isShrink", event.netChange() < 0);

                // Sample of created threads
                List<String> createdNames = event.created().stream()
                        .limit(5)
                        .map(id -> id.name() != null ? id.name() : "?")
                        .toList();
                e.put("createdSample", createdNames);
                e.put("hasMoreCreated", event.created().size() > 5);
                e.put("moreCreatedCount", event.created().size() - 5);

                formattedEvents.add(e);
            }
            context.put("churnEvents", formattedEvents);
            context.put("hasChurnEvents", !formattedEvents.isEmpty());

            // Chart data for HTML
            if (options.getFormat() == OutputFormat.HTML && threadCounts.size() > 1) {
                List<Integer> dumpLabels = new ArrayList<>();
                List<Integer> createdData = new ArrayList<>();
                List<Integer> destroyedData = new ArrayList<>();

                for (int i = 0; i < threadCounts.size(); i++) {
                    dumpLabels.add(i + 1);
                }
                context.put("dumpLabels", dumpLabels);
                context.put("threadCountData", threadCounts);

                // Churn per interval
                for (ThreadChurnAnalyzer.ChurnEvent event : events) {
                    createdData.add(event.createdCount());
                    destroyedData.add(event.destroyedCount());
                }
                context.put("createdData", createdData);
                context.put("destroyedData", destroyedData);

                // Trend info
                if (summary.netGrowth() > 0) {
                    context.put("trendDirection", "up");
                    context.put("trendClass", "change-up");
                    context.put("trendDisplay", "+" + summary.netGrowth() + " ↑");
                } else if (summary.netGrowth() < 0) {
                    context.put("trendDirection", "down");
                    context.put("trendClass", "change-down");
                    context.put("trendDisplay", summary.netGrowth() + " ↓");
                } else {
                    context.put("trendDirection", "stable");
                    context.put("trendClass", "change-same");
                    context.put("trendDisplay", "→ Stable");
                }
            }
        }

        return context;
    }
}