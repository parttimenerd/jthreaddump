package me.bechberger.jstall.analysis;

import me.bechberger.jstall.jfr.JfrParser;
import me.bechberger.jstall.model.ThreadDump;
import me.bechberger.jstall.model.ThreadInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Context for analysis operations, holding thread dumps and optional JFR data.
 * Provides utilities for temporal analysis across multiple dumps.
 */
public class AnalysisContext {

    private final List<ThreadDump> dumps;
    private final @Nullable Path jfrFile;
    private final AnalysisOptions options;

    // Cached thread matching across dumps
    private Map<Long, List<ThreadInfo>> threadsByNativeId;
    private Map<Long, List<ThreadInfo>> threadsByThreadId;
    private Map<String, List<ThreadInfo>> threadsByName;

    // Cached thread evolution data
    private Map<String, ThreadEvolution> threadEvolutions;

    // Cached JFR data
    private @Nullable JfrParser.JfrData jfrData;
    private boolean jfrDataLoaded = false;

    private AnalysisContext(List<ThreadDump> dumps, @Nullable Path jfrFile, AnalysisOptions options) {
        if (dumps == null || dumps.isEmpty()) {
            throw new IllegalArgumentException("At least one thread dump is required");
        }
        this.dumps = List.copyOf(dumps);
        this.jfrFile = jfrFile;
        this.options = options != null ? options : AnalysisOptions.defaults();
    }

    /**
     * Create a context for single dump analysis
     */
    public static AnalysisContext of(@NotNull ThreadDump dump) {
        return new AnalysisContext(List.of(dump), null, null);
    }

    /**
     * Create a context for single dump analysis with options
     */
    public static AnalysisContext of(@NotNull ThreadDump dump, @NotNull AnalysisOptions options) {
        return new AnalysisContext(List.of(dump), null, options);
    }

    /**
     * Create a context for multi-dump temporal analysis
     */
    public static AnalysisContext of(@NotNull List<ThreadDump> dumps) {
        return new AnalysisContext(dumps, null, null);
    }

    /**
     * Create a context for multi-dump temporal analysis with options
     */
    public static AnalysisContext of(@NotNull List<ThreadDump> dumps, @NotNull AnalysisOptions options) {
        return new AnalysisContext(dumps, null, options);
    }

    /**
     * Create a context with JFR correlation
     */
    public static AnalysisContext of(@NotNull List<ThreadDump> dumps, @NotNull Path jfrFile) {
        return new AnalysisContext(dumps, jfrFile, null);
    }

    /**
     * Create a context with JFR correlation and options
     */
    public static AnalysisContext of(@NotNull List<ThreadDump> dumps, @NotNull Path jfrFile, @NotNull AnalysisOptions options) {
        return new AnalysisContext(dumps, jfrFile, options);
    }

    // --- Getters ---

    public List<ThreadDump> getDumps() {
        return dumps;
    }

    public ThreadDump getFirstDump() {
        return dumps.getFirst();
    }

    public ThreadDump getLastDump() {
        return dumps.getLast();
    }

    public int getDumpCount() {
        return dumps.size();
    }

    public boolean isSingleDump() {
        return dumps.size() == 1;
    }

    public boolean isMultiDump() {
        return dumps.size() > 1;
    }

    public boolean hasJfr() {
        return jfrFile != null;
    }

    public @Nullable Path getJfrFile() {
        return jfrFile;
    }

    public AnalysisOptions getOptions() {
        return options;
    }

    // --- JFR Data Access ---

    /**
     * Get the JFR data, loading it if necessary.
     * Returns null if no JFR file is configured or if loading fails.
     */
    public @Nullable JfrParser.JfrData getJfrData() {
        if (!jfrDataLoaded && jfrFile != null) {
            jfrDataLoaded = true;
            try {
                JfrParser parser = new JfrParser();
                jfrData = parser.parse(jfrFile);
            } catch (IOException e) {
                // Log error but continue without JFR data
                jfrData = null;
            }
        }
        return jfrData;
    }

    /**
     * Get JFR data filtered to the time range of the thread dumps.
     */
    public @Nullable JfrParser.JfrData getJfrDataForDumpRange() {
        JfrParser.JfrData data = getJfrData();
        if (data == null) return null;

        Instant start = getFirstDump().timestamp();
        Instant end = getLastDump().timestamp();
        if (start == null || end == null) {
            return data;
        }

        // Add some buffer around the dumps
        start = start.minusSeconds(5);
        end = end.plusSeconds(5);

        return data.filterByTimeRange(start, end);
    }

