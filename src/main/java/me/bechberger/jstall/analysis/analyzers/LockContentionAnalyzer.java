package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.jfr.JfrAnalyzer;
import me.bechberger.jstall.jfr.JfrParser;
import me.bechberger.jstall.model.*;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzer that detects lock contention issues.
 * Works with both thread dump data and JFR lock events.
 * Identifies hot locks (many waiters) and long-held locks.
 */
public class LockContentionAnalyzer implements Analyzer<LockContentionAnalyzer.LockContentionResult> {

    private static final String NAME = "LockContentionAnalyzer";

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Detects lock contention issues including hot locks and long-held locks";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        return true;
    }

    @Override
    public int getPriority() {
        return 80; // High priority
    }

    @Override
    public @NotNull LockContentionResult analyze(@NotNull AnalysisContext context) {
        List<LockContention> allContentions = new ArrayList<>();
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;

        for (int dumpIndex = 0; dumpIndex < context.getDumpCount(); dumpIndex++) {
            ThreadDump dump = context.getDumps().get(dumpIndex);
            List<ThreadInfo> threads = context.getFilteredThreads(dump);

            // Build lock ownership map: lockId -> owner thread
            Map<String, ThreadInfo> lockOwners = new HashMap<>();
            // Build lock waiters map: lockId -> list of waiting threads
            Map<String, List<ThreadInfo>> lockWaiters = new HashMap<>();

            for (ThreadInfo thread : threads) {
                // Find owned locks
                if (thread.locks() != null) {
                    for (LockInfo lock : thread.locks()) {
                        if ("locked".equals(lock.lockType())) {
                            lockOwners.put(lock.lockId(), thread);
                        }
                    }
                }

                // Find waiting threads
                if (thread.waitingOnLock() != null) {
                    lockWaiters.computeIfAbsent(thread.waitingOnLock(), k -> new ArrayList<>()).add(thread);
                }

                // Also check locks for waiting patterns
                if (thread.locks() != null) {
                    for (LockInfo lock : thread.locks()) {
                        if ("waiting to lock".equals(lock.lockType()) ||
                            "waiting on".equals(lock.lockType()) ||
                            "parking to wait for".equals(lock.lockType())) {
                            lockWaiters.computeIfAbsent(lock.lockId(), k -> new ArrayList<>()).add(thread);
                        }
                    }
                }
            }

            // Analyze each lock
            Set<String> allLockIds = new HashSet<>();
            allLockIds.addAll(lockOwners.keySet());
            allLockIds.addAll(lockWaiters.keySet());

            for (String lockId : allLockIds) {
                ThreadInfo owner = lockOwners.get(lockId);
                List<ThreadInfo> waiters = lockWaiters.getOrDefault(lockId, List.of());

                if (waiters.isEmpty()) {
                    continue; // No contention
                }

                // Determine lock class name
                String lockClassName = determineLockClassName(lockId, owner, waiters);

                LockContention contention = new LockContention(
                        lockId,
                        lockClassName,
                        owner,
                        waiters,
                        dumpIndex,
                        dump.timestamp()
                );

                allContentions.add(contention);
            }
        }

        // Sort by waiter count (most contested first)
        allContentions.sort((a, b) -> Integer.compare(b.waiterCount(), a.waiterCount()));

        // Generate findings for hot locks
        for (LockContention contention : allContentions) {
            AnalysisResult.Severity severity = determineSeverity(contention);
            if (severity.isWorseThan(worstSeverity)) {
                worstSeverity = severity;
            }

            if (contention.waiterCount() >= 2) {
                String ownerName = contention.owner() != null ? contention.owner().name() : "unknown";
                findings.add(AnalysisResult.Finding.builder(severity, "lock-contention",
                        String.format("Lock %s held by %s has %d waiting threads",
                                formatLockId(contention.lockId()), ownerName, contention.waiterCount()))
                        .affectedThreads(contention.waiters())
                        .detail("lockId", contention.lockId())
                        .detail("lockClass", contention.lockClassName())
                        .detail("owner", ownerName)
                        .detail("waiterCount", contention.waiterCount())
                        .detail("dumpIndex", contention.dumpIndex())
                        .build());
            }
        }

        // Detect long-held locks across multiple dumps
        if (context.isMultiDump()) {
            Map<String, List<LockContention>> contentionsByLock = allContentions.stream()
                    .collect(Collectors.groupingBy(LockContention::lockId));

            for (Map.Entry<String, List<LockContention>> entry : contentionsByLock.entrySet()) {
                List<LockContention> lockContentions = entry.getValue();
                if (lockContentions.size() >= 2) {
                    // Lock appears in multiple dumps - potentially long-held
                    Set<String> owners = lockContentions.stream()
                            .filter(c -> c.owner() != null)
                            .map(c -> c.owner().name())
                            .collect(Collectors.toSet());

                    if (owners.size() == 1) {
                        // Same owner across dumps - long-held lock
                        String ownerName = owners.iterator().next();
                        int totalWaiters = lockContentions.stream()
                                .mapToInt(LockContention::waiterCount)
                                .sum();

                        findings.add(AnalysisResult.Finding.builder(
                                AnalysisResult.Severity.WARNING, "long-held-lock",
                                String.format("Lock %s held by %s across %d dumps (%d total waiters)",
                                        formatLockId(entry.getKey()), ownerName,
                                        lockContentions.size(), totalWaiters))
                                .detail("lockId", entry.getKey())
                                .detail("owner", ownerName)
                                .detail("dumpCount", lockContentions.size())
                                .detail("totalWaiters", totalWaiters)
                                .build());

                        if (worstSeverity.getLevel() < AnalysisResult.Severity.WARNING.getLevel()) {
                            worstSeverity = AnalysisResult.Severity.WARNING;
                        }
                    }
                }
            }
        }

        // Create summary
        int totalContentedLocks = (int) allContentions.stream()
                .map(LockContention::lockId)
                .distinct()
                .count();
        int maxWaiters = allContentions.stream()
                .mapToInt(LockContention::waiterCount)
                .max()
                .orElse(0);
        int hotLocks = (int) allContentions.stream()
                .filter(c -> c.waiterCount() >= 5)
                .map(LockContention::lockId)
                .distinct()
                .count();

        LockContentionSummary summary = new LockContentionSummary(
                totalContentedLocks, maxWaiters, hotLocks);

        // Analyze JFR lock events if available
        JfrLockAnalysis jfrAnalysis = null;
        if (context.hasJfr()) {
            jfrAnalysis = analyzeJfrLocks(context, findings);
            if (jfrAnalysis != null) {
                for (AnalysisResult.Finding finding : jfrAnalysis.additionalFindings()) {
                    if (finding.severity().isWorseThan(worstSeverity)) {
                        worstSeverity = finding.severity();
                    }
                }
            }
        }

        return new LockContentionResult(worstSeverity, findings, allContentions, summary, jfrAnalysis);
    }

    /**
     * Analyze JFR lock contention events
     */
    private JfrLockAnalysis analyzeJfrLocks(AnalysisContext context, List<AnalysisResult.Finding> findings) {
        JfrParser.JfrData jfrData = context.getJfrDataForDumpRange();
        if (jfrData == null || jfrData.lockEvents().isEmpty()) {
            return null;
        }

        JfrAnalyzer analyzer = new JfrAnalyzer();
        JfrAnalyzer.LockContentionSummary lockSummary = analyzer.getLockContentionSummary(jfrData);

        List<AnalysisResult.Finding> additionalFindings = new ArrayList<>();

        // Report significant lock contention from JFR
        if (lockSummary.totalBlockedTime().toMillis() > 1000) {
            AnalysisResult.Severity severity = lockSummary.totalBlockedTime().toMillis() > 5000
                    ? AnalysisResult.Severity.WARNING
                    : AnalysisResult.Severity.INFO;

            additionalFindings.add(AnalysisResult.Finding.builder(severity, "jfr-lock-contention",
                    String.format("JFR detected %d lock events with %dms total blocked time",
                            lockSummary.totalEvents(), lockSummary.totalBlockedTime().toMillis()))
                    .detail("totalEvents", lockSummary.totalEvents())
                    .detail("uniqueLocks", lockSummary.uniqueLocks())
                    .detail("totalBlockedMs", lockSummary.totalBlockedTime().toMillis())
                    .build());
            findings.add(additionalFindings.getLast());
        }

        // Report hot locks from JFR
        for (JfrAnalyzer.LockProfile hotLock : lockSummary.hotLocks()) {
            if (hotLock.totalDuration().toMillis() > 500) {
                additionalFindings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.INFO, "jfr-hot-lock",
                        String.format("Hot lock: %s (%d events, %dms blocked, max %dms)",
                                hotLock.monitorClass(), hotLock.eventCount(),
                                hotLock.totalDuration().toMillis(), hotLock.maxDuration().toMillis()))
                        .detail("monitorClass", hotLock.monitorClass())
                        .detail("eventCount", hotLock.eventCount())
                        .detail("totalDurationMs", hotLock.totalDuration().toMillis())
                        .detail("maxDurationMs", hotLock.maxDuration().toMillis())
                        .build());
                findings.add(additionalFindings.getLast());
            }
        }

        return new JfrLockAnalysis(lockSummary, additionalFindings);
    }

    private String determineLockClassName(String lockId, ThreadInfo owner, List<ThreadInfo> waiters) {
        // Try to find class name from owner's locks
        if (owner != null && owner.locks() != null) {
            for (LockInfo lock : owner.locks()) {
                if (lockId.equals(lock.lockId()) && lock.className() != null) {
                    return lock.className();
                }
            }
        }
        // Try from waiters
        for (ThreadInfo waiter : waiters) {
            if (waiter.locks() != null) {
                for (LockInfo lock : waiter.locks()) {
                    if (lockId.equals(lock.lockId()) && lock.className() != null) {
                        return lock.className();
                    }
                }
            }
        }
        return "unknown";
    }

    private AnalysisResult.Severity determineSeverity(LockContention contention) {
        int waiters = contention.waiterCount();
        if (waiters >= 10) return AnalysisResult.Severity.ERROR;
        if (waiters >= 5) return AnalysisResult.Severity.WARNING;
        if (waiters >= 2) return AnalysisResult.Severity.INFO;
        return AnalysisResult.Severity.OK;
    }

    private String formatLockId(String lockId) {
        if (lockId != null && lockId.length() > 10) {
            return lockId.substring(0, 10) + "...";
        }
        return lockId;
    }

    /**
     * Information about a contended lock
     */
    public record LockContention(
            String lockId,
            String lockClassName,
            ThreadInfo owner,
            List<ThreadInfo> waiters,
            int dumpIndex,
            java.time.Instant timestamp
    ) {
        public LockContention {
            waiters = waiters != null ? List.copyOf(waiters) : List.of();
        }

        public int waiterCount() {
            return waiters.size();
        }

        public List<String> getWaiterNames() {
            return waiters.stream()
                    .map(ThreadInfo::name)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    /**
     * Summary of lock contention analysis
     */
    public record LockContentionSummary(
            int totalContentedLocks,
            int maxWaiters,
            int hotLocks
    ) {}

    /**
     * JFR-based lock analysis results
     */
    public record JfrLockAnalysis(
            JfrAnalyzer.LockContentionSummary summary,
            List<AnalysisResult.Finding> additionalFindings
    ) {
        public JfrLockAnalysis {
            additionalFindings = additionalFindings != null ? List.copyOf(additionalFindings) : List.of();
        }
    }

    /**
     * Result of lock contention analysis
     */
    public static class LockContentionResult extends AnalysisResult {
        private final List<LockContention> contentions;
        private final LockContentionSummary summary;
        private final JfrLockAnalysis jfrAnalysis;

        public LockContentionResult(Severity severity, List<Finding> findings,
                                    List<LockContention> contentions,
                                    LockContentionSummary summary,
                                    JfrLockAnalysis jfrAnalysis) {
            super(NAME, severity, findings);
            this.contentions = List.copyOf(contentions);
            this.summary = summary;
            this.jfrAnalysis = jfrAnalysis;
        }

        public List<LockContention> getContentions() {
            return contentions;
        }

        public LockContentionSummary getLockSummary() {
            return summary;
        }

        public JfrLockAnalysis getJfrAnalysis() {
            return jfrAnalysis;
        }

        public boolean hasJfrData() {
            return jfrAnalysis != null;
        }

        public List<LockContention> getHotLocks(int minWaiters) {
            return contentions.stream()
                    .filter(c -> c.waiterCount() >= minWaiters)
                    .toList();
        }

        @Override
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            if (contentions.isEmpty()) {
                sb.append("No lock contention in dumps");
            } else {
                sb.append(String.format("Lock contention: %d contended locks, max %d waiters, %d hot locks",
                        summary.totalContentedLocks(), summary.maxWaiters(), summary.hotLocks()));
            }
            if (jfrAnalysis != null && jfrAnalysis.summary() != null) {
                sb.append(String.format("; JFR: %d events, %dms blocked",
                        jfrAnalysis.summary().totalEvents(),
                        jfrAnalysis.summary().totalBlockedTime().toMillis()));
            }
            return sb.toString();
        }
    }
}