package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.model.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Meta-analyzer that provides a high-level verdict on application health.
 * Summarizes: thread states, deadlocks, GC, I/O, lock contention, and more.
 */
public class VerdictAnalyzer implements Analyzer<VerdictAnalyzer.VerdictResult> {

    private static final String NAME = "VerdictAnalyzer";

    public enum VerdictStatus {
        HEALTHY("Application appears healthy"),
        SUSPECTED_STALL("Application may be stalled"),
        DEADLOCK("Deadlock detected"),
        HIGH_CONTENTION("High lock contention"),
        IO_BOUND("Application is I/O bound"),
        GC_PRESSURE("GC pressure detected"),
        THREAD_LEAK("Possible thread leak"),
        POOL_EXHAUSTION("Thread pool exhaustion"),
        UNKNOWN("Unable to determine status");

        private final String description;

        VerdictStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Provides a high-level verdict on application health based on all available data";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        return true;
    }

    @Override
    public int getPriority() {
        return 1000; // Highest priority - runs last to collect all findings
    }

    @Override
    public @NotNull VerdictResult analyze(@NotNull AnalysisContext context) {
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        List<VerdictItem> items = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;

        // Smart multi-dump temporal health analysis
        HealthEvolution healthEvolution = null;
        if (context.isMultiDump()) {
            healthEvolution = analyzeHealthEvolution(context);

            // Add findings for health trends
            if (healthEvolution.isDegrading()) {
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.WARNING, "degrading-health",
                        String.format("Application health is degrading: %.0f → %.0f (%.0f points)",
                                healthEvolution.firstScore(), healthEvolution.lastScore(),
                                healthEvolution.scoreChange()))
                        .detail("trend", healthEvolution.trend())
                        .build());

                if (worstSeverity.getLevel() < AnalysisResult.Severity.WARNING.getLevel()) {
                    worstSeverity = AnalysisResult.Severity.WARNING;
                }
            }

