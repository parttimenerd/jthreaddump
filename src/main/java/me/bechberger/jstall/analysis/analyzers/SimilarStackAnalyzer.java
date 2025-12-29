package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.jfr.JfrAnalyzer;
import me.bechberger.jstall.jfr.JfrParser;
import me.bechberger.jstall.model.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Analyzer that groups threads by similar stack trace prefixes.
 * Works with both thread dump data and JFR execution samples.
 * Useful for identifying threads sharing the same entry point or execution path.
 */
public class SimilarStackAnalyzer implements Analyzer<SimilarStackAnalyzer.SimilarStackResult> {

    private static final String NAME = "SimilarStackAnalyzer";
    private static final int DEFAULT_MIN_GROUP_SIZE = 2;
    private static final int DEFAULT_PREFIX_DEPTH = 100; // Match first N frames (increased for better grouping)

    private final int minGroupSize;
    private final int prefixDepth;

    public SimilarStackAnalyzer() {
        this(DEFAULT_MIN_GROUP_SIZE, DEFAULT_PREFIX_DEPTH);
    }

    public SimilarStackAnalyzer(int minGroupSize, int prefixDepth) {
        this.minGroupSize = minGroupSize;
        this.prefixDepth = prefixDepth;
    }

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Groups threads by common stack trace prefix (entry point grouping)";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        return true;
    }

    @Override
    public int getPriority() {
        return 40; // Medium priority
    }

    @Override
    public @NotNull SimilarStackResult analyze(@NotNull AnalysisContext context) {
        List<SimilarStackGroup> allGroups = new ArrayList<>();
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;

        for (int dumpIndex = 0; dumpIndex < context.getDumpCount(); dumpIndex++) {
            ThreadDump dump = context.getDumps().get(dumpIndex);
            List<ThreadInfo> filteredThreads = context.getFilteredThreads(dump);

            // Group by stack prefix
            Map<List<StackFrame>, List<ThreadInfo>> groupedByPrefix = new LinkedHashMap<>();

            for (ThreadInfo thread : filteredThreads) {
                if (thread.stackTrace() == null || thread.stackTrace().isEmpty()) {
                    continue;
                }

                List<StackFrame> prefix = extractPrefix(thread.stackTrace());
                groupedByPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(thread);
            }

            for (Map.Entry<List<StackFrame>, List<ThreadInfo>> entry : groupedByPrefix.entrySet()) {
                if (entry.getValue().size() >= minGroupSize) {
                    allGroups.add(new SimilarStackGroup(
                            entry.getKey(),
                            entry.getValue(),
                            dumpIndex,
                            dump.timestamp()
                    ));
                }
            }
        }

        // Sort by group size (largest first)
        allGroups.sort((a, b) -> Integer.compare(b.threads().size(), a.threads().size()));

        // Analyze pattern evolution across dumps
        PatternEvolutionAnalysis evolution = analyzePatternEvolution(allGroups, context.getDumpCount());

        // Generate findings
        for (SimilarStackGroup group : allGroups) {
            AnalysisResult.Severity severity = determineSeverity(group);
            if (severity.isWorseThan(worstSeverity)) {
                worstSeverity = severity;
            }

            String entryPoint = formatEntryPoint(group.prefix());
            findings.add(AnalysisResult.Finding.builder(severity, "similar-stacks",
                    String.format("%d threads share entry point: %s",
                            group.threads().size(), entryPoint))
                    .affectedThreads(group.threads())
                    .detail("dumpIndex", group.dumpIndex())
                    .detail("prefixDepth", group.prefix().size())
                    .detail("entryPoint", entryPoint)
                    .build());
        }

        // Add findings for evolving patterns
        for (PatternEvolution evo : evolution.evolvingPatterns()) {
            if (evo.isGrowing() && evo.sizeChange() > 5) {
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.WARNING, "growing-pattern",
                        String.format("Entry point pattern growing: %d → %d threads (%s)",
                                evo.firstSize(), evo.lastSize(), evo.entryPoint()))
                        .detail("sizeChange", evo.sizeChange())
                        .detail("entryPoint", evo.entryPoint())
                        .build());

                if (worstSeverity.getLevel() < AnalysisResult.Severity.WARNING.getLevel()) {
                    worstSeverity = AnalysisResult.Severity.WARNING;
                }
            } else if (evo.hasThreadMigration() && evo.stabilityScore() < 0.5) {
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.INFO, "pattern-migration",
                        String.format("Thread migration detected in pattern %s (stability: %.1f%%)",
                                evo.entryPoint(), evo.stabilityScore() * 100))
                        .detail("stability", evo.stabilityScore())
                        .build());
            }
        }

        // Analyze JFR execution samples if available
        JfrStackAnalysis jfrAnalysis = null;
        if (context.hasJfr()) {
            jfrAnalysis = analyzeJfrStacks(context, findings);
            if (jfrAnalysis != null) {
                for (AnalysisResult.Finding finding : jfrAnalysis.additionalFindings()) {
                    if (finding.severity().isWorseThan(worstSeverity)) {
                        worstSeverity = finding.severity();
                    }
                }
            }
        }

        return new SimilarStackResult(worstSeverity, findings, allGroups, evolution, prefixDepth, jfrAnalysis);
    }

    /**
     * Analyze how patterns evolve across multiple dumps
     */
    private PatternEvolutionAnalysis analyzePatternEvolution(List<SimilarStackGroup> allGroups, int dumpCount) {
        if (dumpCount <= 1 || allGroups.isEmpty()) {
            return new PatternEvolutionAnalysis(List.of(), Map.of());
        }

        // Group by entry point signature
        Map<String, List<SimilarStackGroup>> groupsBySignature = new LinkedHashMap<>();

        for (SimilarStackGroup group : allGroups) {
            String signature = computeEntryPointSignature(group.prefix());
            groupsBySignature.computeIfAbsent(signature, k -> new ArrayList<>()).add(group);
        }

        // Analyze evolution
        List<PatternEvolution> evolvingPatterns = new ArrayList<>();
        Map<Integer, List<SimilarStackGroup>> groupsByDump = new TreeMap<>();

        for (Map.Entry<String, List<SimilarStackGroup>> entry : groupsBySignature.entrySet()) {
            List<SimilarStackGroup> groups = entry.getValue();

            if (groups.size() >= 1) {
                // Sort by dump index
                groups.sort(Comparator.comparingInt(SimilarStackGroup::dumpIndex));

                SimilarStackGroup first = groups.getFirst();
                SimilarStackGroup last = groups.get(groups.size() - 1);

                int firstSize = first.threads().size();
                int lastSize = last.threads().size();
                int sizeChange = lastSize - firstSize;

                // Get size per dump
                List<Integer> sizesPerDump = groups.stream()
                        .map(g -> g.threads().size())
                        .toList();

                // Calculate thread stability (how many same threads across dumps)
                double stabilityScore = calculateStabilityScore(groups);

                // Check for thread migration
                boolean hasThreadMigration = stabilityScore < 0.7; // <70% same threads

                String entryPoint = formatEntryPoint(first.prefix());

                evolvingPatterns.add(new PatternEvolution(
                        entry.getKey(),
                        first.prefix(),
                        sizesPerDump,
                        first.dumpIndex(),
                        last.dumpIndex(),
                        groups.size(),
                        firstSize,
                        lastSize,
                        sizeChange,
                        sizeChange > 0,
                        sizeChange < 0,
                        stabilityScore,
                        hasThreadMigration,
                        entryPoint
                ));
            }
        }

        // Organize by dump
        for (SimilarStackGroup group : allGroups) {
            groupsByDump.computeIfAbsent(group.dumpIndex(), k -> new ArrayList<>()).add(group);
        }

        // Sort by size change
        evolvingPatterns.sort((a, b) -> Integer.compare(Math.abs(b.sizeChange()), Math.abs(a.sizeChange())));

        return new PatternEvolutionAnalysis(evolvingPatterns, groupsByDump);
    }

    private String computeEntryPointSignature(List<StackFrame> prefix) {
        // Use all frames in prefix for matching
        List<String> frames = new ArrayList<>();
        for (StackFrame frame : prefix) {
            frames.add(frame.className() + "." + frame.methodName());
        }
        return String.join("|", frames);
    }

    private double calculateStabilityScore(List<SimilarStackGroup> groups) {
        if (groups.size() < 2) return 1.0;

        // Get all thread names from first group
        Set<String> firstThreads = groups.getFirst().threads().stream()
                .map(ThreadInfo::name)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        if (firstThreads.isEmpty()) return 0.0;

        // Count how many threads appear in subsequent groups
        int totalMatches = 0;
        int totalChecks = 0;

        for (int i = 1; i < groups.size(); i++) {
            Set<String> currentThreads = groups.get(i).threads().stream()
                    .map(ThreadInfo::name)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());

            Set<String> intersection = new HashSet<>(firstThreads);
            intersection.retainAll(currentThreads);

            totalMatches += intersection.size();
            totalChecks += firstThreads.size();
        }

        return totalChecks > 0 ? (double) totalMatches / totalChecks : 0.0;
    }

    /**
     * Analyze JFR execution samples to find common stack patterns
     */
    private JfrStackAnalysis analyzeJfrStacks(AnalysisContext context, List<AnalysisResult.Finding> findings) {
        JfrParser.JfrData jfrData = context.getJfrDataForDumpRange();
        if (jfrData == null || jfrData.executionSamples().isEmpty()) {
            return null;
        }

        JfrAnalyzer analyzer = new JfrAnalyzer();

        // Get stack profiles (grouped by common stacks)
        List<JfrAnalyzer.StackProfile> stackProfiles = analyzer.getStackProfiles(jfrData, prefixDepth);

        // Get hottest methods
        List<JfrAnalyzer.MethodProfile> hottestMethods = analyzer.getHottestMethods(jfrData, 10);

        // Get hottest threads
        List<JfrAnalyzer.ThreadProfile> hottestThreads = analyzer.getHottestThreads(jfrData, 10);

        List<AnalysisResult.Finding> additionalFindings = new ArrayList<>();

        // Report dominant execution patterns
        if (!stackProfiles.isEmpty()) {
            JfrAnalyzer.StackProfile dominant = stackProfiles.getFirst();
            if (dominant.percentage() >= 20) {
                additionalFindings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.INFO, "jfr-dominant-stack",
                        String.format("Dominant execution pattern: %.1f%% of samples share stack prefix",
                                dominant.percentage()))
                        .detail("sampleCount", dominant.sampleCount())
                        .detail("percentage", dominant.percentage())
                        .build());
                findings.add(additionalFindings.getLast());
            }
        }

        // Report if single method dominates CPU
        if (!hottestMethods.isEmpty()) {
            JfrAnalyzer.MethodProfile hottest = hottestMethods.getFirst();
            if (hottest.percentage() >= 30) {
                additionalFindings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.WARNING, "jfr-cpu-hotspot",
                        String.format("CPU hotspot: %s uses %.1f%% of CPU",
                                formatMethodName(hottest.method()), hottest.percentage()))
                        .detail("method", hottest.method())
                        .detail("percentage", hottest.percentage())
                        .build());
                findings.add(additionalFindings.getLast());
            }
        }

        return new JfrStackAnalysis(stackProfiles, hottestMethods, hottestThreads, additionalFindings);
    }

    private String formatMethodName(String method) {
        if (method == null) return "unknown";
        int lastDot = method.lastIndexOf('.');
        if (lastDot > 0) {
            int prevDot = method.lastIndexOf('.', lastDot - 1);
            if (prevDot > 0) {
                return "..." + method.substring(prevDot + 1);
            }
        }
        return method;
    }

    private List<StackFrame> extractPrefix(List<StackFrame> stack) {
        // The "prefix" is actually the bottom of the stack (entry point)
        // Stack traces are top-down, so entry point is at the end
        int size = stack.size();
        int depth = Math.min(prefixDepth, size);

        // Get the bottom N frames (entry point)
        return new ArrayList<>(stack.subList(size - depth, size));
    }

    private boolean hasStackVariation(List<ThreadInfo> threads) {
        if (threads.size() < 2) return false;

        List<StackFrame> firstStack = threads.getFirst().stackTrace();
        for (int i = 1; i < threads.size(); i++) {
            List<StackFrame> otherStack = threads.get(i).stackTrace();

            // Check if stacks are different (either different length or different frames)
            if (firstStack.size() != otherStack.size()) {
                return true; // Different stack depths = variation
            }

            // Skip the prefix (already known to be the same), check if remainder differs
            int prefixLen = Math.min(prefixDepth, firstStack.size());
            for (int j = prefixLen; j < firstStack.size(); j++) {
                if (!Objects.equals(firstStack.get(j), otherStack.get(j))) {
                    return true; // Found variation after prefix
                }
            }
        }
        return false; // All stacks are identical
    }

    private AnalysisResult.Severity determineSeverity(SimilarStackGroup group) {
        int size = group.threads().size();
        if (size >= 20) return AnalysisResult.Severity.WARNING;
        if (size >= 10) return AnalysisResult.Severity.INFO;
        return AnalysisResult.Severity.OK;
    }

    private String formatEntryPoint(List<StackFrame> prefix) {
        if (prefix.isEmpty()) return "unknown";

        // Show the entry point (last frame in prefix, which is bottom of stack)
        StackFrame entryFrame = prefix.getLast();
        String className = entryFrame.className();
        if (className != null) {
            int lastDot = className.lastIndexOf('.');
            if (lastDot >= 0) {
                className = className.substring(lastDot + 1);
            }
        }
        return className + "." + entryFrame.methodName();
    }

    /**
     * Tracks evolution of an entry point pattern across multiple dumps
     */
    public record PatternEvolution(
            String signature,
            List<StackFrame> prefix,
            List<Integer> sizesPerDump,
            int firstSeenDump,
            int lastSeenDump,
            int occurrences,
            int firstSize,
            int lastSize,
            int sizeChange,
            boolean isGrowing,
            boolean isShrinking,
            double stabilityScore,      // 0-1, how many same threads
            boolean hasThreadMigration,
            String entryPoint
    ) {
        public PatternEvolution {
            prefix = prefix != null ? List.copyOf(prefix) : List.of();
            sizesPerDump = sizesPerDump != null ? List.copyOf(sizesPerDump) : List.of();
        }

        public String getTrend() {
            if (isGrowing) return "GROWING";
            if (isShrinking) return "SHRINKING";
            if (hasThreadMigration) return "MIGRATING";
            return "STABLE";
        }

        public double getGrowthRate() {
            if (firstSize == 0) return 0;
            return ((double) sizeChange / firstSize) * 100.0;
        }
    }

    /**
     * Analysis of pattern evolution across multiple dumps
     */
    public record PatternEvolutionAnalysis(
            List<PatternEvolution> evolvingPatterns,
            Map<Integer, List<SimilarStackGroup>> groupsByDump
    ) {
        public PatternEvolutionAnalysis {
            evolvingPatterns = evolvingPatterns != null ? List.copyOf(evolvingPatterns) : List.of();
            groupsByDump = groupsByDump != null ? Map.copyOf(groupsByDump) : Map.of();
        }

        public boolean hasEvolution() {
            return !evolvingPatterns.isEmpty();
        }

        public int getGrowingPatternCount() {
            return (int) evolvingPatterns.stream().filter(PatternEvolution::isGrowing).count();
        }

        public int getShrinkingPatternCount() {
            return (int) evolvingPatterns.stream().filter(PatternEvolution::isShrinking).count();
        }

        public int getMigratingPatternCount() {
            return (int) evolvingPatterns.stream().filter(PatternEvolution::hasThreadMigration).count();
        }
    }

    /**
     * A group of threads with similar stack trace prefix
     */
    public record SimilarStackGroup(
            List<StackFrame> prefix,
            List<ThreadInfo> threads,
            int dumpIndex,
            java.time.Instant timestamp
    ) {
        public SimilarStackGroup {
            prefix = prefix != null ? List.copyOf(prefix) : List.of();
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

        /**
         * Get the unique stack traces in this group
         */
        public Set<List<StackFrame>> getUniqueStacks() {
            Set<List<StackFrame>> unique = new LinkedHashSet<>();
            for (ThreadInfo thread : threads) {
                if (thread.stackTrace() != null) {
                    unique.add(thread.stackTrace());
                }
            }
            return unique;
        }
    }

    /**
     * JFR-based stack analysis results
     */
    public record JfrStackAnalysis(
            List<JfrAnalyzer.StackProfile> stackProfiles,
            List<JfrAnalyzer.MethodProfile> hottestMethods,
            List<JfrAnalyzer.ThreadProfile> hottestThreads,
            List<AnalysisResult.Finding> additionalFindings
    ) {
        public JfrStackAnalysis {
            stackProfiles = stackProfiles != null ? List.copyOf(stackProfiles) : List.of();
            hottestMethods = hottestMethods != null ? List.copyOf(hottestMethods) : List.of();
            hottestThreads = hottestThreads != null ? List.copyOf(hottestThreads) : List.of();
            additionalFindings = additionalFindings != null ? List.copyOf(additionalFindings) : List.of();
        }
    }

    /**
     * Result of similar stack analysis
     */
    public static class SimilarStackResult extends AnalysisResult {
        private final List<SimilarStackGroup> groups;
        private final PatternEvolutionAnalysis evolution;
        private final int prefixDepth;
        private final JfrStackAnalysis jfrAnalysis;

        public SimilarStackResult(Severity severity, List<Finding> findings,
                                  List<SimilarStackGroup> groups, int prefixDepth,
                                  JfrStackAnalysis jfrAnalysis) {
            this(severity, findings, groups, null, prefixDepth, jfrAnalysis);
        }

        public SimilarStackResult(Severity severity, List<Finding> findings,
                                  List<SimilarStackGroup> groups,
                                  PatternEvolutionAnalysis evolution,
                                  int prefixDepth,
                                  JfrStackAnalysis jfrAnalysis) {
            super(NAME, severity, findings);
            this.groups = List.copyOf(groups);
            this.evolution = evolution;
            this.prefixDepth = prefixDepth;
            this.jfrAnalysis = jfrAnalysis;
        }

        public List<SimilarStackGroup> getGroups() {
            return groups;
        }

        public PatternEvolutionAnalysis getEvolution() {
            return evolution;
        }

        public boolean hasEvolution() {
            return evolution != null && evolution.hasEvolution();
        }

        public int getPrefixDepth() {
            return prefixDepth;
        }

        public JfrStackAnalysis getJfrAnalysis() {
            return jfrAnalysis;
        }

        public boolean hasJfrData() {
            return jfrAnalysis != null;
        }

        public List<SimilarStackGroup> getGroupsForDump(int dumpIndex) {
            return groups.stream()
                    .filter(g -> g.dumpIndex() == dumpIndex)
                    .toList();
        }

        public int getTotalGroupedThreads() {
            return groups.stream()
                    .mapToInt(SimilarStackGroup::size)
                    .sum();
        }

        @Override
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            if (groups.isEmpty()) {
                sb.append("No similar stack groups found");
            } else {
                sb.append(String.format("%d groups with similar entry points (%d threads total)",
                        groups.size(), getTotalGroupedThreads()));
            }
            if (jfrAnalysis != null && !jfrAnalysis.hottestMethods().isEmpty()) {
                JfrAnalyzer.MethodProfile top = jfrAnalysis.hottestMethods().getFirst();
                sb.append(String.format("; JFR top: %s (%.1f%%)",
                        formatMethodName(top.method()), top.percentage()));
            }
            return sb.toString();
        }

        private String formatMethodName(String method) {
            if (method == null) return "unknown";
            int lastDot = method.lastIndexOf('.');
            if (lastDot > 0 && lastDot < method.length() - 1) {
                return method.substring(lastDot + 1);
            }
            return method;
        }

        public boolean hasGroups() {
            return !groups.isEmpty();
        }
    }
}