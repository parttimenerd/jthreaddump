package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.ThreadPoolAnalyzer;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputFormat;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for ThreadPoolAnalyzer results.
 * Shows thread pool health, utilization, and trends.
 */
public class ThreadPoolView extends HandlebarsViewRenderer {

    public ThreadPoolView() {
        super("thread-pool", "thread-pool", ThreadPoolAnalyzer.ThreadPoolResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof ThreadPoolAnalyzer.ThreadPoolResult poolResult) {
            ThreadPoolAnalyzer.ThreadPoolSummary summary = poolResult.getPoolSummary();

            // Summary data
            context.put("poolCount", summary.poolCount());
            context.put("totalPoolThreads", summary.totalPoolThreads());
            context.put("exhaustedPoolCount", summary.exhaustedPoolCount());
            context.put("idlePoolCount", summary.idlePoolCount());
            context.put("hasPools", summary.poolCount() > 0);
            context.put("hasExhausted", summary.exhaustedPoolCount() > 0);

            // Format pools for display
            Map<String, List<ThreadPoolAnalyzer.PoolSnapshot>> byPool = poolResult.getSnapshotsByPool();
            List<Map<String, Object>> pools = new ArrayList<>();

            for (Map.Entry<String, List<ThreadPoolAnalyzer.PoolSnapshot>> entry : byPool.entrySet()) {
                Map<String, Object> poolInfo = new LinkedHashMap<>();
                String poolName = entry.getKey();
                List<ThreadPoolAnalyzer.PoolSnapshot> snapshots = entry.getValue();

                poolInfo.put("name", poolName);
                poolInfo.put("snapshotCount", snapshots.size());

                // Latest snapshot info
                ThreadPoolAnalyzer.PoolSnapshot latest = snapshots.get(snapshots.size() - 1);
                poolInfo.put("totalThreads", latest.totalThreads());
                poolInfo.put("activeThreads", latest.activeThreads());
                poolInfo.put("idleThreads", latest.idleThreads());
                poolInfo.put("blockedThreads", latest.blockedThreads());
                poolInfo.put("utilization", String.format(Locale.US, "%.0f", latest.utilizationPercent()));
                poolInfo.put("isExhausted", latest.utilizationPercent() >= 95);
                poolInfo.put("isIdle", latest.utilizationPercent() <= 10);

                // Multi-dump trends
                if (snapshots.size() > 1) {
                    ThreadPoolAnalyzer.PoolSnapshot first = snapshots.get(0);
                    int sizeChange = latest.totalThreads() - first.totalThreads();
                    double utilChange = latest.utilizationPercent() - first.utilizationPercent();

                    poolInfo.put("sizeChange", sizeChange);
                    poolInfo.put("sizeChangeDisplay", formatChange(sizeChange));
                    poolInfo.put("utilChange", String.format(Locale.US, "%.0f", utilChange));
                    poolInfo.put("utilChangeDisplay", formatChange(utilChange));
                    poolInfo.put("hasTrend", true);

                    // History for charts
                    List<Integer> totalHistory = new ArrayList<>();
                    List<Integer> activeHistory = new ArrayList<>();
                    List<String> utilHistory = new ArrayList<>();
                    for (ThreadPoolAnalyzer.PoolSnapshot s : snapshots) {
                        totalHistory.add(s.totalThreads());
                        activeHistory.add(s.activeThreads());
                        utilHistory.add(String.format(Locale.US, "%.0f", s.utilizationPercent()));
                    }
                    poolInfo.put("totalHistory", totalHistory);
                    poolInfo.put("activeHistory", activeHistory);
                    poolInfo.put("utilHistory", utilHistory);
                } else {
                    poolInfo.put("hasTrend", false);
                }

                pools.add(poolInfo);
            }

            // Sort by utilization descending
            pools.sort((a, b) -> Double.compare(
                    Double.parseDouble((String) b.get("utilization")),
                    Double.parseDouble((String) a.get("utilization"))));
            context.put("pools", pools);

            // Dump labels for charts
            int maxSnapshots = byPool.values().stream()
                    .mapToInt(List::size).max().orElse(1);
            List<Integer> dumpLabels = new ArrayList<>();
            for (int i = 1; i <= maxSnapshots; i++) {
                dumpLabels.add(i);
            }
            context.put("dumpLabels", dumpLabels);
            context.put("isMultiDump", maxSnapshots > 1);

            // Aggregated data for charts
            List<String> poolNames = pools.stream().map(p -> (String) p.get("name")).toList();
            List<Integer> activeData = pools.stream().map(p -> (Integer) p.get("activeThreads")).toList();
            List<Integer> idleData = pools.stream().map(p -> (Integer) p.get("idleThreads")).toList();
            context.put("poolNames", poolNames);
            context.put("activeData", activeData);
            context.put("idleData", idleData);
        }

        return context;
    }

    private String formatChange(double change) {
        if (change > 0) return "+" + (int) change + " ↑";
        if (change < 0) return (int) change + " ↓";
        return "→";
    }
}