            if (healthEvolution.hasCriticalChange()) {
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.ERROR, "critical-health-change",
                        String.format("Critical health change detected at dump %d",
                                healthEvolution.criticalChangeDump()))
                        .build());

                if (worstSeverity.getLevel() < AnalysisResult.Severity.ERROR.getLevel()) {
                    worstSeverity = AnalysisResult.Severity.ERROR;
                }
            }
        }

        // Analyze thread states
        ThreadStateAnalysis stateAnalysis = analyzeThreadStates(context);
        items.add(new VerdictItem("Thread States", stateAnalysis.summary, stateAnalysis.severity, stateAnalysis.details));
        if (stateAnalysis.severity.isWorseThan(worstSeverity)) worstSeverity = stateAnalysis.severity;

        // Check for deadlocks
        DeadlockCheck deadlockCheck = checkForDeadlocks(context);
        items.add(new VerdictItem("Deadlocks", deadlockCheck.summary, deadlockCheck.severity, deadlockCheck.details));
        if (deadlockCheck.severity.isWorseThan(worstSeverity)) worstSeverity = deadlockCheck.severity;

        // Check GC activity
        GCCheck gcCheck = checkGCActivity(context);
        items.add(new VerdictItem("GC Activity", gcCheck.summary, gcCheck.severity, gcCheck.details));
        if (gcCheck.severity.isWorseThan(worstSeverity)) worstSeverity = gcCheck.severity;

        // Check I/O blocking
        IOCheck ioCheck = checkIOBlocking(context);
        items.add(new VerdictItem("I/O Status", ioCheck.summary, ioCheck.severity, ioCheck.details));
        if (ioCheck.severity.isWorseThan(worstSeverity)) worstSeverity = ioCheck.severity;

        // Check lock contention
        LockCheck lockCheck = checkLockContention(context);
        items.add(new VerdictItem("Lock Contention", lockCheck.summary, lockCheck.severity, lockCheck.details));
        if (lockCheck.severity.isWorseThan(worstSeverity)) worstSeverity = lockCheck.severity;

        // Check thread pools
        PoolCheck poolCheck = checkThreadPools(context);
        items.add(new VerdictItem("Thread Pools", poolCheck.summary, poolCheck.severity, poolCheck.details));
        if (poolCheck.severity.isWorseThan(worstSeverity)) worstSeverity = poolCheck.severity;

        // Check thread churn (multi-dump only)
        if (context.isMultiDump()) {
            ChurnCheck churnCheck = checkThreadChurn(context);
            items.add(new VerdictItem("Thread Churn", churnCheck.summary, churnCheck.severity, churnCheck.details));
            if (churnCheck.severity.isWorseThan(worstSeverity)) worstSeverity = churnCheck.severity;
        }

        // Determine overall verdict
        VerdictStatus status = determineOverallStatus(deadlockCheck, stateAnalysis, gcCheck, ioCheck, lockCheck, poolCheck);

        // Generate top findings
        findings.addAll(generateTopFindings(items));

        // Where is time spent?
        TimeDistribution timeDistribution = analyzeTimeDistribution(context);

        return new VerdictResult(worstSeverity, findings, status, items, timeDistribution,
                context.getDumpCount(), stateAnalysis.totalThreads, healthEvolution);
    }

    /**
     * Smart multi-dump health evolution analysis
     * Tracks health scores over time and detects trends
     */
    private HealthEvolution analyzeHealthEvolution(AnalysisContext context) {
        List<Double> healthScoresPerDump = new ArrayList<>();
        List<String> statusPerDump = new ArrayList<>();
        Map<String, List<Double>> categoryScoresPerDump = new LinkedHashMap<>();

        // Calculate health score for each dump
        for (int i = 0; i < context.getDumpCount(); i++) {
            ThreadDump dump = context.getDumps().get(i);

            // Create a mini-context for single dump analysis
            AnalysisContext miniContext = AnalysisContext.of(dump, context.getOptions());

            // Calculate health score (0-100, higher is better)
            double healthScore = calculateHealthScore(miniContext);
            healthScoresPerDump.add(healthScore);

            // Determine status
            String status;
            if (healthScore >= 90) status = "EXCELLENT";
            else if (healthScore >= 75) status = "GOOD";
            else if (healthScore >= 60) status = "FAIR";
            else if (healthScore >= 40) status = "POOR";
            else status = "CRITICAL";
            statusPerDump.add(status);

            // Track per-category scores
            Map<String, Double> categoryScores = calculateCategoryScores(miniContext);
            for (Map.Entry<String, Double> entry : categoryScores.entrySet()) {
                categoryScoresPerDump.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(entry.getValue());
            }
        }

        // Analyze trend
        double firstScore = healthScoresPerDump.getFirst();
        double lastScore = healthScoresPerDump.getLast();
        double scoreChange = lastScore - firstScore;
        double changePercent = firstScore > 0 ? (scoreChange / firstScore) * 100 : 0;

        String trend;
        boolean isDegrading = false;
        boolean isImproving = false;
        if (scoreChange < -10) {
            trend = "DEGRADING";
            isDegrading = true;
        } else if (scoreChange > 10) {
            trend = "IMPROVING";
            isImproving = true;
        } else {
            trend = "STABLE";
        }

        // Detect critical changes (score drop > 20 points)
        int criticalChangeDump = -1;
        boolean hasCriticalChange = false;
        for (int i = 1; i < healthScoresPerDump.size(); i++) {
            double drop = healthScoresPerDump.get(i) - healthScoresPerDump.get(i - 1);
            if (drop < -20) {
                criticalChangeDump = i;
                hasCriticalChange = true;
                break;
            }
        }

        // Identify problem categories (those getting worse)
        List<String> degradingCategories = new ArrayList<>();
        List<String> improvingCategories = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : categoryScoresPerDump.entrySet()) {
            List<Double> scores = entry.getValue();
            if (scores.size() >= 2) {
                double catChange = scores.getLast() - scores.getFirst();
                if (catChange < -10) {
                    degradingCategories.add(entry.getKey());
                } else if (catChange > 10) {
                    improvingCategories.add(entry.getKey());
                }
            }
        }

        return new HealthEvolution(
                healthScoresPerDump,
                statusPerDump,
                categoryScoresPerDump,
                firstScore,
                lastScore,
                scoreChange,
                changePercent,
                trend,
                isDegrading,
                isImproving,
                hasCriticalChange,
                criticalChangeDump,
                degradingCategories,
                improvingCategories
        );
    }

    /**
     * Calculate overall health score (0-100)
     */
    private double calculateHealthScore(AnalysisContext context) {
        double score = 100.0;

        ThreadDump dump = context.getFirstDump();
        int total = context.getFilteredThreads(dump).size();
        if (total == 0) return 50.0; // Unknown

        // Deductions based on thread states
        int blocked = 0, waiting = 0;
        for (ThreadInfo thread : context.getFilteredThreads(dump)) {
            if (thread.state() == Thread.State.BLOCKED) blocked++;
            if (thread.state() == Thread.State.WAITING || thread.state() == Thread.State.TIMED_WAITING) waiting++;
        }

        double blockedPercent = (blocked * 100.0) / total;
        double waitingPercent = (waiting * 100.0) / total;

        // Heavy deduction for blocked threads
        if (blockedPercent > 50) score -= 40;
        else if (blockedPercent > 30) score -= 25;
        else if (blockedPercent > 10) score -= 10;

        // Light deduction for waiting threads
        if (waitingPercent > 70) score -= 20;
        else if (waitingPercent > 50) score -= 10;

        // Check for deadlocks (critical)
        for (ThreadInfo thread : context.getFilteredThreads(dump)) {
            if (thread.waitingOnLock() != null) {
                score -= 5; // Penalty for each lock waiter
            }
        }

        // GC thread check
        int gcThreads = 0;
        for (ThreadInfo thread : dump.threads()) {
            String name = thread.name();
            if (name != null && (name.contains("GC") || name.contains("G1"))) {
                gcThreads++;
            }
        }
        if (gcThreads > total * 0.15) score -= 15;  // Too many GC threads

        return Math.max(0, Math.min(100, score));
    }

    /**
     * Calculate category-specific health scores
     */
    private Map<String, Double> calculateCategoryScores(AnalysisContext context) {
        Map<String, Double> scores = new LinkedHashMap<>();

        ThreadDump dump = context.getFirstDump();
        int total = context.getFilteredThreads(dump).size();
        if (total == 0) return scores;

        // Thread state score
        int blocked = 0;
        for (ThreadInfo thread : context.getFilteredThreads(dump)) {
            if (thread.state() == Thread.State.BLOCKED) blocked++;
        }
        double threadStateScore = 100 - ((blocked * 100.0) / total);
        scores.put("Thread States", Math.max(0, threadStateScore));

        // Lock contention score
        int waitingOnLocks = 0;
        for (ThreadInfo thread : context.getFilteredThreads(dump)) {
            if (thread.waitingOnLock() != null) waitingOnLocks++;
        }
        double lockScore = 100 - ((waitingOnLocks * 100.0) / total);
        scores.put("Lock Contention", Math.max(0, lockScore));

        // GC pressure score
        int gcThreads = 0;
        for (ThreadInfo thread : dump.threads()) {
            String name = thread.name();
            if (name != null && (name.contains("GC") || name.contains("G1"))) {
                gcThreads++;
            }
        }
        double gcScore = 100 - ((gcThreads * 100.0) / total * 5); // GC threads weighted x5
        scores.put("GC Activity", Math.max(0, Math.min(100, gcScore)));

        return scores;
    }

    private ThreadStateAnalysis analyzeThreadStates(AnalysisContext context) {
        int total = 0, runnable = 0, blocked = 0, waiting = 0, timedWaiting = 0;

        for (ThreadDump dump : context.getDumps()) {
            for (ThreadInfo thread : context.getFilteredThreads(dump)) {
                total++;
                if (thread.state() == null) continue;
                switch (thread.state()) {
                    case RUNNABLE -> runnable++;
                    case BLOCKED -> blocked++;
                    case WAITING -> waiting++;
                    case TIMED_WAITING -> timedWaiting++;
                    default -> {}
                }
            }
        }

        // Normalize for multi-dump
        int dumpCount = context.getDumpCount();
        total /= dumpCount;
        runnable /= dumpCount;
        blocked /= dumpCount;
        waiting /= dumpCount;
        timedWaiting /= dumpCount;

        String summary;
        AnalysisResult.Severity severity;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("total", total);
        details.put("runnable", runnable);
        details.put("blocked", blocked);
        details.put("waiting", waiting);
        details.put("timedWaiting", timedWaiting);

        double blockedPercent = total > 0 ? (blocked * 100.0) / total : 0;
        if (blockedPercent > 50) {
            summary = String.format("Critical: %.0f%% threads blocked", blockedPercent);
            severity = AnalysisResult.Severity.ERROR;
        } else if (blockedPercent > 20) {
            summary = String.format("Warning: %.0f%% threads blocked", blockedPercent);
            severity = AnalysisResult.Severity.WARNING;
        } else {
            summary = String.format("%d threads: %d runnable, %d blocked, %d waiting",
                    total, runnable, blocked, waiting + timedWaiting);
            severity = AnalysisResult.Severity.OK;
        }

        return new ThreadStateAnalysis(summary, severity, details, total);
    }

    private DeadlockCheck checkForDeadlocks(AnalysisContext context) {
        // Check for deadlock sections in dumps
        for (ThreadDump dump : context.getDumps()) {
            if (dump.deadlockInfos() != null && !dump.deadlockInfos().isEmpty()) {
                int count = dump.deadlockInfos().size();
                return new DeadlockCheck(
                        count + " deadlock(s) detected!",
                        AnalysisResult.Severity.CRITICAL,
                        Map.of("count", count, "threads", dump.deadlockInfos().stream()
                                .flatMap(d -> d.threads().stream().map(t -> t.threadName())).toList())
                );
            }
        }
        return new DeadlockCheck("No deadlocks", AnalysisResult.Severity.OK, Map.of());
    }

    private GCCheck checkGCActivity(AnalysisContext context) {
        int gcThreads = 0, totalThreads = 0;
        for (ThreadDump dump : context.getDumps()) {
            for (ThreadInfo thread : dump.threads()) {
                totalThreads++;
                String name = thread.name();
                if (name != null && (name.contains("GC") || name.contains("G1") ||
                        name.contains("ZGC") || name.contains("Shenandoah"))) {
                    gcThreads++;
                }
            }
        }

        int dumpCount = context.getDumpCount();
        gcThreads /= dumpCount;
        totalThreads /= dumpCount;

        double gcPercent = totalThreads > 0 ? (gcThreads * 100.0) / totalThreads : 0;
        if (gcPercent > 30) {
            return new GCCheck("High GC thread activity", AnalysisResult.Severity.WARNING,
                    Map.of("gcThreads", gcThreads, "percent", gcPercent));
        }
        return new GCCheck(gcThreads + " GC threads active", AnalysisResult.Severity.OK,
                Map.of("gcThreads", gcThreads));
    }

    private IOCheck checkIOBlocking(AnalysisContext context) {
        int ioBlocked = 0;
        for (ThreadDump dump : context.getDumps()) {
            for (ThreadInfo thread : context.getFilteredThreads(dump)) {
                if (isIOBlocked(thread)) {
                    ioBlocked++;
                }
            }
        }

        int avg = ioBlocked / context.getDumpCount();
        if (avg > 10) {
            return new IOCheck(avg + " threads blocked on I/O", AnalysisResult.Severity.WARNING,
                    Map.of("blocked", avg));
        } else if (avg > 0) {
            return new IOCheck(avg + " threads in I/O", AnalysisResult.Severity.INFO,
                    Map.of("blocked", avg));
        }
        return new IOCheck("No I/O blocking", AnalysisResult.Severity.OK, Map.of());
    }

    private boolean isIOBlocked(ThreadInfo thread) {
        if (thread.stackTrace() == null || thread.stackTrace().isEmpty()) return false;
        for (StackFrame frame : thread.stackTrace()) {
            String className = frame.className();
            if (className == null) continue;
            if (className.contains("SocketInputStream") || className.contains("FileInputStream") ||
                    className.contains("SocketOutputStream") || className.contains("FileOutputStream") ||
                    className.contains("Selector") || className.contains("ServerSocket")) {
                return true;
            }
        }
        return false;
    }

    private LockCheck checkLockContention(AnalysisContext context) {
        Map<String, List<ThreadInfo>> lockWaiters = new HashMap<>();
        for (ThreadDump dump : context.getDumps()) {
            for (ThreadInfo thread : context.getFilteredThreads(dump)) {
                String lock = thread.waitingOnLock();
                if (lock != null) {
                    lockWaiters.computeIfAbsent(lock, k -> new ArrayList<>()).add(thread);
                }
            }
        }

        int hotLocks = (int) lockWaiters.values().stream().filter(l -> l.size() >= 3).count();
        int maxWaiters = lockWaiters.values().stream().mapToInt(List::size).max().orElse(0);

        if (hotLocks > 0) {
            return new LockCheck(hotLocks + " hot lock(s), max " + maxWaiters + " waiters",
                    maxWaiters >= 5 ? AnalysisResult.Severity.WARNING : AnalysisResult.Severity.INFO,
                    Map.of("hotLocks", hotLocks, "maxWaiters", maxWaiters));
        }
        return new LockCheck("No significant contention", AnalysisResult.Severity.OK, Map.of());
    }

    private PoolCheck checkThreadPools(AnalysisContext context) {
        Map<String, Integer> poolCounts = new HashMap<>();
        for (ThreadDump dump : context.getDumps()) {
            for (ThreadInfo thread : dump.threads()) {
                String name = thread.name();
                if (name != null && name.matches("pool-\\d+-thread-\\d+")) {
                    String pool = name.replaceAll("-thread-\\d+$", "");
                    poolCounts.merge(pool, 1, Integer::sum);
                }
            }
        }

        int pools = poolCounts.size();
        int maxSize = poolCounts.values().stream().mapToInt(i -> i).max().orElse(0);

        if (maxSize > 50) {
            return new PoolCheck(pools + " pools, largest has " + maxSize + " threads",
                    AnalysisResult.Severity.INFO, Map.of("pools", pools, "maxSize", maxSize));
        }
        return new PoolCheck(pools + " thread pool(s)", AnalysisResult.Severity.OK,
                Map.of("pools", pools));
    }

    private ChurnCheck checkThreadChurn(AnalysisContext context) {
        if (!context.isMultiDump()) {
            return new ChurnCheck("Single dump", AnalysisResult.Severity.OK, Map.of());
        }

        int first = context.getFirstDump().threads().size();
        int last = context.getLastDump().threads().size();
        int change = last - first;

        if (Math.abs(change) > first * 0.2) {
            return new ChurnCheck("Thread count: " + first + " → " + last,
                    change > 0 ? AnalysisResult.Severity.WARNING : AnalysisResult.Severity.INFO,
                    Map.of("first", first, "last", last, "change", change));
        }
        return new ChurnCheck("Thread count stable: " + first + " → " + last,
                AnalysisResult.Severity.OK, Map.of("first", first, "last", last));
    }

    private TimeDistribution analyzeTimeDistribution(AnalysisContext context) {
        int running = 0, blocked = 0, waiting = 0, io = 0, gc = 0;

        for (ThreadDump dump : context.getDumps()) {
            for (ThreadInfo thread : context.getFilteredThreads(dump)) {
                String name = thread.name();
                if (name != null && (name.contains("GC") || name.contains("G1"))) {
                    gc++;
                } else if (isIOBlocked(thread)) {
                    io++;
                } else if (thread.state() == Thread.State.BLOCKED) {
                    blocked++;
                } else if (thread.state() == Thread.State.WAITING || thread.state() == Thread.State.TIMED_WAITING) {
                    waiting++;
                } else if (thread.state() == Thread.State.RUNNABLE) {
                    running++;
                }
            }
        }

        int total = running + blocked + waiting + io + gc;
        return new TimeDistribution(
                total > 0 ? running * 100.0 / total : 0,
                total > 0 ? blocked * 100.0 / total : 0,
                total > 0 ? waiting * 100.0 / total : 0,
                total > 0 ? io * 100.0 / total : 0,
                total > 0 ? gc * 100.0 / total : 0
        );
    }

    private VerdictStatus determineOverallStatus(DeadlockCheck deadlock, ThreadStateAnalysis state,
                                                  GCCheck gc, IOCheck io, LockCheck lock, PoolCheck pool) {
        if (deadlock.severity == AnalysisResult.Severity.CRITICAL) return VerdictStatus.DEADLOCK;
        if (state.severity == AnalysisResult.Severity.ERROR) return VerdictStatus.SUSPECTED_STALL;
        if (gc.severity.isWorseThan(AnalysisResult.Severity.INFO)) return VerdictStatus.GC_PRESSURE;
        if (io.severity.isWorseThan(AnalysisResult.Severity.INFO)) return VerdictStatus.IO_BOUND;
        if (lock.severity.isWorseThan(AnalysisResult.Severity.INFO)) return VerdictStatus.HIGH_CONTENTION;
        return VerdictStatus.HEALTHY;
    }

    private List<AnalysisResult.Finding> generateTopFindings(List<VerdictItem> items) {
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        for (VerdictItem item : items) {
            if (item.severity.isWorseThan(AnalysisResult.Severity.OK)) {
                findings.add(AnalysisResult.Finding.builder(item.severity, "verdict-" + item.category.toLowerCase().replace(" ", "-"),
                        item.category + ": " + item.summary).build());
            }
        }
        return findings;
    }

    // Inner record types
    private record ThreadStateAnalysis(String summary, AnalysisResult.Severity severity,
                                        Map<String, Object> details, int totalThreads) {}
    private record DeadlockCheck(String summary, AnalysisResult.Severity severity, Map<String, Object> details) {}
    private record GCCheck(String summary, AnalysisResult.Severity severity, Map<String, Object> details) {}
    private record IOCheck(String summary, AnalysisResult.Severity severity, Map<String, Object> details) {}
    private record LockCheck(String summary, AnalysisResult.Severity severity, Map<String, Object> details) {}
    private record PoolCheck(String summary, AnalysisResult.Severity severity, Map<String, Object> details) {}
    private record ChurnCheck(String summary, AnalysisResult.Severity severity, Map<String, Object> details) {}

    // Public records for result
    public record VerdictItem(String category, String summary, AnalysisResult.Severity severity,
                              Map<String, Object> details) {}

    public record TimeDistribution(double running, double blocked, double waiting, double io, double gc) {}

    /**
     * Smart health evolution tracking across multiple dumps
     */
    public record HealthEvolution(
            List<Double> healthScoresPerDump,
            List<String> statusPerDump,
            Map<String, List<Double>> categoryScoresPerDump,
            double firstScore,
            double lastScore,
            double scoreChange,
            double changePercent,
            String trend,               // DEGRADING, IMPROVING, STABLE
            boolean isDegrading,
            boolean isImproving,
            boolean hasCriticalChange,  // Score dropped > 20 points
            int criticalChangeDump,     // Which dump had critical change
            List<String> degradingCategories,
            List<String> improvingCategories
    ) {
        public HealthEvolution {
            healthScoresPerDump = healthScoresPerDump != null ? List.copyOf(healthScoresPerDump) : List.of();
            statusPerDump = statusPerDump != null ? List.copyOf(statusPerDump) : List.of();
            categoryScoresPerDump = categoryScoresPerDump != null ? Map.copyOf(categoryScoresPerDump) : Map.of();
            degradingCategories = degradingCategories != null ? List.copyOf(degradingCategories) : List.of();
            improvingCategories = improvingCategories != null ? List.copyOf(improvingCategories) : List.of();
        }

        public String getHealthStatus() {
            if (lastScore >= 90) return "EXCELLENT";
            if (lastScore >= 75) return "GOOD";
            if (lastScore >= 60) return "FAIR";
            if (lastScore >= 40) return "POOR";
            return "CRITICAL";
        }
    }

    public static class VerdictResult extends AnalysisResult {
        private final VerdictStatus status;
        private final List<VerdictItem> items;
        private final TimeDistribution timeDistribution;
        private final int dumpCount;
        private final int totalThreads;
        private final HealthEvolution healthEvolution;

        public VerdictResult(Severity severity, List<Finding> findings, VerdictStatus status,
                            List<VerdictItem> items, TimeDistribution timeDistribution,
                            int dumpCount, int totalThreads) {
            this(severity, findings, status, items, timeDistribution, dumpCount, totalThreads, null);
        }

        public VerdictResult(Severity severity, List<Finding> findings, VerdictStatus status,
                            List<VerdictItem> items, TimeDistribution timeDistribution,
                            int dumpCount, int totalThreads, HealthEvolution healthEvolution) {
            super(NAME, severity, findings);
            this.status = status;
            this.items = List.copyOf(items);
            this.timeDistribution = timeDistribution;
            this.dumpCount = dumpCount;
            this.totalThreads = totalThreads;
            this.healthEvolution = healthEvolution;
        }

        public VerdictStatus getStatus() { return status; }
        public List<VerdictItem> getItems() { return items; }
        public TimeDistribution getTimeDistribution() { return timeDistribution; }
        public int getDumpCount() { return dumpCount; }
        public int getTotalThreads() { return totalThreads; }
        public HealthEvolution getHealthEvolution() { return healthEvolution; }
        public boolean hasHealthEvolution() { return healthEvolution != null; }

        @Override
        public String getSummary() {
            return "VERDICT: " + status.name() + " - " + status.getDescription();
        }
    }
}