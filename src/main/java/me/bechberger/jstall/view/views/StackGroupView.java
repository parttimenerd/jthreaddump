package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.StackGroupAnalyzer;
import me.bechberger.jstall.model.StackFrame;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for StackGroupAnalyzer results.
 * Shows groups of threads with identical stack traces.
 */
public class StackGroupView extends HandlebarsViewRenderer {

    public StackGroupView() {
        super("stack-groups", "stack-groups", StackGroupAnalyzer.StackGroupResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof StackGroupAnalyzer.StackGroupResult groupResult) {
            context.put("groupCount", groupResult.getGroups().size());
            context.put("totalGroupedThreads", groupResult.getTotalGroupedThreads());
            context.put("minGroupSize", groupResult.getMinGroupSize());
            context.put("hasGroups", !groupResult.getGroups().isEmpty());

            // Format groups
            List<Map<String, Object>> groups = new ArrayList<>();
            for (int i = 0; i < groupResult.getGroups().size(); i++) {
                StackGroupAnalyzer.StackGroup group = groupResult.getGroups().get(i);
                groups.add(formatStackGroup(group, i + 1, options));
            }
            context.put("groups", groups);

            // Largest group
            groupResult.getLargestGroup().ifPresent(largest -> {
                context.put("largestGroupSize", largest.size());
                largest.getTopFrame().ifPresent(frame ->
                    context.put("largestGroupTopFrame", formatShortFrame(frame)));
            });

            // Evolution data for multi-dump
            StackGroupAnalyzer.GroupEvolutionAnalysis evolution = groupResult.getEvolution();
            if (evolution != null && evolution.hasEvolution()) {
                context.put("hasEvolution", true);
                context.put("isMultiDump", true);

                // Summary stats
                context.put("growingGroupCount", evolution.getGrowingGroupCount());
                context.put("shrinkingGroupCount", evolution.getShrinkingGroupCount());
                context.put("stuckGroupCount", evolution.getStuckGroupCount());

                // Format evolving groups
                List<Map<String, Object>> evolvingGroups = new ArrayList<>();
                for (StackGroupAnalyzer.GroupEvolution evo : evolution.evolvingGroups()) {
                    Map<String, Object> evoInfo = new LinkedHashMap<>();
                    evoInfo.put("topFrame", evo.topFrame());
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
                    evoInfo.put("isStuck", evo.isStuck());
                    evoInfo.put("isPersistent", evo.isPersistent());
                    evoInfo.put("growthRate", String.format(Locale.US, "%.1f", evo.getGrowthRate()));

                    // Size history for charts
                    evoInfo.put("sizeHistory", evo.sizesPerDump());

                    evolvingGroups.add(evoInfo);
                }
                context.put("evolvingGroups", evolvingGroups);

                // Per-dump counts for timeline chart
                Map<Integer, List<StackGroupAnalyzer.StackGroup>> byDump = evolution.groupsByDump();
                int maxDump = byDump.keySet().stream().max(Integer::compareTo).orElse(0);
                List<Integer> dumpLabels = new ArrayList<>();
                List<Integer> groupCounts = new ArrayList<>();
                List<Integer> totalThreadCounts = new ArrayList<>();

                for (int i = 0; i <= maxDump; i++) {
                    dumpLabels.add(i + 1);
                    List<StackGroupAnalyzer.StackGroup> dumpGroups = byDump.getOrDefault(i, List.of());
                    groupCounts.add(dumpGroups.size());
                    int threadCount = dumpGroups.stream().mapToInt(StackGroupAnalyzer.StackGroup::size).sum();
                    totalThreadCounts.add(threadCount);
                }

                context.put("dumpLabels", dumpLabels);
                context.put("groupCounts", groupCounts);
                context.put("totalThreadCounts", totalThreadCounts);
            } else {
                context.put("hasEvolution", false);
                context.put("isMultiDump", false);
            }
        }

        return context;
    }

    private String formatSizeChange(int change) {
        if (change > 0) return "+" + change + " ↑";
        if (change < 0) return change + " ↓";
        return "→";
    }

    private Map<String, Object> formatStackGroup(StackGroupAnalyzer.StackGroup group, int index,
                                                   OutputOptions options) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("index", index);
        info.put("threadCount", group.size());
        info.put("dumpIndex", group.dumpIndex());
        info.put("threadNames", group.getThreadNames());

        // Top frame summary
        group.getTopFrame().ifPresent(frame -> {
            info.put("topFrame", formatShortFrame(frame));
            info.put("topFrameFull", formatFullFrame(frame));
        });

        // Stack trace
        List<String> stackLines = new ArrayList<>();
        int maxDepth = Math.min(options.getMaxStackDepth(), group.stack().size());
        for (int i = 0; i < maxDepth; i++) {
            StackFrame frame = group.stack().get(i);
            stackLines.add(formatFullFrame(frame));
        }
        if (group.stack().size() > maxDepth) {
            stackLines.add("... " + (group.stack().size() - maxDepth) + " more frames");
        }
        info.put("stackTrace", stackLines);
        info.put("stackDepth", group.stack().size());

        // Thread names (limited)
        List<String> names = group.getThreadNames();
        int maxThreadsToShow = Math.min(10, names.size());
        info.put("displayedThreadNames", names.subList(0, maxThreadsToShow));
        info.put("hasMoreThreads", names.size() > maxThreadsToShow);
        info.put("moreThreadCount", names.size() - maxThreadsToShow);

        return info;
    }

    private String formatShortFrame(StackFrame frame) {
        if (frame.className() != null && frame.methodName() != null) {
            String className = frame.className();
            int lastDot = className.lastIndexOf('.');
            if (lastDot >= 0) {
                className = className.substring(lastDot + 1);
            }
            return className + "." + frame.methodName();
        }
        if (frame.methodName() != null) {
            return frame.methodName();
        }
        return frame.toString();
    }

    private String formatFullFrame(StackFrame frame) {
        StringBuilder sb = new StringBuilder("at ");
        if (frame.className() != null) {
            sb.append(frame.className());
            if (frame.methodName() != null) {
                sb.append(".").append(frame.methodName());
            }
        } else if (frame.methodName() != null) {
            sb.append(frame.methodName());
        }
        if (frame.fileName() != null) {
            sb.append("(").append(frame.fileName());
            if (frame.lineNumber() != null && frame.lineNumber() > 0) {
                sb.append(":").append(frame.lineNumber());
            }
            sb.append(")");
        } else if (frame.nativeMethod() != null && frame.nativeMethod()) {
            sb.append("(Native Method)");
        }
        return sb.toString();
    }
}