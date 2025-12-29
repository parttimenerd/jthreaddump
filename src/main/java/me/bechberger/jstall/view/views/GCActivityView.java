package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.GCActivityAnalyzer;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputFormat;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for GCActivityAnalyzer results.
 * Shows GC thread activity, CPU usage, and safepoint information.
 */
public class GCActivityView extends HandlebarsViewRenderer {

    public GCActivityView() {
        super("gc-activity", "gc-activity", GCActivityAnalyzer.GCActivityResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof GCActivityAnalyzer.GCActivityResult gcResult) {
            GCActivityAnalyzer.GCSummary summary = gcResult.getGCSummary();

            // Summary data
            context.put("avgGcCpuPercentage", String.format(Locale.US, "%.1f", summary.avgGcCpuPercentage()));
            context.put("maxGcCpuPercentage", String.format(Locale.US, "%.1f", summary.maxGcCpuPercentage()));
            context.put("maxRunnableGcThreads", summary.maxRunnableGcThreads());
            context.put("maxThreadsAtSafepoint", summary.maxThreadsAtSafepoint());
            context.put("potentialSTW", summary.potentialSTW());
            context.put("highGcCpu", summary.highGcCpu());
            context.put("hasIssues", summary.potentialSTW() || summary.highGcCpu());

            // Snapshots for multi-dump analysis
            List<GCActivityAnalyzer.GCSnapshot> snapshots = gcResult.getSnapshots();
            context.put("snapshotCount", snapshots.size());
            context.put("isMultiDump", snapshots.size() > 1);

            // Format snapshots for display
            List<Map<String, Object>> formattedSnapshots = new ArrayList<>();
            for (GCActivityAnalyzer.GCSnapshot snapshot : snapshots) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("dumpIndex", snapshot.dumpIndex() + 1);
                s.put("gcThreadCount", snapshot.gcThreadCount());
                s.put("runnableGcThreads", snapshot.runnableGcThreads());
                s.put("appThreadCount", snapshot.appThreadCount());
                s.put("threadsAtSafepoint", snapshot.threadsAtSafepoint());
                s.put("gcCpuPercentage", String.format(Locale.US, "%.1f", snapshot.gcCpuPercentage()));
                s.put("totalGcCpuMs", snapshot.totalGcCpuMs());
                s.put("totalAppCpuMs", snapshot.totalAppCpuMs());
                formattedSnapshots.add(s);
            }
            context.put("snapshots", formattedSnapshots);

            // Chart data for HTML
            if (options.getFormat() == OutputFormat.HTML && snapshots.size() > 1) {
                List<Integer> dumpLabels = new ArrayList<>();
                List<String> gcCpuData = new ArrayList<>();
                List<Integer> safepointData = new ArrayList<>();
                List<Integer> gcThreadData = new ArrayList<>();

                for (int i = 0; i < snapshots.size(); i++) {
                    GCActivityAnalyzer.GCSnapshot s = snapshots.get(i);
                    dumpLabels.add(i + 1);
                    gcCpuData.add(String.format(Locale.US, "%.1f", s.gcCpuPercentage()));
                    safepointData.add(s.threadsAtSafepoint());
                    gcThreadData.add(s.runnableGcThreads());
                }

                context.put("dumpLabels", dumpLabels);
                context.put("gcCpuData", gcCpuData);
                context.put("safepointData", safepointData);
                context.put("gcThreadData", gcThreadData);

                // Trend data
                if (snapshots.size() >= 2) {
                    GCActivityAnalyzer.GCSnapshot first = snapshots.get(0);
                    GCActivityAnalyzer.GCSnapshot last = snapshots.get(snapshots.size() - 1);

                    List<Map<String, Object>> trendData = new ArrayList<>();
                    trendData.add(createTrendRow("GC CPU %",
                            String.format(Locale.US, "%.1f%%", first.gcCpuPercentage()),
                            String.format(Locale.US, "%.1f%%", last.gcCpuPercentage()),
                            last.gcCpuPercentage() - first.gcCpuPercentage()));
                    trendData.add(createTrendRow("Safepoint Threads",
                            String.valueOf(first.threadsAtSafepoint()),
                            String.valueOf(last.threadsAtSafepoint()),
                            last.threadsAtSafepoint() - first.threadsAtSafepoint()));
                    trendData.add(createTrendRow("Active GC Threads",
                            String.valueOf(first.runnableGcThreads()),
                            String.valueOf(last.runnableGcThreads()),
                            last.runnableGcThreads() - first.runnableGcThreads()));

                    context.put("trendData", trendData);
                }
            }
        }

        return context;
    }

    private Map<String, Object> createTrendRow(String metric, String first, String last, double change) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metric", metric);
        row.put("first", first);
        row.put("last", last);

        if (change > 0.1) {
            row.put("changeDisplay", String.format(Locale.US, "+%.1f ↑", change));
            row.put("changeClass", "change-up");
        } else if (change < -0.1) {
            row.put("changeDisplay", String.format(Locale.US, "%.1f ↓", change));
            row.put("changeClass", "change-down");
        } else {
            row.put("changeDisplay", "→");
            row.put("changeClass", "change-same");
        }
        return row;
    }
}