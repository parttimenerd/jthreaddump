package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.SimilarStackAnalyzer;
import me.bechberger.jstall.model.StackFrame;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for SimilarStackAnalyzer results.
 * Shows threads grouped by similar stack trace prefixes (entry points).
 */
public class SimilarStackView extends HandlebarsViewRenderer {

    public SimilarStackView() {
        super("similar-stacks", "similar-stacks", SimilarStackAnalyzer.SimilarStackResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof SimilarStackAnalyzer.SimilarStackResult similarResult) {
            // Summary
            context.put("groupCount", similarResult.getGroups().size());
            context.put("totalGroupedThreads", similarResult.getTotalGroupedThreads());
            context.put("prefixDepth", similarResult.getPrefixDepth());
            context.put("hasGroups", !similarResult.getGroups().isEmpty());

            // Format groups
            List<Map<String, Object>> groups = new ArrayList<>();
            int index = 1;
            for (SimilarStackAnalyzer.SimilarStackGroup group : similarResult.getGroups()) {
                if (index > options.getMaxThreads()) break;

                Map<String, Object> groupInfo = new LinkedHashMap<>();
                groupInfo.put("index", index++);
                groupInfo.put("threadCount", group.size());
                groupInfo.put("uniqueStackCount", group.getUniqueStacks().size());
                groupInfo.put("dumpIndex", group.dumpIndex() + 1);

                // Entry point (formatted prefix)
                String entryPoint = formatEntryPoint(group.prefix());
                groupInfo.put("entryPoint", entryPoint);

                // Prefix frames
                List<String> prefixFrames = new ArrayList<>();
                for (StackFrame frame : group.prefix()) {
                    prefixFrames.add(formatStackFrame(frame));
                }
                groupInfo.put("prefixFrames", prefixFrames);

                // Thread names
                List<String> threadNames = group.getThreadNames();
                int maxNames = Math.min(10, threadNames.size());
                groupInfo.put("displayedThreadNames", threadNames.subList(0, maxNames));
                groupInfo.put("hasMoreThreads", threadNames.size() > maxNames);
                groupInfo.put("moreThreadCount", threadNames.size() - maxNames);

                groups.add(groupInfo);
            }
            context.put("groups", groups);
            context.put("hasMoreGroups", similarResult.getGroups().size() > options.getMaxThreads());
            context.put("moreGroupCount", similarResult.getGroups().size() - groups.size());

            // JFR data if available
            if (similarResult.hasJfrData()) {
                SimilarStackAnalyzer.JfrStackAnalysis jfr = similarResult.getJfrAnalysis();
                Map<String, Object> jfrData = new LinkedHashMap<>();

                // Hottest methods
                List<Map<String, Object>> methods = new ArrayList<>();
                for (var m : jfr.hottestMethods().stream().limit(5).toList()) {
                    Map<String, Object> method = new LinkedHashMap<>();
                    method.put("name", formatMethodName(m.method()));
                    method.put("percentage", String.format(Locale.US, "%.1f", m.percentage()));
                    method.put("sampleCount", m.sampleCount());
                    methods.add(method);
                }
                jfrData.put("hottestMethods", methods);

                // Hottest threads
                List<Map<String, Object>> threads = new ArrayList<>();
                for (var t : jfr.hottestThreads().stream().limit(5).toList()) {
                    Map<String, Object> thread = new LinkedHashMap<>();
                    thread.put("name", t.threadName());
                    thread.put("percentage", String.format(Locale.US, "%.1f", t.percentage()));
                    thread.put("sampleCount", t.sampleCount());
                    threads.add(thread);
                }
                jfrData.put("hottestThreads", threads);

                context.put("jfrAnalysis", jfrData);
                context.put("hasJfrData", true);
            } else {
                context.put("hasJfrData", false);
            }

            // Pattern evolution data for multi-dump
            SimilarStackAnalyzer.PatternEvolutionAnalysis evolution = similarResult.getEvolution();
            if (evolution != null && evolution.hasEvolution()) {
                context.put("hasEvolution", true);
                context.put("isMultiDump", true);

                // Summary stats
                context.put("growingPatternCount", evolution.getGrowingPatternCount());
                context.put("shrinkingPatternCount", evolution.getShrinkingPatternCount());
                context.put("migratingPatternCount", evolution.getMigratingPatternCount());

                // Format evolving patterns
                List<Map<String, Object>> evolvingPatterns = new ArrayList<>();
                for (SimilarStackAnalyzer.PatternEvolution evo : evolution.evolvingPatterns()) {
                    Map<String, Object> evoInfo = new LinkedHashMap<>();
                    evoInfo.put("entryPoint", evo.entryPoint());
                    evoInfo.put("firstSize", evo.firstSize());
                    evoInfo.put("lastSize", evo.lastSize());
                    evoInfo.put("sizeChange", evo.sizeChange());
                    evoInfo.put("sizeChangeDisplay", formatSizeChange(evo.sizeChange()));
                    evoInfo.put("firstDump", evo.firstSeenDump() + 1);
                    evoInfo.put("lastDump", evo.lastSeenDump() + 1);
                    evoInfo.put("occurrences", evo.occurrences());
                    evoInfo.put("trend", evo.getTrend());
                    evoInfo.put("isGrowing", evo.isGrowing());
                    evoInfo.put("isShrinking", evo.isShrinking());
                    evoInfo.put("hasMigration", evo.hasThreadMigration());
                    evoInfo.put("stabilityScore", String.format(Locale.US, "%.1f", evo.stabilityScore() * 100));
                    evoInfo.put("growthRate", String.format(Locale.US, "%.1f", evo.getGrowthRate()));

                    // Stability level
                    if (evo.stabilityScore() >= 0.8) {
                        evoInfo.put("stabilityLevel", "HIGH");
                    } else if (evo.stabilityScore() >= 0.5) {
                        evoInfo.put("stabilityLevel", "MEDIUM");
                    } else {
                        evoInfo.put("stabilityLevel", "LOW");
                    }

                    evolvingPatterns.add(evoInfo);
                }
                context.put("evolvingPatterns", evolvingPatterns);

                // Per-dump counts for timeline chart
                Map<Integer, List<SimilarStackAnalyzer.SimilarStackGroup>> byDump = evolution.groupsByDump();
                int maxDump = byDump.keySet().stream().max(Integer::compareTo).orElse(0);
                List<Integer> dumpLabels = new ArrayList<>();
                List<Integer> patternCounts = new ArrayList<>();
                List<Integer> totalThreadCounts = new ArrayList<>();

                for (int i = 0; i <= maxDump; i++) {
                    dumpLabels.add(i + 1);
                    List<SimilarStackAnalyzer.SimilarStackGroup> dumpGroups = byDump.getOrDefault(i, List.of());
                    patternCounts.add(dumpGroups.size());
                    int totalThreads = dumpGroups.stream().mapToInt(SimilarStackAnalyzer.SimilarStackGroup::size).sum();
                    totalThreadCounts.add(totalThreads);
                }

                context.put("dumpLabels", dumpLabels);
                context.put("patternCounts", patternCounts);
                context.put("evolutionThreadCounts", totalThreadCounts);
            } else {
                context.put("hasEvolution", false);
                context.put("isMultiDump", false);
            }

            // Chart data
            List<String> entryPoints = groups.stream()
                    .map(g -> truncate((String) g.get("entryPoint"), 30))
                    .toList();
            List<Integer> threadCounts = groups.stream()
                    .map(g -> (Integer) g.get("threadCount"))
                    .toList();
            context.put("entryPointLabels", entryPoints);
            context.put("threadCountData", threadCounts);
        }

        return context;
    }