    /**
     * Get JFR data for a specific thread dump (within a time window around it).
     */
    public @Nullable JfrParser.JfrData getJfrDataForDump(int dumpIndex) {
        if (dumpIndex < 0 || dumpIndex >= dumps.size()) {
            return null;
        }
        JfrParser.JfrData data = getJfrData();
        if (data == null) return null;

        ThreadDump dump = dumps.get(dumpIndex);
        Instant dumpTime = dump.timestamp();
        if (dumpTime == null) return data;

        // Use a window around the dump timestamp
        Instant start = dumpTime.minusSeconds(2);
        Instant end = dumpTime.plusSeconds(2);

        return data.filterByTimeRange(start, end);
    }

    // --- Temporal Analysis Utilities ---

    /**
     * Get the total time span covered by the dumps
     */
    public @Nullable Duration getTimeSpan() {
        if (isSingleDump()) {
            return Duration.ZERO;
        }
        Instant first = getFirstDump().timestamp();
        Instant last = getLastDump().timestamp();
        if (first == null || last == null) {
            return null;
        }
        return Duration.between(first, last);
    }

    /**
     * Find a thread across all dumps by native thread ID (most reliable)
     */
    public List<ThreadInfo> findThreadByNativeId(long nativeId) {
        if (threadsByNativeId == null) {
            buildThreadIndices();
        }
        return threadsByNativeId.getOrDefault(nativeId, List.of());
    }

    /**
     * Find a thread across all dumps by Java thread ID
     */
    public List<ThreadInfo> findThreadByThreadId(long threadId) {
        if (threadsByThreadId == null) {
            buildThreadIndices();
        }
        return threadsByThreadId.getOrDefault(threadId, List.of());
    }

    /**
     * Find a thread across all dumps by name (least reliable)
     */
    public List<ThreadInfo> findThreadByName(String name) {
        if (threadsByName == null) {
            buildThreadIndices();
        }
        return threadsByName.getOrDefault(name, List.of());
    }

    /**
     * Match a thread from one dump to its corresponding thread in another dump.
     * Uses native ID (primary), thread ID (secondary), or name (fallback).
     */
    public @Nullable ThreadInfo matchThread(ThreadInfo thread, ThreadDump targetDump) {
        // Try native ID first (most reliable)
        if (thread.nativeId() != null) {
            for (ThreadInfo t : targetDump.threads()) {
                if (thread.nativeId().equals(t.nativeId())) {
                    return t;
                }
            }
        }

        // Try Java thread ID second
        if (thread.threadId() != null) {
            for (ThreadInfo t : targetDump.threads()) {
                if (thread.threadId().equals(t.threadId())) {
                    return t;
                }
            }
        }

        // Fallback to name (least reliable)
        for (ThreadInfo t : targetDump.threads()) {
            if (Objects.equals(thread.name(), t.name())) {
                return t;
            }
        }

        return null;
    }

    /**
     * Get all unique threads across all dumps, matched by identity.
     * Returns a map of thread identifier to list of ThreadInfo (one per dump, null if absent).
     */
    public Map<ThreadIdentifier, List<ThreadInfo>> getMatchedThreads() {
        Map<ThreadIdentifier, List<ThreadInfo>> result = new LinkedHashMap<>();

        for (int i = 0; i < dumps.size(); i++) {
            ThreadDump dump = dumps.get(i);
            for (ThreadInfo thread : dump.threads()) {
                ThreadIdentifier id = ThreadIdentifier.of(thread);
                List<ThreadInfo> list = result.computeIfAbsent(id, k -> {
                    List<ThreadInfo> l = new ArrayList<>(dumps.size());
                    for (int j = 0; j < dumps.size(); j++) {
                        l.add(null);
                    }
                    return l;
                });
                list.set(i, thread);
            }
        }

        return result;
    }

    /**
     * Check if a thread should be included based on current filter options
     */
    public boolean shouldIncludeThread(ThreadInfo thread) {
        return options.getThreadFilter().test(thread);
    }

    /**
     * Get filtered threads from a dump based on current options
     */
    public List<ThreadInfo> getFilteredThreads(ThreadDump dump) {
        return dump.threads().stream()
                .filter(this::shouldIncludeThread)
                .toList();
    }

    /**
     * Get all filtered threads from all dumps
     */
    public List<List<ThreadInfo>> getAllFilteredThreads() {
        return dumps.stream()
                .map(this::getFilteredThreads)
                .toList();
    }

