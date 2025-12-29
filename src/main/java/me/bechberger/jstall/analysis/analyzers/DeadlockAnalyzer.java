package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.model.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Analyzer that detects and reports deadlocks.
 * Uses JVM-reported deadlocks and attempts to find additional cycles.
 */
public class DeadlockAnalyzer implements Analyzer<DeadlockAnalyzer.DeadlockResult> {

    private static final String NAME = "DeadlockAnalyzer";

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Detects deadlocks using JVM reports and lock graph analysis";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        return true;
    }

    @Override
    public int getPriority() {
        return 200; // Highest priority - deadlocks are critical
    }

    @Override
    public @NotNull DeadlockResult analyze(@NotNull AnalysisContext context) {
        List<DetectedDeadlock> allDeadlocks = new ArrayList<>();
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;

        for (int dumpIndex = 0; dumpIndex < context.getDumpCount(); dumpIndex++) {
            ThreadDump dump = context.getDumps().get(dumpIndex);

            // First, use JVM-reported deadlocks
            if (dump.deadlockInfos() != null && !dump.deadlockInfos().isEmpty()) {
                for (DeadlockInfo info : dump.deadlockInfos()) {
                    DetectedDeadlock deadlock = fromDeadlockInfo(info, dumpIndex, dump.timestamp());
                    allDeadlocks.add(deadlock);

                    worstSeverity = AnalysisResult.Severity.CRITICAL;

                    List<String> threadNames = info.threads().stream()
                            .map(DeadlockInfo.DeadlockedThread::threadName)
                            .toList();

                    findings.add(AnalysisResult.Finding.builder(
                            AnalysisResult.Severity.CRITICAL, "deadlock",
                            String.format("Deadlock detected involving %d threads: %s",
                                    info.threads().size(), String.join(", ", threadNames)))
                            .detail("dumpIndex", dumpIndex)
                            .detail("threadCount", info.threads().size())
                            .detail("threadNames", threadNames)
                            .build());
                }
            }

            // Additionally, try to detect potential deadlock cycles from lock graph
            List<ThreadInfo> threads = context.getFilteredThreads(dump);
            List<LockCycle> cycles = detectLockCycles(threads);

            for (LockCycle cycle : cycles) {
                // Check if this cycle is already reported
                if (!isAlreadyReported(cycle, allDeadlocks)) {
                    DetectedDeadlock deadlock = new DetectedDeadlock(
                            cycle.threads(),
                            cycle.locks(),
                            dumpIndex,
                            dump.timestamp(),
                            false // Not JVM-reported
                    );
                    allDeadlocks.add(deadlock);

                    if (worstSeverity.getLevel() < AnalysisResult.Severity.ERROR.getLevel()) {
                        worstSeverity = AnalysisResult.Severity.ERROR;
                    }

                    List<String> threadNames = cycle.threads().stream()
                            .map(ThreadInfo::name)
                            .filter(Objects::nonNull)
                            .toList();

                    findings.add(AnalysisResult.Finding.builder(
                            AnalysisResult.Severity.ERROR, "potential-deadlock",
                            String.format("Potential lock cycle detected: %s",
                                    String.join(" → ", threadNames)))
                            .affectedThreads(cycle.threads())
                            .detail("dumpIndex", dumpIndex)
                            .detail("cycleSize", cycle.threads().size())
                            .build());
                }
            }
        }

        // Check for persistent deadlocks across dumps
        if (context.isMultiDump() && !allDeadlocks.isEmpty()) {
            Map<Set<String>, Integer> deadlockOccurrences = new HashMap<>();
            for (DetectedDeadlock deadlock : allDeadlocks) {
                Set<String> threadSet = new HashSet<>();
                for (ThreadInfo t : deadlock.threads()) {
                    if (t.name() != null) threadSet.add(t.name());
                }
                deadlockOccurrences.merge(threadSet, 1, Integer::sum);
            }

            for (Map.Entry<Set<String>, Integer> entry : deadlockOccurrences.entrySet()) {
                if (entry.getValue() >= 2) {
                    findings.add(AnalysisResult.Finding.builder(
                            AnalysisResult.Severity.CRITICAL, "persistent-deadlock",
                            String.format("Deadlock persisted across %d dumps: %s",
                                    entry.getValue(), entry.getKey()))
                            .detail("occurrences", entry.getValue())
                            .detail("threads", entry.getKey())
                            .build());
                }
            }
        }

        DeadlockSummary summary = new DeadlockSummary(
                allDeadlocks.size(),
                (int) allDeadlocks.stream().filter(DetectedDeadlock::jvmReported).count(),
                allDeadlocks.stream().mapToInt(d -> d.threads().size()).max().orElse(0)
        );

        // Analyze deadlock persistence across dumps
        DeadlockPersistenceAnalysis persistence = analyzeDeadlockPersistence(allDeadlocks, context.getDumpCount());

        return new DeadlockResult(worstSeverity, findings, allDeadlocks, persistence);
    }

    /**
     * Analyze if deadlocks persist across multiple dumps
     */
    private DeadlockPersistenceAnalysis analyzeDeadlockPersistence(List<DetectedDeadlock> allDeadlocks, int dumpCount) {
        if (dumpCount <= 1 || allDeadlocks.isEmpty()) {
            return new DeadlockPersistenceAnalysis(List.of(), Map.of());
        }

        // Group deadlocks by signature (set of involved thread names)
        Map<String, List<DetectedDeadlock>> deadlockGroups = new LinkedHashMap<>();

        for (DetectedDeadlock deadlock : allDeadlocks) {
            String signature = computeDeadlockSignature(deadlock);
            deadlockGroups.computeIfAbsent(signature, k -> new ArrayList<>()).add(deadlock);
        }

        // Analyze persistence
        List<DeadlockPersistence> persistentDeadlocks = new ArrayList<>();
        Map<Integer, List<DetectedDeadlock>> deadlocksByDump = new TreeMap<>();

        for (Map.Entry<String, List<DetectedDeadlock>> entry : deadlockGroups.entrySet()) {
            List<DetectedDeadlock> group = entry.getValue();
            if (group.size() > 1 || (group.size() == 1 && dumpCount > 1)) {
                // Track which dumps contain this deadlock
                Set<Integer> dumpIndices = new TreeSet<>();
                for (DetectedDeadlock dl : group) {
                    dumpIndices.add(dl.dumpIndex());
                }

                int firstSeen = dumpIndices.stream().min(Integer::compareTo).orElse(0);
                int lastSeen = dumpIndices.stream().max(Integer::compareTo).orElse(0);
                boolean isPersistent = group.size() > 1;

                persistentDeadlocks.add(new DeadlockPersistence(
                        entry.getKey(),
                        firstSeen,
                        lastSeen,
                        group.size(),
                        isPersistent,
                        group.getFirst()
                ));
            }
        }

        // Organize by dump
        for (DetectedDeadlock deadlock : allDeadlocks) {
            deadlocksByDump.computeIfAbsent(deadlock.dumpIndex(), k -> new ArrayList<>()).add(deadlock);
        }

        return new DeadlockPersistenceAnalysis(persistentDeadlocks, deadlocksByDump);
    }

    private String computeDeadlockSignature(DetectedDeadlock deadlock) {
        // Create a signature based on thread names (sorted for consistency)
        List<String> threadNames = deadlock.threads().stream()
                .map(ThreadInfo::name)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        return String.join("|", threadNames);
    }

    private DetectedDeadlock fromDeadlockInfo(DeadlockInfo info, int dumpIndex, java.time.Instant timestamp) {
        List<ThreadInfo> threads = new ArrayList<>();
        List<String> locks = new ArrayList<>();

        for (DeadlockInfo.DeadlockedThread dt : info.threads()) {
            // Create a simplified ThreadInfo
            threads.add(new ThreadInfo(
                    dt.threadName(),
                    null, null, null, null,
                    Thread.State.BLOCKED,
                    null, null,
                    dt.stackTrace(),
                    dt.locks(),
                    dt.waitingForObject(),
                    null
            ));

            if (dt.waitingForObject() != null) {
                locks.add(dt.waitingForObject());
            }
        }

        return new DetectedDeadlock(threads, locks, dumpIndex, timestamp, true);
    }

    private List<LockCycle> detectLockCycles(List<ThreadInfo> threads) {
        List<LockCycle> cycles = new ArrayList<>();

        // Build lock graph: thread -> waiting for lock, lock -> held by thread
        Map<String, ThreadInfo> lockHeldBy = new HashMap<>();
        Map<String, String> threadWaitingFor = new HashMap<>();

        for (ThreadInfo thread : threads) {
            if (thread.state() != Thread.State.BLOCKED) continue;

            String waitingFor = thread.waitingOnLock();
            if (waitingFor == null && thread.locks() != null) {
                for (LockInfo lock : thread.locks()) {
                    if ("waiting to lock".equals(lock.lockType())) {
                        waitingFor = lock.lockId();
                        break;
                    }
                }
            }

            if (waitingFor != null && thread.name() != null) {
                threadWaitingFor.put(thread.name(), waitingFor);
            }

            // Record held locks
            if (thread.locks() != null) {
                for (LockInfo lock : thread.locks()) {
                    if ("locked".equals(lock.lockType())) {
                        lockHeldBy.put(lock.lockId(), thread);
                    }
                }
            }
        }

        // Find cycles using DFS
        for (ThreadInfo startThread : threads) {
            if (startThread.state() != Thread.State.BLOCKED) continue;
            if (startThread.name() == null) continue;

            List<ThreadInfo> path = new ArrayList<>();
            Set<String> visited = new HashSet<>();

            if (findCycle(startThread, threadWaitingFor, lockHeldBy, path, visited, threads)) {
                cycles.add(new LockCycle(new ArrayList<>(path), List.of()));
            }
        }

        return cycles;
    }

    private boolean findCycle(ThreadInfo current, Map<String, String> threadWaitingFor,
                              Map<String, ThreadInfo> lockHeldBy, List<ThreadInfo> path,
                              Set<String> visited, List<ThreadInfo> allThreads) {
        if (current == null || current.name() == null) return false;

        if (visited.contains(current.name())) {
            // Found a cycle
            int cycleStart = -1;
            for (int i = 0; i < path.size(); i++) {
                if (path.get(i).name().equals(current.name())) {
                    cycleStart = i;
                    break;
                }
            }
            if (cycleStart >= 0) {
                // Keep only the cycle portion
                path.subList(0, cycleStart).clear();
                return true;
            }
            return false;
        }

        visited.add(current.name());
        path.add(current);

        String waitingFor = threadWaitingFor.get(current.name());
        if (waitingFor != null) {
            ThreadInfo holder = lockHeldBy.get(waitingFor);
            if (holder != null && findCycle(holder, threadWaitingFor, lockHeldBy, path, visited, allThreads)) {
                return true;
            }
        }

        path.removeLast();
        return false;
    }

    private boolean isAlreadyReported(LockCycle cycle, List<DetectedDeadlock> existing) {
        Set<String> cycleThreads = new HashSet<>();
        for (ThreadInfo t : cycle.threads()) {
            if (t.name() != null) cycleThreads.add(t.name());
        }

        for (DetectedDeadlock deadlock : existing) {
            Set<String> dlThreads = new HashSet<>();
            for (ThreadInfo t : deadlock.threads()) {
                if (t.name() != null) dlThreads.add(t.name());
            }
            if (cycleThreads.equals(dlThreads)) {
                return true;
            }
        }
        return false;
    }

    private record LockCycle(List<ThreadInfo> threads, List<String> locks) {}

    /**
     * A detected deadlock
     */
    public record DetectedDeadlock(
            List<ThreadInfo> threads,
            List<String> locks,
            int dumpIndex,
            java.time.Instant timestamp,
            boolean jvmReported
    ) {
        public DetectedDeadlock {
            threads = threads != null ? List.copyOf(threads) : List.of();
            locks = locks != null ? List.copyOf(locks) : List.of();
        }

        public int size() {
            return threads.size();
        }

        public List<String> getThreadNames() {
            return threads.stream()
                    .map(ThreadInfo::name)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    /**
     * Tracks deadlock persistence across dumps
     */
    public record DeadlockPersistence(
            String signature,           // Hash of involved threads
            int firstSeenDumpIndex,
            int lastSeenDumpIndex,
            int occurrenceCount,
            boolean isPersistent,       // Appears in multiple dumps
            DetectedDeadlock representative  // Sample deadlock from this group
    ) {
        public boolean isOngoing() {
            return lastSeenDumpIndex > firstSeenDumpIndex;
        }

        public int dumpSpan() {
            return lastSeenDumpIndex - firstSeenDumpIndex + 1;
        }
    }

    /**
     * Analysis of deadlock persistence across multiple dumps
     */
    public record DeadlockPersistenceAnalysis(
            List<DeadlockPersistence> persistentDeadlocks,
            Map<Integer, List<DetectedDeadlock>> deadlocksByDump
    ) {
        public DeadlockPersistenceAnalysis {
            persistentDeadlocks = persistentDeadlocks != null ? List.copyOf(persistentDeadlocks) : List.of();
            deadlocksByDump = deadlocksByDump != null ? Map.copyOf(deadlocksByDump) : Map.of();
        }

        public boolean hasPersistentDeadlocks() {
            return persistentDeadlocks.stream().anyMatch(DeadlockPersistence::isPersistent);
        }

        public int getMaxOccurrences() {
            return persistentDeadlocks.stream()
                    .mapToInt(DeadlockPersistence::occurrenceCount)
                    .max()
                    .orElse(0);
        }
    }

    /**
     * Summary of deadlock analysis
     */
    public record DeadlockSummary(
            int totalDeadlocks,
            int jvmReportedCount,
            int maxCycleSize
    ) {
        public boolean hasDeadlocks() {
            return totalDeadlocks > 0;
        }
    }

    /**
     * Result of deadlock analysis
     */
    public static class DeadlockResult extends AnalysisResult {
        private final List<DetectedDeadlock> deadlocks;
        private final DeadlockPersistenceAnalysis persistence;
        private final DeadlockSummary summary;

        public DeadlockResult(Severity severity, List<Finding> findings,
                             List<DetectedDeadlock> deadlocks) {
            this(severity, findings, deadlocks, null);
        }

        public DeadlockResult(Severity severity, List<Finding> findings,
                             List<DetectedDeadlock> deadlocks,
                             DeadlockPersistenceAnalysis persistence) {
            super(NAME, severity, findings);
            this.deadlocks = List.copyOf(deadlocks);
            this.persistence = persistence;

            int jvmReported = (int) deadlocks.stream().filter(DetectedDeadlock::jvmReported).count();
            int maxSize = deadlocks.stream().mapToInt(DetectedDeadlock::size).max().orElse(0);
            this.summary = new DeadlockSummary(deadlocks.size(), jvmReported, maxSize);
        }

        public List<DetectedDeadlock> getDeadlocks() {
            return deadlocks;
        }

        public DeadlockPersistenceAnalysis getPersistence() {
            return persistence;
        }

        public DeadlockSummary getDeadlockSummary() {
            return summary;
        }

        public boolean hasDeadlocks() {
            return !deadlocks.isEmpty();
        }

        public boolean hasPersistentDeadlocks() {
            return persistence != null && persistence.hasPersistentDeadlocks();
        }

        @Override
        public String getSummary() {
            if (deadlocks.isEmpty()) {
                return "No deadlocks detected";
            }
            return String.format("DEADLOCK: %d deadlock(s) detected, max cycle size %d",
                    summary.totalDeadlocks(), summary.maxCycleSize());
        }
    }
}