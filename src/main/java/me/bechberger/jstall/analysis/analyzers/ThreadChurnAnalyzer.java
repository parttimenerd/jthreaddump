package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.analysis.AnalysisContext.ThreadIdentifier;
import me.bechberger.jstall.model.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Analyzer that detects thread creation and destruction patterns.
 * Useful for detecting thread leaks or executor mismanagement.
 */
public class ThreadChurnAnalyzer implements Analyzer<ThreadChurnAnalyzer.ThreadChurnResult> {

    private static final String NAME = "ThreadChurnAnalyzer";

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Detects thread creation/destruction patterns to identify leaks or churn";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        return context.isMultiDump();
    }

    @Override
    public boolean requiresMultipleDumps() {
        return true;
    }

    @Override
    public int getPriority() {
        return 60;
    }

    @Override
    public @NotNull ThreadChurnResult analyze(@NotNull AnalysisContext context) {
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;

        List<Integer> threadCounts = new ArrayList<>();
        List<Set<ThreadIdentifier>> threadSets = new ArrayList<>();

        for (ThreadDump dump : context.getDumps()) {
            List<ThreadInfo> filtered = context.getFilteredThreads(dump);
            threadCounts.add(filtered.size());

            Set<ThreadIdentifier> ids = new LinkedHashSet<>();
            for (ThreadInfo thread : filtered) {
                ids.add(ThreadIdentifier.of(thread));
            }
            threadSets.add(ids);
        }

        List<ChurnEvent> churnEvents = new ArrayList<>();
        int totalCreated = 0;
        int totalDestroyed = 0;

        for (int i = 1; i < threadSets.size(); i++) {
            Set<ThreadIdentifier> prev = threadSets.get(i - 1);
            Set<ThreadIdentifier> curr = threadSets.get(i);

            Set<ThreadIdentifier> created = new LinkedHashSet<>(curr);
            created.removeAll(prev);

            Set<ThreadIdentifier> destroyed = new LinkedHashSet<>(prev);
            destroyed.removeAll(curr);

            totalCreated += created.size();
            totalDestroyed += destroyed.size();

            if (!created.isEmpty() || !destroyed.isEmpty()) {
                churnEvents.add(new ChurnEvent(
                        i - 1, i,
                        context.getDumps().get(i - 1).timestamp(),
                        context.getDumps().get(i).timestamp(),
                        created.size(),
                        destroyed.size(),
                        new ArrayList<>(created),
                        new ArrayList<>(destroyed)
                ));
            }
        }

        int firstCount = threadCounts.getFirst();
        int lastCount = threadCounts.getLast();
        int netGrowth = lastCount - firstCount;
        double growthRate = context.getTimeSpan() != null && context.getTimeSpan().toSeconds() > 0
                ? (double) netGrowth / context.getTimeSpan().toSeconds()
                : 0;

        boolean potentialLeak = netGrowth > 0 && isMonotonicGrowth(threadCounts);
        boolean highChurn = totalCreated > threadCounts.getFirst() || totalDestroyed > threadCounts.getFirst();

        if (potentialLeak) {
            AnalysisResult.Severity severity = netGrowth >= 10
                    ? AnalysisResult.Severity.WARNING
                    : AnalysisResult.Severity.INFO;
            if (severity.isWorseThan(worstSeverity)) {
                worstSeverity = severity;
            }

            findings.add(AnalysisResult.Finding.builder(severity, "thread-growth",
                    String.format("Thread count grew from %d to %d (+%d, %.2f/sec)",
                            firstCount, lastCount, netGrowth, growthRate))
                    .detail("firstCount", firstCount)
                    .detail("lastCount", lastCount)
                    .detail("netGrowth", netGrowth)
                    .detail("growthRate", growthRate)
                    .build());
        }

        if (highChurn) {
            AnalysisResult.Severity severity = AnalysisResult.Severity.INFO;
            if (severity.isWorseThan(worstSeverity)) {
                worstSeverity = severity;
            }

            findings.add(AnalysisResult.Finding.builder(severity, "thread-churn",
                    String.format("High thread churn: %d created, %d destroyed",
                            totalCreated, totalDestroyed))
                    .detail("totalCreated", totalCreated)
                    .detail("totalDestroyed", totalDestroyed)
                    .build());
        }

        Map<String, Integer> poolGrowth = analyzePoolGrowth(threadSets);
        for (Map.Entry<String, Integer> entry : poolGrowth.entrySet()) {
            if (entry.getValue() >= 5) {
                AnalysisResult.Severity severity = entry.getValue() >= 10
                        ? AnalysisResult.Severity.WARNING
                        : AnalysisResult.Severity.INFO;
                if (severity.isWorseThan(worstSeverity)) {
                    worstSeverity = severity;
                }

                findings.add(AnalysisResult.Finding.builder(severity, "pool-growth",
                        String.format("Thread pool '%s' grew by %d threads",
                                entry.getKey(), entry.getValue()))
                        .detail("poolName", entry.getKey())
                        .detail("growth", entry.getValue())
                        .build());
            }
        }

        ThreadChurnSummary summary = new ThreadChurnSummary(
                firstCount, lastCount, netGrowth, totalCreated, totalDestroyed,
                potentialLeak, highChurn
        );

        return new ThreadChurnResult(worstSeverity, findings, threadCounts, churnEvents, summary);
    }

    private boolean isMonotonicGrowth(List<Integer> counts) {
        if (counts.size() < 2) return false;
        for (int i = 1; i < counts.size(); i++) {
            if (counts.get(i) < counts.get(i - 1)) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Integer> analyzePoolGrowth(List<Set<ThreadIdentifier>> threadSets) {
        Map<String, Integer> firstPoolCounts = countByPoolPrefix(threadSets.getFirst());
        Map<String, Integer> lastPoolCounts = countByPoolPrefix(threadSets.getLast());

        Map<String, Integer> growth = new LinkedHashMap<>();
        for (String pool : lastPoolCounts.keySet()) {
            int first = firstPoolCounts.getOrDefault(pool, 0);
            int last = lastPoolCounts.get(pool);
            if (last > first) {
                growth.put(pool, last - first);
            }
        }
        return growth;
    }

    private Map<String, Integer> countByPoolPrefix(Set<ThreadIdentifier> threads) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ThreadIdentifier id : threads) {
            String name = id.name();
            if (name == null) continue;

            String poolPrefix = extractPoolPrefix(name);
            if (poolPrefix != null) {
                counts.merge(poolPrefix, 1, Integer::sum);
            }
        }
        return counts;
    }

    private String extractPoolPrefix(String threadName) {
        if (threadName.matches("pool-\\d+-thread-\\d+")) {
            return threadName.replaceAll("-thread-\\d+$", "");
        }
        if (threadName.matches("ForkJoinPool.*-worker-\\d+")) {
            return threadName.replaceAll("-worker-\\d+$", "");
        }
        if (threadName.matches(".*-\\d+$")) {
            return threadName.replaceAll("-\\d+$", "");
        }
        return null;
    }

    public record ChurnEvent(
            int fromDumpIndex,
            int toDumpIndex,
            java.time.Instant fromTimestamp,
            java.time.Instant toTimestamp,
            int createdCount,
            int destroyedCount,
            List<ThreadIdentifier> created,
            List<ThreadIdentifier> destroyed
    ) {
        public ChurnEvent {
            created = created != null ? List.copyOf(created) : List.of();
            destroyed = destroyed != null ? List.copyOf(destroyed) : List.of();
        }

        public int netChange() {
            return createdCount - destroyedCount;
        }
    }

    public record ThreadChurnSummary(
            int firstCount,
            int lastCount,
            int netGrowth,
            int totalCreated,
            int totalDestroyed,
            boolean potentialLeak,
            boolean highChurn
    ) {
        public double churnRate() {
            if (firstCount == 0) return 0;
            return (double) (totalCreated + totalDestroyed) / firstCount;
        }
    }

    public static class ThreadChurnResult extends AnalysisResult {
        private final List<Integer> threadCounts;
        private final List<ChurnEvent> churnEvents;
        private final ThreadChurnSummary summary;

        public ThreadChurnResult(Severity severity, List<Finding> findings,
                                 List<Integer> threadCounts,
                                 List<ChurnEvent> churnEvents,
                                 ThreadChurnSummary summary) {
            super(NAME, severity, findings);
            this.threadCounts = List.copyOf(threadCounts);
            this.churnEvents = List.copyOf(churnEvents);
            this.summary = summary;
        }

        public List<Integer> getThreadCounts() {
            return threadCounts;
        }

        public List<ChurnEvent> getChurnEvents() {
            return churnEvents;
        }

        public ThreadChurnSummary getChurnSummary() {
            return summary;
        }

        @Override
        public String getSummary() {
            if (summary.potentialLeak()) {
                return String.format("Thread leak detected: %d → %d (+%d)",
                        summary.firstCount(), summary.lastCount(), summary.netGrowth());
            }
            if (summary.highChurn()) {
                return String.format("High thread churn: %d created, %d destroyed",
                        summary.totalCreated(), summary.totalDestroyed());
            }
            return String.format("Thread count stable: %d → %d",
                    summary.firstCount(), summary.lastCount());
        }
    }
}