    private void buildThreadIndices() {
        threadsByNativeId = new HashMap<>();
        threadsByThreadId = new HashMap<>();
        threadsByName = new HashMap<>();

        for (ThreadDump dump : dumps) {
            for (ThreadInfo thread : dump.threads()) {
                if (thread.nativeId() != null) {
                    threadsByNativeId.computeIfAbsent(thread.nativeId(), k -> new ArrayList<>()).add(thread);
                }
                if (thread.threadId() != null) {
                    threadsByThreadId.computeIfAbsent(thread.threadId(), k -> new ArrayList<>()).add(thread);
                }
                if (thread.name() != null) {
                    threadsByName.computeIfAbsent(thread.name(), k -> new ArrayList<>()).add(thread);
                }
            }
        }
    }

    /**
     * Get thread evolution data for all threads across dumps.
     * This computes and caches thread lifecycle information.
     */
    public Map<String, ThreadEvolution> getThreadEvolutions() {
        if (threadEvolutions != null) {
            return threadEvolutions;
        }

        threadEvolutions = new LinkedHashMap<>();

        if (isSingleDump()) {
            // No evolution in single dump
            return threadEvolutions;
        }

        // Build thread evolution for each unique thread
        Map<ThreadIdentifier, List<ThreadInfo>> matchedThreads = getMatchedThreads();

        for (Map.Entry<ThreadIdentifier, List<ThreadInfo>> entry : matchedThreads.entrySet()) {
            ThreadIdentifier id = entry.getKey();
            List<ThreadInfo> threadSnapshots = entry.getValue();

            // Find which dumps contain this thread
            List<Integer> dumpsPresent = new ArrayList<>();
            Map<Integer, Thread.State> stateByDump = new LinkedHashMap<>();
            ThreadInfo firstOccurrence = null;
            ThreadInfo lastOccurrence = null;

            for (int i = 0; i < threadSnapshots.size(); i++) {
                ThreadInfo thread = threadSnapshots.get(i);
                if (thread != null) {
                    dumpsPresent.add(i);
                    if (thread.state() != null) {
                        stateByDump.put(i, thread.state());
                    }
                    if (firstOccurrence == null) {
                        firstOccurrence = thread;
                    }
                    lastOccurrence = thread;
                }
            }

            if (dumpsPresent.isEmpty()) {
                continue;
            }

            // Determine thread lifecycle flags
            boolean appearedNewly = dumpsPresent.getFirst() > 0;
            boolean disappeared = dumpsPresent.getLast() < dumps.size() - 1;
            boolean stateChanged = stateByDump.values().stream().distinct().count() > 1;

            String threadName = id.name != null ? id.name : id.toString();

            ThreadEvolution evolution = new ThreadEvolution(
                    threadName,
                    id,
                    List.copyOf(dumpsPresent),
                    Map.copyOf(stateByDump),
                    appearedNewly,
                    disappeared,
                    stateChanged,
                    firstOccurrence,
                    lastOccurrence
            );

            threadEvolutions.put(threadName, evolution);
        }

        return threadEvolutions;
    }

    /**
     * Get time span between two dumps
     */
    public Duration getTimespanBetweenDumps(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= dumps.size() ||
            toIndex < 0 || toIndex >= dumps.size() ||
            fromIndex > toIndex) {
            return Duration.ZERO;
        }

        Instant from = dumps.get(fromIndex).timestamp();
        Instant to = dumps.get(toIndex).timestamp();

        if (from == null || to == null) {
            return Duration.ZERO;
        }

