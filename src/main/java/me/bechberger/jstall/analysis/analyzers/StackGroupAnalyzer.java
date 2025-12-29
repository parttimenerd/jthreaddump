package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.model.StackFrame;
import me.bechberger.jstall.model.ThreadDump;
import me.bechberger.jstall.model.ThreadInfo;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzer that groups threads by identical stack traces.
 * Useful for identifying clusters of threads doing the same thing.
 */
public class StackGroupAnalyzer implements Analyzer<StackGroupAnalyzer.StackGroupResult> {

    private static final String NAME = "StackGroupAnalyzer";
    private static final int DEFAULT_MIN_GROUP_SIZE = 2;

    private final int minGroupSize;

    public StackGroupAnalyzer() {
        this(DEFAULT_MIN_GROUP_SIZE);
    }

    public StackGroupAnalyzer(int minGroupSize) {
        this.minGroupSize = minGroupSize;
    }

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Groups threads by identical stack traces to identify clusters";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        return true; // Can analyze any context
    }

    @Override
    public @NotNull StackGroupResult analyze(@NotNull AnalysisContext context) {
        List<StackGroup> allGroups = new ArrayList<>();

        for (int i = 0; i < context.getDumpCount(); i++) {
            ThreadDump dump = context.getDumps().get(i);
            List<ThreadInfo> filteredThreads = context.getFilteredThreads(dump);

            Map<List<StackFrame>, List<ThreadInfo>> groupedByStack = filteredThreads.stream()
                    .filter(t -> t.stackTrace() != null && !t.stackTrace().isEmpty())
                    .collect(Collectors.groupingBy(ThreadInfo::stackTrace));

            for (Map.Entry<List<StackFrame>, List<ThreadInfo>> entry : groupedByStack.entrySet()) {
                if (entry.getValue().size() >= minGroupSize) {
                    allGroups.add(new StackGroup(
                            entry.getKey(),
                            entry.getValue(),
                            i,
                            dump.timestamp()
                    ));
                }
            }
        }

        // Sort by group size (largest first)
        allGroups.sort((a, b) -> Integer.compare(b.threads().size(), a.threads().size()));

        // Analyze group evolution across dumps
        GroupEvolutionAnalysis evolution = analyzeGroupEvolution(allGroups, context.getDumpCount());

        // Build findings
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;

        for (StackGroup group : allGroups) {
            AnalysisResult.Severity severity = determineSeverity(group);
            if (severity.isWorseThan(worstSeverity)) {
                worstSeverity = severity;
            }

            String topFrame = group.stack().isEmpty() ? "empty" :
                    formatFrame(group.stack().getFirst());

            findings.add(AnalysisResult.Finding.builder(severity, "identical-stacks",
                    String.format("%d threads share identical stack at %s",
                            group.threads().size(), topFrame))
                    .affectedThreads(group.threads())
                    .detail("dumpIndex", group.dumpIndex())
                    .detail("stackDepth", group.stack().size())
                    .detail("topFrame", topFrame)
                    .build());
        }

        // Add findings for evolving groups
        for (GroupEvolution evo : evolution.evolvingGroups()) {
            if (evo.isGrowing() && evo.sizeChange() > 5) {
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.WARNING, "growing-stack-group",
                        String.format("Stack group growing: %d → %d threads (%s)",
                                evo.firstSize(), evo.lastSize(), evo.topFrame()))
                        .detail("sizeChange", evo.sizeChange())
                        .detail("firstDump", evo.firstSeenDump())
                        .detail("lastDump", evo.lastSeenDump())
                        .build());

                if (worstSeverity.getLevel() < AnalysisResult.Severity.WARNING.getLevel()) {
                    worstSeverity = AnalysisResult.Severity.WARNING;
                }
            } else if (evo.isShrinking() && evo.firstSize() > 10) {
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.INFO, "shrinking-stack-group",
                        String.format("Stack group shrinking: %d → %d threads (%s)",
                                evo.firstSize(), evo.lastSize(), evo.topFrame()))
                        .detail("sizeChange", evo.sizeChange())
                        .build());
            } else if (evo.isStuck() && evo.occurrences() >= 3) {
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.WARNING, "stuck-stack-group",
                        String.format("Stack group stuck: %d threads unchanged across %d dumps (%s)",
                                evo.firstSize(), evo.occurrences(), evo.topFrame()))
                        .detail("occurrences", evo.occurrences())
                        .build());

                if (worstSeverity.getLevel() < AnalysisResult.Severity.WARNING.getLevel()) {
                    worstSeverity = AnalysisResult.Severity.WARNING;
                }
            }
        }

        return new StackGroupResult(worstSeverity, findings, allGroups, evolution, minGroupSize);
    }

    /**
     * Analyze how groups evolve across multiple dumps
     */
    private GroupEvolutionAnalysis analyzeGroupEvolution(List<StackGroup> allGroups, int dumpCount) {
        if (dumpCount <= 1 || allGroups.isEmpty()) {
            return new GroupEvolutionAnalysis(List.of(), Map.of());
        }

        // Group by stack signature (top 3 frames for matching)
        Map<String, List<StackGroup>> groupsBySignature = new LinkedHashMap<>();

        for (StackGroup group : allGroups) {
            String signature = computeStackSignature(group.stack());
            groupsBySignature.computeIfAbsent(signature, k -> new ArrayList<>()).add(group);
        }

        // Analyze evolution
        List<GroupEvolution> evolvingGroups = new ArrayList<>();
        Map<Integer, List<StackGroup>> groupsByDump = new TreeMap<>();

        for (Map.Entry<String, List<StackGroup>> entry : groupsBySignature.entrySet()) {
            List<StackGroup> groups = entry.getValue();

            if (groups.size() >= 1) {
                // Sort by dump index
                groups.sort(Comparator.comparingInt(StackGroup::dumpIndex));

                StackGroup first = groups.getFirst();
                StackGroup last = groups.get(groups.size() - 1);

                int firstSize = first.threads().size();
                int lastSize = last.threads().size();
                int sizeChange = lastSize - firstSize;

                // Get size per dump
                List<Integer> sizesPerDump = new ArrayList<>();
                for (StackGroup g : groups) {
                    sizesPerDump.add(g.threads().size());
                }

                // Determine trend
                boolean isGrowing = sizeChange > 0;
                boolean isShrinking = sizeChange < 0;
                boolean isStuck = sizesPerDump.stream().distinct().count() == 1; // All same size
                boolean isPersistent = groups.size() >= dumpCount * 0.5; // In >50% of dumps

                String topFrame = first.stack().isEmpty() ? "unknown" : formatFrame(first.stack().getFirst());

                evolvingGroups.add(new GroupEvolution(
                        entry.getKey(),
                        first.stack(),
                        sizesPerDump,
                        first.dumpIndex(),
                        last.dumpIndex(),
                        groups.size(),
                        firstSize,
                        lastSize,
                        sizeChange,
                        isGrowing,
                        isShrinking,
                        isStuck,
                        isPersistent,
                        topFrame
                ));
            }
        }

        // Organize by dump
        for (StackGroup group : allGroups) {
            groupsByDump.computeIfAbsent(group.dumpIndex(), k -> new ArrayList<>()).add(group);
        }

        // Sort by size change (largest changes first)
        evolvingGroups.sort((a, b) -> Integer.compare(Math.abs(b.sizeChange()), Math.abs(a.sizeChange())));

        return new GroupEvolutionAnalysis(evolvingGroups, groupsByDump);
    }

    private String computeStackSignature(List<StackFrame> stack) {
        // Use top 3 frames for matching (or fewer if stack is shorter)
        int depth = Math.min(3, stack.size());
        List<String> frames = new ArrayList<>();

        for (int i = 0; i < depth; i++) {
            StackFrame frame = stack.get(i);
            frames.add(frame.className() + "." + frame.methodName());
        }

        return String.join("|", frames);
    }

    private AnalysisResult.Severity determineSeverity(StackGroup group) {
        int size = group.threads().size();
        List<StackFrame> stack = group.stack();

        // Check if threads are blocked on I/O or locks
        if (!stack.isEmpty()) {
            String topMethod = stack.getFirst().methodName();
            String topClass = stack.getFirst().className();

            // I/O blocking patterns
            if (topClass != null && (
                    topClass.contains("SocketInputStream") ||
                    topClass.contains("FileInputStream") ||
                    topClass.contains("PlainSocketImpl") ||
                    topClass.contains("EPoll") ||
                    topClass.contains("WindowsSelectorImpl"))) {
                if (size >= 10) return AnalysisResult.Severity.WARNING;
                if (size >= 5) return AnalysisResult.Severity.INFO;
            }

            // Lock contention patterns
            if ("park".equals(topMethod) || "wait".equals(topMethod) ||
                    "lock".equals(topMethod) || "acquire".equals(topMethod)) {
                if (size >= 10) return AnalysisResult.Severity.WARNING;
                if (size >= 5) return AnalysisResult.Severity.INFO;
            }
        }

        // General size-based severity
        if (size >= 20) return AnalysisResult.Severity.WARNING;
        if (size >= 10) return AnalysisResult.Severity.INFO;
        return AnalysisResult.Severity.OK;
    }

    private String formatFrame(StackFrame frame) {
        if (frame.className() != null && frame.methodName() != null) {
            String className = frame.className();
            int lastDot = className.lastIndexOf('.');
            if (lastDot >= 0) {
                className = className.substring(lastDot + 1);
            }
            return className + "." + frame.methodName();
        }
        return frame.toString();
    }

    /**
     * Tracks evolution of a stack group across multiple dumps
     */
    public record GroupEvolution(
            String signature,
            List<StackFrame> stack,
            List<Integer> sizesPerDump,
            int firstSeenDump,
            int lastSeenDump,
            int occurrences,
            int firstSize,
            int lastSize,
            int sizeChange,
            boolean isGrowing,
            boolean isShrinking,
            boolean isStuck,
            boolean isPersistent,
            String topFrame
    ) {
        public GroupEvolution {
            stack = stack != null ? List.copyOf(stack) : List.of();
            sizesPerDump = sizesPerDump != null ? List.copyOf(sizesPerDump) : List.of();
        }

        public int dumpSpan() {
            return lastSeenDump - firstSeenDump + 1;
        }

        public String getTrend() {
            if (isStuck) return "STUCK";
            if (isGrowing) return "GROWING";
            if (isShrinking) return "SHRINKING";
            return "STABLE";
        }

        public double getGrowthRate() {
            if (firstSize == 0) return 0;
            return ((double) sizeChange / firstSize) * 100.0;
        }
    }

    /**
     * Analysis of group evolution across multiple dumps
     */
    public record GroupEvolutionAnalysis(
            List<GroupEvolution> evolvingGroups,
            Map<Integer, List<StackGroup>> groupsByDump
    ) {
        public GroupEvolutionAnalysis {
            evolvingGroups = evolvingGroups != null ? List.copyOf(evolvingGroups) : List.of();
            groupsByDump = groupsByDump != null ? Map.copyOf(groupsByDump) : Map.of();
        }

        public boolean hasEvolution() {
            return !evolvingGroups.isEmpty();
        }

        public int getGrowingGroupCount() {
            return (int) evolvingGroups.stream().filter(GroupEvolution::isGrowing).count();
        }

        public int getShrinkingGroupCount() {
            return (int) evolvingGroups.stream().filter(GroupEvolution::isShrinking).count();
        }

        public int getStuckGroupCount() {
            return (int) evolvingGroups.stream().filter(GroupEvolution::isStuck).count();
        }
    }

    /**
     * A group of threads with identical stack traces
     */
    public record StackGroup(
            List<StackFrame> stack,
            List<ThreadInfo> threads,
            int dumpIndex,
            java.time.Instant timestamp
    ) {
        public StackGroup {
            stack = stack != null ? List.copyOf(stack) : List.of();
            threads = threads != null ? List.copyOf(threads) : List.of();
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

        public Optional<StackFrame> getTopFrame() {
            return stack.isEmpty() ? Optional.empty() : Optional.of(stack.getFirst());
        }
    }

    /**
     * Result of stack group analysis
     */
    public static class StackGroupResult extends AnalysisResult {
        private final List<StackGroup> groups;
        private final GroupEvolutionAnalysis evolution;
        private final int minGroupSize;

        public StackGroupResult(Severity severity, List<Finding> findings,
                               List<StackGroup> groups, int minGroupSize) {
            this(severity, findings, groups, null, minGroupSize);
        }

        public StackGroupResult(Severity severity, List<Finding> findings,
                               List<StackGroup> groups, GroupEvolutionAnalysis evolution,
                               int minGroupSize) {
            super(NAME, severity, findings);
            this.groups = List.copyOf(groups);
            this.evolution = evolution;
            this.minGroupSize = minGroupSize;
        }

        public List<StackGroup> getGroups() {
            return groups;
        }

        public GroupEvolutionAnalysis getEvolution() {
            return evolution;
        }

        public boolean hasEvolution() {
            return evolution != null && evolution.hasEvolution();
        }

        public int getMinGroupSize() {
            return minGroupSize;
        }

        /**
         * Get groups for a specific dump
         */
        public List<StackGroup> getGroupsForDump(int dumpIndex) {
            return groups.stream()
                    .filter(g -> g.dumpIndex() == dumpIndex)
                    .toList();
        }

        /**
         * Get the largest group
         */
        public Optional<StackGroup> getLargestGroup() {
            return groups.stream()
                    .max(Comparator.comparingInt(StackGroup::size));
        }

        /**
         * Get total number of threads in groups
         */
        public int getTotalGroupedThreads() {
            return groups.stream()
                    .mapToInt(StackGroup::size)
                    .sum();
        }

        @Override
        public String getSummary() {
            if (groups.isEmpty()) {
                return "No thread groups with identical stacks found";
            }
            int totalThreads = getTotalGroupedThreads();
            return String.format("%d groups with identical stacks (%d threads total)",
                    groups.size(), totalThreads);
        }
    }
}