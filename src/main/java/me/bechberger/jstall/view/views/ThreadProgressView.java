package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.ProgressClassification;
import me.bechberger.jstall.analysis.analyzers.ThreadProgressAnalyzer;
import me.bechberger.jstall.model.StackFrame;
import me.bechberger.jstall.model.ThreadInfo;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for ThreadProgressAnalyzer results.
 * Shows thread progress classification and summary.
 */
public class ThreadProgressView extends HandlebarsViewRenderer {

    public ThreadProgressView() {
        super("thread-progress", "thread-progress", ThreadProgressAnalyzer.ProgressResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof ThreadProgressAnalyzer.ProgressResult progressResult) {
            ThreadProgressAnalyzer.ProgressSummary summary = progressResult.getProgressSummary();

            // Summary data
            context.put("total", summary.total());
            context.put("active", summary.active());
            context.put("noProgress", summary.noProgress());
            context.put("blocked", summary.blocked());
            context.put("stuck", summary.stuck());
            context.put("waiting", summary.waiting());
            context.put("problemCount", summary.problemCount());
            context.put("problemPercentage", summary.problemPercentage());

            // Check for stall indication
            boolean indicatesStall = summary.indicatesStall(90.0);
            context.put("indicatesStall", indicatesStall);

            // Multi-dump information
            int dumpCount = progressResult.getProgressMap().values().stream()
                    .mapToInt(p -> p.history().size())
                    .max()
                    .orElse(1);
            context.put("dumpCount", dumpCount);
            context.put("isMultiDump", dumpCount > 1);

            // Classification breakdown
            Map<ProgressClassification, List<Map<String, Object>>> byClassification = new LinkedHashMap<>();
            for (ThreadProgressAnalyzer.ThreadProgress progress : progressResult.getProgressMap().values()) {
                byClassification.computeIfAbsent(progress.classification(), k -> new ArrayList<>())
                        .add(formatThreadProgress(progress, options));
            }

            // Order classifications by importance
            List<Map<String, Object>> classifications = new ArrayList<>();
            for (ProgressClassification classification : ProgressClassification.values()) {
                List<Map<String, Object>> threads = byClassification.get(classification);
                if (threads != null && !threads.isEmpty()) {
                    Map<String, Object> classInfo = new LinkedHashMap<>();
                    classInfo.put("classification", classification);
                    classInfo.put("name", classification.name());
                    classInfo.put("isProblem", classification.isProblem());
                    classInfo.put("isHealthy", classification.isHealthy());
                    classInfo.put("count", threads.size());

                    // Only include thread details for problem classifications or if verbose
                    if (classification.isProblem() || options.isVerbose()) {
                        int maxToShow = Math.min(options.getMaxThreads(), threads.size());
                        classInfo.put("threads", threads.subList(0, maxToShow));
                        classInfo.put("hasMore", threads.size() > maxToShow);
                        classInfo.put("moreCount", threads.size() - maxToShow);
                    }

                    classifications.add(classInfo);
                }
            }
            context.put("classifications", classifications);

            // Problem threads for quick access
            List<Map<String, Object>> problems = new ArrayList<>();
            for (ThreadProgressAnalyzer.ThreadProgress progress : progressResult.getProblems()) {
                problems.add(formatThreadProgress(progress, options));
            }
            context.put("problems", problems);
            context.put("hasProblems", !problems.isEmpty());

            // Multi-dump chart data for HTML visualization
            // dumpCount is already set above in the context
            if (dumpCount > 1) {
                // Dump labels for charts
                List<Integer> dumpLabels = new ArrayList<>();
                for (int i = 1; i <= dumpCount; i++) {
                    dumpLabels.add(i);
                }
                context.put("dumpLabels", dumpLabels);

                // Calculate per-dump statistics for line charts
                List<Integer> activeHistory = new ArrayList<>();
                List<Integer> blockedHistory = new ArrayList<>();
                List<Integer> stuckHistory = new ArrayList<>();
                List<Integer> totalHistory = new ArrayList<>();

                // Aggregate state counts per dump
                Map<Integer, Map<String, Integer>> perDumpCounts = new LinkedHashMap<>();
                for (int d = 0; d < dumpCount; d++) {
                    perDumpCounts.put(d, new LinkedHashMap<>());
                    perDumpCounts.get(d).put("active", 0);
                    perDumpCounts.get(d).put("blocked", 0);
                    perDumpCounts.get(d).put("stuck", 0);
                    perDumpCounts.get(d).put("total", 0);
                }

                for (ThreadProgressAnalyzer.ThreadProgress progress : progressResult.getProgressMap().values()) {
                    List<ThreadInfo> history = progress.history();
                    for (int d = 0; d < Math.min(dumpCount, history.size()); d++) {
                        ThreadInfo ti = history.get(d);
                        if (ti != null) {
                            perDumpCounts.get(d).merge("total", 1, Integer::sum);
                            if (ti.state() == Thread.State.RUNNABLE) {
                                perDumpCounts.get(d).merge("active", 1, Integer::sum);
                            } else if (ti.state() == Thread.State.BLOCKED) {
                                perDumpCounts.get(d).merge("blocked", 1, Integer::sum);
                            } else if (ti.state() == Thread.State.WAITING || ti.state() == Thread.State.TIMED_WAITING) {
                                // Check if it's stuck based on classification
                                if (progress.classification() == ProgressClassification.STUCK) {
                                    perDumpCounts.get(d).merge("stuck", 1, Integer::sum);
                                }
                            }
                        }
                    }
                }

                for (int d = 0; d < dumpCount; d++) {
                    activeHistory.add(perDumpCounts.get(d).get("active"));
                    blockedHistory.add(perDumpCounts.get(d).get("blocked"));
                    stuckHistory.add(perDumpCounts.get(d).get("stuck"));
                    totalHistory.add(perDumpCounts.get(d).get("total"));
                }

                context.put("activeHistory", activeHistory);
                context.put("blockedHistory", blockedHistory);
                context.put("stuckHistory", stuckHistory);
                context.put("totalHistory", totalHistory);

                // Trend data for table
                List<Map<String, Object>> trendData = new ArrayList<>();
                trendData.add(createTrendRow("Total Threads", totalHistory));
                trendData.add(createTrendRow("Active", activeHistory));
                trendData.add(createTrendRow("Blocked", blockedHistory));
                trendData.add(createTrendRow("Stuck", stuckHistory));
                context.put("trendData", trendData);

                // Data for advanced visualizations (D3.js)
                // Thread history for timeline and heatmap
                List<Map<String, Object>> threadHistory = new ArrayList<>();
                for (ThreadProgressAnalyzer.ThreadProgress progress : progressResult.getProgressMap().values()) {
                    Map<String, Object> threadData = new LinkedHashMap<>();
                    threadData.put("name", progress.identifier().name());

                    List<String> states = new ArrayList<>();
                    for (ThreadInfo ti : progress.history()) {
                        if (ti != null && ti.state() != null) {
                            states.add(ti.state().name());
                        } else {
                            states.add("TERMINATED");
                        }
                    }
                    threadData.put("stateHistory", states);
                    threadHistory.add(threadData);
                }
                context.put("threadHistory", threadHistory);

                // State transitions for Sankey diagram
                List<Map<String, Object>> stateTransitions = new ArrayList<>();
                Map<String, Integer> transitionCounts = new LinkedHashMap<>();

                for (ThreadProgressAnalyzer.ThreadProgress progress : progressResult.getProgressMap().values()) {
                    List<ThreadInfo> history = progress.history();
                    for (int i = 0; i < history.size() - 1; i++) {
                        ThreadInfo from = history.get(i);
                        ThreadInfo to = history.get(i + 1);

                        if (from != null && to != null && from.state() != null && to.state() != null) {
                            String fromState = from.state().name();
                            String toState = to.state().name();
                            String key = i + ":" + fromState + "->" + (i + 1) + ":" + toState;
                            transitionCounts.merge(key, 1, Integer::sum);
                        }
                    }
                }

                for (Map.Entry<String, Integer> entry : transitionCounts.entrySet()) {
                    String[] parts = entry.getKey().split(":");
                    int fromDump = Integer.parseInt(parts[0]);
                    String[] states = parts[1].split("->");
                    int toDump = Integer.parseInt(states[1]);
                    String fromState = states[0];
                    String toState = states[1].substring(String.valueOf(toDump).length());

                    Map<String, Object> transition = new LinkedHashMap<>();
                    transition.put("fromDump", fromDump);
                    transition.put("toDump", toDump);
                    transition.put("fromState", fromState);
                    transition.put("toState", toState);
                    transition.put("count", entry.getValue());
                    stateTransitions.add(transition);
                }
                context.put("stateTransitions", stateTransitions);
            }
        }

        return context;
    }