        return Duration.between(from, to);
    }

    /**
     * Get a thread by name in a specific dump
     */
    public @Nullable ThreadInfo getThreadByName(String name, int dumpIndex) {
        if (dumpIndex < 0 || dumpIndex >= dumps.size()) {
            return null;
        }

        return dumps.get(dumpIndex).threads().stream()
                .filter(t -> name.equals(t.name()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Track a thread across a range of dumps
     */
    public List<ThreadInfo> trackThread(ThreadIdentifier id, int fromDump, int toDump) {
        List<ThreadInfo> tracked = new ArrayList<>();

        for (int i = fromDump; i <= toDump && i < dumps.size(); i++) {
            ThreadDump dump = dumps.get(i);
            ThreadInfo found = null;

            for (ThreadInfo thread : dump.threads()) {
                if (id.matches(thread)) {
                    found = thread;
                    break;
                }
            }

            tracked.add(found);
        }

        return tracked;
    }

    /**
     * Detect changes across dumps using a custom extractor function
     */
    public <T> List<Change<T>> detectChanges(java.util.function.Function<ThreadDump, T> extractor) {
        List<Change<T>> changes = new ArrayList<>();

        if (isSingleDump()) {
            return changes;
        }

        T previous = extractor.apply(dumps.getFirst());

        for (int i = 1; i < dumps.size(); i++) {
            T current = extractor.apply(dumps.get(i));

            // Calculate delta (if both are numbers)
            double delta = 0.0;
            if (previous instanceof Number prev && current instanceof Number curr) {
                delta = curr.doubleValue() - prev.doubleValue();
            }

            changes.add(new Change<>(i, previous, current, delta));
            previous = current;
        }

        return changes;
    }

    /**
     * Represents a change between two dumps
     */
    public record Change<T>(
            int dumpIndex,
            T before,
            T after,
            double delta
    ) {
        public boolean hasChanged() {
            return !Objects.equals(before, after);
        }

        public boolean increased() {
            return delta > 0;
        }

        public boolean decreased() {
            return delta < 0;
        }
    }

    /**
     * Thread evolution data tracking a thread's lifecycle across multiple dumps
     */
    public record ThreadEvolution(
            String threadName,
            ThreadIdentifier identifier,
            List<Integer> dumpsPresent,
            Map<Integer, Thread.State> stateByDump,
            boolean appearedNewly,       // Thread didn't exist in first dump
            boolean disappeared,          // Thread doesn't exist in last dump
            boolean stateChanged,         // Thread state changed across dumps
            @Nullable ThreadInfo firstOccurrence,
            @Nullable ThreadInfo lastOccurrence
    ) {
        public ThreadEvolution {
            dumpsPresent = dumpsPresent != null ? List.copyOf(dumpsPresent) : List.of();
            stateByDump = stateByDump != null ? Map.copyOf(stateByDump) : Map.of();
        }

        /**
         * Check if thread was present in all dumps
         */
        public boolean isPersistent(int totalDumps) {
            return dumpsPresent.size() == totalDumps;
        }

        /**
         * Check if thread appeared in the middle of analysis
         */
        public boolean isTransient() {
            return appearedNewly || disappeared;
        }

        /**
         * Get the most common state across dumps
         */
        public @Nullable Thread.State getMostCommonState() {
            if (stateByDump.isEmpty()) {
                return null;
            }

            return stateByDump.values().stream()
                    .collect(java.util.stream.Collectors.groupingBy(s -> s, java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }

        /**
         * Get state in a specific dump
         */
        public @Nullable Thread.State getStateInDump(int dumpIndex) {
            return stateByDump.get(dumpIndex);
        }

        /**
         * Check if thread was present in a specific dump
         */
        public boolean wasPresentInDump(int dumpIndex) {
            return dumpsPresent.contains(dumpIndex);
        }
    }

    /**
     * Identifier for matching threads across dumps.
     * Prioritizes native ID, then thread ID, then name.
     */
    public record ThreadIdentifier(
            @Nullable Long nativeId,
            @Nullable Long threadId,
            @Nullable String name
    ) {
        public static ThreadIdentifier of(ThreadInfo thread) {
            return new ThreadIdentifier(thread.nativeId(), thread.threadId(), thread.name());
        }

        /**
         * Check if this identifier matches another thread
         */
        public boolean matches(ThreadInfo thread) {
            if (nativeId != null && nativeId.equals(thread.nativeId())) {
                return true;
            }
            if (threadId != null && threadId.equals(thread.threadId())) {
                return true;
            }
            return name != null && name.equals(thread.name());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ThreadIdentifier that)) return false;
            // Match by native ID first
            if (nativeId != null && that.nativeId != null) {
                return nativeId.equals(that.nativeId);
            }
            // Then by thread ID
            if (threadId != null && that.threadId != null) {
                return threadId.equals(that.threadId);
            }
            // Fallback to name
            return Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            if (nativeId != null) return nativeId.hashCode();
            if (threadId != null) return threadId.hashCode();
            return name != null ? name.hashCode() : 0;
        }

        @Override
        public String toString() {
            if (nativeId != null) {
                return String.format("nid=0x%x", nativeId);
            }
            if (threadId != null) {
                return String.format("tid=%d", threadId);
            }
            return name != null ? name : "unknown";
        }
    }
}