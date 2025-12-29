package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.model.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzer that examines thread pool behavior and health.
 */
public class ThreadPoolAnalyzer implements Analyzer<ThreadPoolAnalyzer.ThreadPoolResult> {

    private static final String NAME = "ThreadPoolAnalyzer";

    private static final List<PoolPattern> POOL_PATTERNS = List.of(
            new PoolPattern("pool-(\\d+)-thread-(\\d+)", "ExecutorService"),
            new PoolPattern("ForkJoinPool[-.](\\d+)-worker-(\\d+)", "ForkJoinPool"),
            new PoolPattern("ForkJoinPool\\.commonPool-worker-(\\d+)", "CommonPool"),
            new PoolPattern("scheduling-(\\d+)", "ScheduledExecutor"),
            new PoolPattern("Timer-(\\d+)", "Timer"),
            new PoolPattern("(\\w+)-pool-(\\d+)-thread-(\\d+)", "Custom"),
            new PoolPattern("(\\w+)ThreadPool-(\\d+)", "Custom"),
            new PoolPattern("(\\w+)-worker-(\\d+)", "Worker")
    );

    private record PoolPattern(String regex, String type) {
        Pattern compiled() {
            return Pattern.compile(regex);
        }
    }

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Analyzes thread pool behavior, utilization, and health";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        return true;
    }

    @Override
    public int getPriority() {
        return 45;
    }

    @Override
    public @NotNull ThreadPoolResult analyze(@NotNull AnalysisContext context) {
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;
        List<PoolSnapshot> allSnapshots = new ArrayList<>();

        for (int dumpIndex = 0; dumpIndex < context.getDumpCount(); dumpIndex++) {
            ThreadDump dump = context.getDumps().get(dumpIndex);
            List<ThreadInfo> filtered = context.getFilteredThreads(dump);

            Map<String, List<ThreadInfo>> threadsByPool = new LinkedHashMap<>();

            for (ThreadInfo thread : filtered) {
                String poolName = identifyPool(thread);
                if (poolName != null) {
                    threadsByPool.computeIfAbsent(poolName, k -> new ArrayList<>()).add(thread);
                }
            }

            for (Map.Entry<String, List<ThreadInfo>> entry : threadsByPool.entrySet()) {
                PoolSnapshot snapshot = analyzePool(entry.getKey(), entry.getValue(), dumpIndex, dump.timestamp());
                allSnapshots.add(snapshot);
            }
        }

        Map<String, List<PoolSnapshot>> snapshotsByPool = new LinkedHashMap<>();
        for (PoolSnapshot snapshot : allSnapshots) {
            snapshotsByPool.computeIfAbsent(snapshot.poolName(), k -> new ArrayList<>()).add(snapshot);
        }

        for (Map.Entry<String, List<PoolSnapshot>> entry : snapshotsByPool.entrySet()) {
            List<AnalysisResult.Finding> poolFindings = analyzePoolTrends(entry.getKey(), entry.getValue());
            findings.addAll(poolFindings);

            for (AnalysisResult.Finding finding : poolFindings) {
                if (finding.severity().isWorseThan(worstSeverity)) {
                    worstSeverity = finding.severity();
                }
            }
        }

        int totalPoolThreads = allSnapshots.stream()
                .filter(s -> s.dumpIndex() == 0)
                .mapToInt(PoolSnapshot::totalThreads)
                .sum();

        ThreadPoolSummary summary = new ThreadPoolSummary(
                snapshotsByPool.size(),
                totalPoolThreads,
                countExhaustedPools(snapshotsByPool),
                countIdlePools(snapshotsByPool)
        );

        return new ThreadPoolResult(worstSeverity, findings, allSnapshots, snapshotsByPool, summary);
    }

    private String identifyPool(ThreadInfo thread) {
        String name = thread.name();
        if (name == null) return null;

        for (PoolPattern pattern : POOL_PATTERNS) {
            Matcher matcher = pattern.compiled().matcher(name);
            if (matcher.find()) {
                if (name.startsWith("pool-") && matcher.groupCount() >= 2) {
                    return "pool-" + matcher.group(1);
                }
                if (name.startsWith("ForkJoinPool")) {
                    if (name.contains("commonPool")) {
                        return "ForkJoinPool.commonPool";
                    }
                    return "ForkJoinPool-" + matcher.group(1);
                }
                return matcher.group(0).replaceAll("-\\d+$", "");
            }
        }

        if (name.contains("pool") || name.contains("Pool") ||
            name.contains("worker") || name.contains("Worker") ||
            name.contains("executor") || name.contains("Executor")) {
            return name.replaceAll("-?\\d+$", "");
        }

        return null;
    }

    private PoolSnapshot analyzePool(String poolName, List<ThreadInfo> threads,
                                     int dumpIndex, java.time.Instant timestamp) {
        int runnable = 0, waiting = 0, blocked = 0, timedWaiting = 0;
        long totalCpuMs = 0;

        for (ThreadInfo thread : threads) {
            if (thread.state() != null) {
                switch (thread.state()) {
                    case RUNNABLE -> runnable++;
                    case WAITING -> waiting++;
                    case BLOCKED -> blocked++;
                    case TIMED_WAITING -> timedWaiting++;
                    default -> {}
                }
            }
            if (thread.cpuTimeMs() != null) {
                totalCpuMs += thread.cpuTimeMs();
            }
        }

        int active = runnable + blocked;
        int idle = waiting + timedWaiting;
        double utilizationPercent = threads.isEmpty() ? 0 : (active * 100.0) / threads.size();

        return new PoolSnapshot(poolName, dumpIndex, timestamp, threads.size(),
                runnable, waiting, blocked, timedWaiting, active, idle, utilizationPercent, totalCpuMs);
    }

    private List<AnalysisResult.Finding> analyzePoolTrends(String poolName, List<PoolSnapshot> snapshots) {
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        if (snapshots.isEmpty()) return findings;

        for (PoolSnapshot snapshot : snapshots) {
            if (snapshot.totalThreads() > 0 && snapshot.utilizationPercent() >= 95) {
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.WARNING, "pool-exhausted",
                        String.format("Thread pool '%s' exhausted: %d/%d active (%.0f%%)",
                                poolName, snapshot.activeThreads(), snapshot.totalThreads(),
                                snapshot.utilizationPercent()))
                        .detail("poolName", poolName)
                        .detail("dumpIndex", snapshot.dumpIndex())
                        .build());
                break;
            }
        }

        int maxBlocked = snapshots.stream().mapToInt(PoolSnapshot::blockedThreads).max().orElse(0);
        if (maxBlocked >= 3) {
            findings.add(AnalysisResult.Finding.builder(
                    AnalysisResult.Severity.WARNING, "pool-blocked",
                    String.format("Thread pool '%s' has %d blocked threads", poolName, maxBlocked))
                    .detail("poolName", poolName)
                    .detail("blockedThreads", maxBlocked)
                    .build());
        }

        return findings;
    }

    private int countExhaustedPools(Map<String, List<PoolSnapshot>> snapshotsByPool) {
        return (int) snapshotsByPool.values().stream()
                .filter(snapshots -> snapshots.stream().anyMatch(s -> s.utilizationPercent() >= 95))
                .count();
    }

    private int countIdlePools(Map<String, List<PoolSnapshot>> snapshotsByPool) {
        return (int) snapshotsByPool.values().stream()
                .filter(snapshots -> snapshots.stream().allMatch(s -> s.utilizationPercent() <= 10))
                .count();
    }

    public record PoolSnapshot(
            String poolName, int dumpIndex, java.time.Instant timestamp, int totalThreads,
            int runnableThreads, int waitingThreads, int blockedThreads, int timedWaitingThreads,
            int activeThreads, int idleThreads, double utilizationPercent, long totalCpuMs
    ) {}

    public record ThreadPoolSummary(int poolCount, int totalPoolThreads, int exhaustedPoolCount, int idlePoolCount) {}

    public static class ThreadPoolResult extends AnalysisResult {
        private final List<PoolSnapshot> snapshots;
        private final Map<String, List<PoolSnapshot>> snapshotsByPool;
        private final ThreadPoolSummary summary;

        public ThreadPoolResult(Severity severity, List<Finding> findings,
                               List<PoolSnapshot> snapshots,
                               Map<String, List<PoolSnapshot>> snapshotsByPool,
                               ThreadPoolSummary summary) {
            super(NAME, severity, findings);
            this.snapshots = List.copyOf(snapshots);
            this.snapshotsByPool = Map.copyOf(snapshotsByPool);
            this.summary = summary;
        }

        public List<PoolSnapshot> getSnapshots() { return snapshots; }
        public Map<String, List<PoolSnapshot>> getSnapshotsByPool() { return snapshotsByPool; }
        public ThreadPoolSummary getPoolSummary() { return summary; }

        @Override
        public String getSummary() {
            if (summary.poolCount() == 0) return "No thread pools detected";
            if (summary.exhaustedPoolCount() > 0) {
                return String.format("%d pools (%d exhausted)", summary.poolCount(), summary.exhaustedPoolCount());
            }
            return String.format("%d pools with %d threads", summary.poolCount(), summary.totalPoolThreads());
        }
    }
}