    private Map<String, Object> createTrendRow(String metric, List<Integer> history) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metric", metric);
        int first = history.isEmpty() ? 0 : history.get(0);
        int last = history.isEmpty() ? 0 : history.get(history.size() - 1);
        row.put("first", first);
        row.put("last", last);

        int change = last - first;
        if (change > 0) {
            row.put("changeDisplay", "+" + change + " ↑");
            row.put("changeClass", "change-up");
        } else if (change < 0) {
            row.put("changeDisplay", change + " ↓");
            row.put("changeClass", "change-down");
        } else {
            row.put("changeDisplay", "0 →");
            row.put("changeClass", "change-same");
        }
        return row;
    }    private Map<String, Object> formatThreadProgress(ThreadProgressAnalyzer.ThreadProgress progress,
                                                      OutputOptions options) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("classification", progress.classification());
        info.put("classificationName", progress.classification().name());
        info.put("isProblem", progress.isProblem());

        // Thread identifier info
        info.put("threadName", progress.identifier().name());
        if (options.isShowThreadIds()) {
            info.put("nativeId", progress.identifier().nativeId());
            info.put("javaId", progress.identifier().threadId());
        }

        // Multi-dump: state history
        List<ThreadInfo> history = progress.history();
        info.put("historySize", history.size());
        info.put("isMultiDump", history.size() > 1);

        if (history.size() > 1) {
            // Format state transitions for multi-dump view
            List<String> stateHistory = new ArrayList<>();
            List<Map<String, Object>> dumpHistory = new ArrayList<>();

            for (int i = 0; i < history.size(); i++) {
                ThreadInfo ti = history.get(i);
                Map<String, Object> dumpInfo = new LinkedHashMap<>();
                dumpInfo.put("dumpIndex", i + 1);

                if (ti != null) {
                    stateHistory.add(ti.state() != null ? ti.state().name() : "?");
                    dumpInfo.put("present", true);
                    dumpInfo.put("state", ti.state());
                    dumpInfo.put("cpuTimeMs", ti.cpuTimeMs());
                } else {
                    stateHistory.add("-");
                    dumpInfo.put("present", false);
                }
                dumpHistory.add(dumpInfo);
            }

            info.put("stateHistory", stateHistory);
            info.put("stateHistoryString", String.join(" → ", stateHistory));
            info.put("dumpHistory", dumpHistory);

            // Check for state changes
            long uniqueStates = stateHistory.stream().filter(s -> !s.equals("-")).distinct().count();
            info.put("hasStateChanges", uniqueStates > 1);
        }

        // Get the latest thread info
        ThreadInfo latest = findLastNonNull(progress.history());
        if (latest != null) {
            info.put("state", latest.state());
            info.put("daemon", latest.daemon());

            if (options.isShowTimestamps()) {
                info.put("cpuTimeMs", latest.cpuTimeMs());
                info.put("elapsedTimeMs", latest.elapsedTimeMs());
            }

            // Include abbreviated stack if verbose
            if (options.isVerbose() && latest.stackTrace() != null && !latest.stackTrace().isEmpty()) {
                List<String> stackLines = new ArrayList<>();
                int maxDepth = Math.min(options.getMaxStackDepth(), latest.stackTrace().size());
                for (int i = 0; i < maxDepth; i++) {
                    StackFrame frame = latest.stackTrace().get(i);
                    stackLines.add(formatStackFrame(frame));
                }
                if (latest.stackTrace().size() > maxDepth) {
                    stackLines.add("... " + (latest.stackTrace().size() - maxDepth) + " more");
                }
                info.put("stackTrace", stackLines);
            }
        }

        return info;
    }

    private ThreadInfo findLastNonNull(List<ThreadInfo> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) != null) {
                return history.get(i);
            }
        }
        return null;
    }

    private String formatStackFrame(StackFrame frame) {
        StringBuilder sb = new StringBuilder();
        sb.append("at ");
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
        }
        return sb.toString();
    }
}