    private String formatSizeChange(int change) {
        if (change > 0) return "+" + change + " ↑";
        if (change < 0) return change + " ↓";
        return "→";
    }

    private String formatEntryPoint(List<StackFrame> prefix) {
        if (prefix.isEmpty()) return "unknown";
        StackFrame entry = prefix.getLast();
        String className = entry.className();
        if (className != null) {
            int lastDot = className.lastIndexOf('.');
            if (lastDot >= 0) className = className.substring(lastDot + 1);
        } else {
            className = "?";
        }
        return className + "." + (entry.methodName() != null ? entry.methodName() : "?");
    }

    private String formatStackFrame(StackFrame frame) {
        StringBuilder sb = new StringBuilder();
        if (frame.className() != null) {
            sb.append(frame.className());
            if (frame.methodName() != null) {
                sb.append(".").append(frame.methodName());
            }
        }
        if (frame.fileName() != null) {
            sb.append("(").append(frame.fileName());
            if (frame.lineNumber() != null && frame.lineNumber() > 0) {
                sb.append(":").append(frame.lineNumber());
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private String formatMethodName(String method) {
        if (method == null) return "unknown";
        int lastDot = method.lastIndexOf('.');
        if (lastDot > 0) {
            int prevDot = method.lastIndexOf('.', lastDot - 1);
            if (prevDot > 0) return "..." + method.substring(prevDot + 1);
        }
        return method;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
}