package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.analysis.AnalysisContext.ThreadIdentifier;
import me.bechberger.jstall.model.StackFrame;
import me.bechberger.jstall.model.ThreadInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Analyzer that classifies thread progress between dumps.
 * Detects RUNNABLE threads making no progress, stuck threads, etc.
 */
public class ThreadProgressAnalyzer implements Analyzer<ThreadProgressAnalyzer.ProgressResult> {

    private static final String NAME = "ThreadProgressAnalyzer";

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Classifies thread progress between dumps to detect stalls";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        return true; // Can work on single or multiple dumps
    }

    @Override
    public boolean requiresMultipleDumps() {
        return false; // Works on single dump too (with limited info)
    }

    @Override
    public int getPriority() {
        return 100; // High priority - fundamental analysis
    }

    @Override
    public @NotNull ProgressResult analyze(@NotNull AnalysisContext context) {
        Map<ThreadIdentifier, ThreadProgress> progressMap = new LinkedHashMap<>();
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;

        if (context.isSingleDump()) {
            // Single dump: classify based on state only
            for (ThreadInfo thread : context.getFilteredThreads(context.getFirstDump())) {
                ThreadIdentifier id = ThreadIdentifier.of(thread);
                ProgressClassification classification = classifySingleDump(thread, context.getOptions());
                progressMap.put(id, new ThreadProgress(id, List.of(thread), classification));
            }
        } else {
            // Multi-dump: track progress over time
            Map<ThreadIdentifier, List<ThreadInfo>> matchedThreads = context.getMatchedThreads();

            for (Map.Entry<ThreadIdentifier, List<ThreadInfo>> entry : matchedThreads.entrySet()) {
                ThreadIdentifier id = entry.getKey();
                List<ThreadInfo> threadHistory = entry.getValue();

                // Filter based on options
                ThreadInfo lastNonNull = findLastNonNull(threadHistory);
                if (lastNonNull != null && !context.shouldIncludeThread(lastNonNull)) {
                    progressMap.put(id, new ThreadProgress(id, threadHistory, ProgressClassification.IGNORED));
                    continue;
                }

                ProgressClassification classification = classifyProgress(threadHistory, context.getOptions());
                progressMap.put(id, new ThreadProgress(id, threadHistory, classification));
            }
        }

        // Generate findings
        Map<ProgressClassification, List<ThreadProgress>> byClassification = new LinkedHashMap<>();
        for (ThreadProgress progress : progressMap.values()) {
            byClassification.computeIfAbsent(progress.classification(), k -> new ArrayList<>())
                    .add(progress);
        }

        // Report problems
        for (ProgressClassification classification : ProgressClassification.values()) {
            if (classification.isProblem()) {
                List<ThreadProgress> threads = byClassification.get(classification);
                if (threads != null && !threads.isEmpty()) {
                    AnalysisResult.Severity severity = switch (classification) {
                        case STUCK -> AnalysisResult.Severity.ERROR;
                        case RUNNABLE_NO_PROGRESS, BLOCKED_ON_LOCK -> AnalysisResult.Severity.WARNING;
                        default -> AnalysisResult.Severity.INFO;
                    };

                    if (severity.isWorseThan(worstSeverity)) {
                        worstSeverity = severity;
                    }

                    List<ThreadInfo> affected = threads.stream()
                            .map(p -> findLastNonNull(p.history()))
                            .filter(Objects::nonNull)
                            .toList();

                    findings.add(AnalysisResult.Finding.builder(severity, "progress",
                            String.format("%d threads classified as %s", threads.size(), classification))
                            .affectedThreads(affected)
                            .detail("classification", classification)
                            .detail("count", threads.size())
                            .build());
                }
            }
        }

        // Compute summary statistics
        int total = progressMap.size();
        int active = countByClassification(byClassification, ProgressClassification.ACTIVE);
        int noProgress = countByClassification(byClassification, ProgressClassification.RUNNABLE_NO_PROGRESS);
        int blocked = countByClassification(byClassification, ProgressClassification.BLOCKED_ON_LOCK);
        int stuck = countByClassification(byClassification, ProgressClassification.STUCK);
        int waiting = countByClassification(byClassification, ProgressClassification.WAITING_EXPECTED) +
                     countByClassification(byClassification, ProgressClassification.TIMED_WAITING_EXPECTED);

        ProgressSummary summary = new ProgressSummary(total, active, noProgress, blocked, stuck, waiting);

        return new ProgressResult(worstSeverity, findings, progressMap, summary);
    }

    private ProgressClassification classifySingleDump(ThreadInfo thread, AnalysisOptions options) {
        if (thread.state() == null) {
            return ProgressClassification.UNKNOWN;
        }

        return switch (thread.state()) {
            case NEW -> ProgressClassification.NEW;
            case RUNNABLE -> ProgressClassification.ACTIVE; // Assume active for single dump
            case BLOCKED -> ProgressClassification.BLOCKED_ON_LOCK;
            case WAITING -> {
                if (isExpectedWaiting(thread)) {
                    yield ProgressClassification.WAITING_EXPECTED;
                }
                yield ProgressClassification.STUCK;
            }
            case TIMED_WAITING -> {
                if (isExpectedTimedWaiting(thread)) {
                    yield ProgressClassification.TIMED_WAITING_EXPECTED;
                }
                yield ProgressClassification.STUCK;
            }
            case TERMINATED -> ProgressClassification.TERMINATED;
        };
    }

    private ProgressClassification classifyProgress(List<ThreadInfo> history, AnalysisOptions options) {
        // Check if thread is absent from first dump (new thread)
        if (history.getFirst() == null) {
            ThreadInfo lastNonNull = findLastNonNull(history);
            if (lastNonNull != null) {
                return ProgressClassification.NEW;
            }
            return ProgressClassification.UNKNOWN;
        }

        // Check if thread is absent from last dump (terminated thread)
        if (history.getLast() == null) {
            return ProgressClassification.TERMINATED;
        }

        ThreadInfo first = findFirstNonNull(history);
        ThreadInfo last = findLastNonNull(history);

        if (first == null || last == null) {
            return ProgressClassification.UNKNOWN;
        }

        // Check for restart (elapsed time decreased)
        if (first.elapsedTimeMs() != null && last.elapsedTimeMs() != null) {
            if (last.elapsedTimeMs() < first.elapsedTimeMs()) {
                return ProgressClassification.RESTARTED;
            }
        }

        // Check current state
        if (last.state() == Thread.State.TERMINATED) {
            return ProgressClassification.TERMINATED;
        }

        if (last.state() == Thread.State.BLOCKED) {
            return ProgressClassification.BLOCKED_ON_LOCK;
        }

        if (last.state() == Thread.State.WAITING) {
            if (isExpectedWaiting(last)) {
                return ProgressClassification.WAITING_EXPECTED;
            }
        }

        if (last.state() == Thread.State.TIMED_WAITING) {
            if (isExpectedTimedWaiting(last)) {
                return ProgressClassification.TIMED_WAITING_EXPECTED;
            }
        }

        // Check for RUNNABLE progress
        if (last.state() == Thread.State.RUNNABLE) {
            boolean cpuProgress = hasCpuProgress(history, options.getCpuEpsilonMs());
            boolean stackChanged = hasStackChanged(history);

            if (cpuProgress || stackChanged) {
                return ProgressClassification.ACTIVE;
            } else {
                return ProgressClassification.RUNNABLE_NO_PROGRESS;
            }
        }

        // Check for stuck (no changes across multiple dumps)
        if (isStuck(history)) {
            return ProgressClassification.STUCK;
        }

        return ProgressClassification.UNKNOWN;
    }

    private boolean hasCpuProgress(List<ThreadInfo> history, long epsilon) {
        Long firstCpu = null;
        Long lastCpu = null;

        for (ThreadInfo t : history) {
            if (t != null && t.cpuTimeMs() != null) {
                if (firstCpu == null) {
                    firstCpu = t.cpuTimeMs();
                }
                lastCpu = t.cpuTimeMs();
            }
        }

        if (firstCpu == null || lastCpu == null) {
            return false; // Can't determine
        }

        return (lastCpu - firstCpu) > epsilon;
    }

    private boolean hasStackChanged(List<ThreadInfo> history) {
        List<StackFrame> firstStack = null;

        for (ThreadInfo t : history) {
            if (t != null && t.stackTrace() != null && !t.stackTrace().isEmpty()) {
                if (firstStack == null) {
                    firstStack = t.stackTrace();
                } else if (!firstStack.equals(t.stackTrace())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isStuck(List<ThreadInfo> history) {
        // Check if state and stack are unchanged across multiple dumps
        Thread.State firstState = null;
        List<StackFrame> firstStack = null;
        int unchangedCount = 0;

        for (ThreadInfo t : history) {
            if (t == null) continue;

            if (firstState == null) {
                firstState = t.state();
                firstStack = t.stackTrace();
                unchangedCount = 1;
            } else if (Objects.equals(firstState, t.state()) &&
                       Objects.equals(firstStack, t.stackTrace())) {
                unchangedCount++;
            }
        }

        return unchangedCount >= 2 && firstState != Thread.State.RUNNABLE;
    }

    private boolean isExpectedWaiting(ThreadInfo thread) {
        String name = thread.name();
        if (name == null) return false;

        // Common pool/worker thread patterns
        return name.contains("pool") ||
               name.contains("Pool") ||
               name.contains("worker") ||
               name.contains("Worker") ||
               name.contains("executor") ||
               name.contains("Executor") ||
               name.startsWith("ForkJoinPool") ||
               name.startsWith("CommonPool");
    }

    private boolean isExpectedTimedWaiting(ThreadInfo thread) {
        String name = thread.name();
        if (name == null) return false;

        // Scheduler and background patterns
        return name.contains("Timer") ||
               name.contains("Scheduler") ||
               name.contains("Scheduled") ||
               name.contains("KeepAlive") ||
               name.contains("Heartbeat");
    }

    private @Nullable ThreadInfo findFirstNonNull(List<ThreadInfo> history) {
        for (ThreadInfo t : history) {
            if (t != null) return t;
        }
        return null;
    }

    private @Nullable ThreadInfo findLastNonNull(List<ThreadInfo> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) != null) return history.get(i);
        }
        return null;
    }

    private int countByClassification(Map<ProgressClassification, List<ThreadProgress>> map,
                                      ProgressClassification classification) {
        List<ThreadProgress> list = map.get(classification);
        return list != null ? list.size() : 0;
    }

    /**
     * Progress information for a single thread
     */
    public record ThreadProgress(
            ThreadIdentifier identifier,
            List<ThreadInfo> history,
            ProgressClassification classification
    ) {
        public ThreadProgress {
            // Use ArrayList copy since history may contain null elements
            history = history != null ? new ArrayList<>(history) : List.of();
        }

        public boolean isProblem() {
            return classification.isProblem();
        }

        public boolean isHealthy() {
            return classification.isHealthy();
        }
    }

    /**
     * Summary of progress classifications
     */
    public record ProgressSummary(
            int total,
            int active,
            int noProgress,
            int blocked,
            int stuck,
            int waiting
    ) {
        public int problemCount() {
            return noProgress + blocked + stuck;
        }

        public double problemPercentage() {
            if (total == 0) return 0.0;
            return (problemCount() * 100.0) / total;
        }

        public boolean indicatesStall(double threshold) {
            return problemPercentage() >= threshold;
        }
    }

    /**
     * Result of progress analysis
     */
    public static class ProgressResult extends AnalysisResult {
        private final Map<ThreadIdentifier, ThreadProgress> progressMap;
        private final ProgressSummary summary;

        public ProgressResult(Severity severity, List<Finding> findings,
                             Map<ThreadIdentifier, ThreadProgress> progressMap,
                             ProgressSummary summary) {
            super(NAME, severity, findings);
            this.progressMap = Map.copyOf(progressMap);
            this.summary = summary;
        }

        public Map<ThreadIdentifier, ThreadProgress> getProgressMap() {
            return progressMap;
        }

        public ProgressSummary getProgressSummary() {
            return summary;
        }

        public Optional<ThreadProgress> getProgress(ThreadIdentifier id) {
            return Optional.ofNullable(progressMap.get(id));
        }

        public List<ThreadProgress> getByClassification(ProgressClassification classification) {
            return progressMap.values().stream()
                    .filter(p -> p.classification() == classification)
                    .toList();
        }

        public List<ThreadProgress> getProblems() {
            return progressMap.values().stream()
                    .filter(ThreadProgress::isProblem)
                    .toList();
        }

        @Override
        public String getSummary() {
            return String.format("Progress: %d active, %d no-progress, %d blocked, %d stuck (%.1f%% problem)",
                    summary.active(), summary.noProgress(), summary.blocked(),
                    summary.stuck(), summary.problemPercentage());
        }
    }
}