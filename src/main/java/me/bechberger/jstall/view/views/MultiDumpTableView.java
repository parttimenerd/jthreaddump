package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.ProgressClassification;
import me.bechberger.jstall.analysis.analyzers.ThreadProgressAnalyzer;
import me.bechberger.jstall.analysis.AnalysisContext.ThreadIdentifier;
import me.bechberger.jstall.model.ThreadInfo;
import me.bechberger.jstall.view.AbstractViewRenderer;
import me.bechberger.jstall.view.OutputFormat;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

/**
 * View renderer that displays multi-dump thread analysis in a tabular format.
 * Shows thread state evolution, CPU progress, and state changes across dumps.
 */
public class MultiDumpTableView extends AbstractViewRenderer {

    public MultiDumpTableView() {
        super("multi-dump-table");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends AnalysisResult>[] getResultTypes() {
        return new Class[] { ThreadProgressAnalyzer.ProgressResult.class };
    }

    @Override
    protected String renderText(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        if (!(result instanceof ThreadProgressAnalyzer.ProgressResult progressResult)) {
            return result.getSummary();
        }

        StringBuilder sb = new StringBuilder();
        boolean color = options.isColorEnabled();

        // Header
        sb.append(formatLine("═", 100, color)).append("\n");
        sb.append(centerText("MULTI-DUMP THREAD ANALYSIS", 100)).append("\n");
        sb.append(formatLine("═", 100, color)).append("\n\n");

        // Summary stats
        ThreadProgressAnalyzer.ProgressSummary summary = progressResult.getProgressSummary();
        sb.append("Total Threads: ").append(summary.total()).append("\n");
        sb.append("Problem Rate: ").append(String.format(Locale.US, "%.1f%%", summary.problemPercentage())).append("\n\n");

        // Thread state table
        sb.append(formatLine("─", 100, color)).append("\n");
        sb.append("THREAD STATE EVOLUTION\n");
        sb.append(formatLine("─", 100, color)).append("\n\n");

        // Build table headers
        Map<ThreadIdentifier, ThreadProgressAnalyzer.ThreadProgress> progressMap = progressResult.getProgressMap();

        // Find max dump count from any thread history
        int maxDumps = progressMap.values().stream()
                .mapToInt(p -> p.history().size())
                .max()
                .orElse(1);

        // Table header
        sb.append(String.format("%-30s | %-15s", "Thread Name", "Classification"));
        for (int i = 0; i < maxDumps; i++) {
            sb.append(String.format(" | Dump %d", i + 1));
        }
        sb.append("\n");
        sb.append(formatLine("─", 30 + 17 + maxDumps * 10, color)).append("\n");

        // Sort threads: problems first, then by name
        List<Map.Entry<ThreadIdentifier, ThreadProgressAnalyzer.ThreadProgress>> sortedEntries =
                progressMap.entrySet().stream()
                        .sorted((a, b) -> {
                            // Problems first
                            if (a.getValue().isProblem() != b.getValue().isProblem()) {
                                return a.getValue().isProblem() ? -1 : 1;
                            }
                            // Then by name
                            String nameA = a.getKey().name() != null ? a.getKey().name() : "";
                            String nameB = b.getKey().name() != null ? b.getKey().name() : "";
                            return nameA.compareTo(nameB);
                        })
                        .limit(options.getMaxThreads())
                        .toList();

        for (Map.Entry<ThreadIdentifier, ThreadProgressAnalyzer.ThreadProgress> entry : sortedEntries) {
            ThreadIdentifier id = entry.getKey();
            ThreadProgressAnalyzer.ThreadProgress progress = entry.getValue();

            String name = truncate(id.name() != null ? id.name() : "?", 28);
            String classification = progress.classification().name();

            if (color) {
                classification = colorizeClassification(progress.classification(), classification);
            }

            sb.append(String.format("%-30s | %-15s", name, classification));

            // State for each dump
            for (int i = 0; i < progress.history().size(); i++) {
                ThreadInfo ti = progress.history().get(i);
                String state = ti != null ? formatShortState(ti.state()) : "-";
                if (color && ti != null) {
                    state = colorizeState(ti.state(), state);
                }
                sb.append(String.format(" | %-6s", state));
            }
            sb.append("\n");
        }

        if (progressMap.size() > options.getMaxThreads()) {
            sb.append(String.format("... and %d more threads\n", progressMap.size() - options.getMaxThreads()));
        }

        // Problem threads details
        List<ThreadProgressAnalyzer.ThreadProgress> problems = progressResult.getProblems();
        if (!problems.isEmpty() && options.isVerbose()) {
            sb.append("\n");
            sb.append(formatLine("─", 100, color)).append("\n");
            sb.append("PROBLEM THREAD DETAILS\n");
            sb.append(formatLine("─", 100, color)).append("\n\n");

            for (ThreadProgressAnalyzer.ThreadProgress problem : problems.stream().limit(10).toList()) {
                sb.append("• ").append(problem.identifier().name());
                sb.append(" [").append(problem.classification()).append("]\n");

                // Show state changes
                sb.append("  States: ");
                List<String> states = new ArrayList<>();
                for (ThreadInfo ti : problem.history()) {
                    if (ti != null) {
                        states.add(ti.state() != null ? ti.state().name() : "?");
                    } else {
                        states.add("-");
                    }
                }
                sb.append(String.join(" → ", states)).append("\n");

                // Show CPU time changes if available
                List<Long> cpuTimes = problem.history().stream()
                        .map(ti -> ti != null ? ti.cpuTimeMs() : null)
                        .toList();
                if (cpuTimes.stream().anyMatch(Objects::nonNull)) {
                    sb.append("  CPU: ");
                    List<String> cpuStrings = cpuTimes.stream()
                            .map(cpu -> cpu != null ? cpu + "ms" : "-")
                            .toList();
                    sb.append(String.join(" → ", cpuStrings)).append("\n");
                }
                sb.append("\n");
            }
        }

        // Classification summary table
        sb.append(formatLine("─", 100, color)).append("\n");
        sb.append("CLASSIFICATION SUMMARY\n");
        sb.append(formatLine("─", 100, color)).append("\n\n");

        Map<ProgressClassification, Long> classificationCounts = progressMap.values().stream()
                .collect(Collectors.groupingBy(ThreadProgressAnalyzer.ThreadProgress::classification, Collectors.counting()));

        sb.append(String.format("%-25s | %s\n", "Classification", "Count"));
        sb.append(formatLine("─", 35, color)).append("\n");

        for (ProgressClassification classification : ProgressClassification.values()) {
            long count = classificationCounts.getOrDefault(classification, 0L);
            if (count > 0) {
                String classStr = classification.name();
                if (color) {
                    classStr = colorizeClassification(classification, classStr);
                }
                sb.append(String.format("%-25s | %d\n", classStr, count));
            }
        }

        sb.append("\n");
        sb.append(formatLine("═", 100, color)).append("\n");

        return sb.toString();
    }

    @Override
    protected String renderHtml(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        if (!(result instanceof ThreadProgressAnalyzer.ProgressResult progressResult)) {
            return "<p>" + result.getSummary() + "</p>";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<title>Multi-Dump Thread Analysis</title>\n");
        sb.append("<style>\n");
        appendHtmlStyles(sb);
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<h1>Multi-Dump Thread Analysis</h1>\n");

        // Summary
        ThreadProgressAnalyzer.ProgressSummary summary = progressResult.getProgressSummary();
        sb.append("<div class=\"summary-cards\">\n");
        sb.append(summaryCard("Total Threads", String.valueOf(summary.total()), ""));
        sb.append(summaryCard("Active", String.valueOf(summary.active()), "ok"));
        sb.append(summaryCard("Blocked", String.valueOf(summary.blocked()), summary.blocked() > 0 ? "warning" : ""));
        sb.append(summaryCard("Stuck", String.valueOf(summary.stuck()), summary.stuck() > 0 ? "error" : ""));
        sb.append(summaryCard("Problem Rate", String.format(Locale.US, "%.1f%%", summary.problemPercentage()),
                summary.problemPercentage() > 50 ? "error" : summary.problemPercentage() > 25 ? "warning" : ""));
        sb.append("</div>\n");

        // Thread state evolution table
        sb.append("<h2>Thread State Evolution</h2>\n");
        sb.append("<div class=\"table-container\">\n");
        sb.append("<table class=\"thread-table\">\n<thead>\n<tr>\n");
        sb.append("<th>Thread Name</th><th>Classification</th>");

        Map<ThreadIdentifier, ThreadProgressAnalyzer.ThreadProgress> progressMap = progressResult.getProgressMap();
        int maxDumps = progressMap.values().stream()
                .mapToInt(p -> p.history().size())
                .max()
                .orElse(1);

        for (int i = 0; i < maxDumps; i++) {
            sb.append("<th>Dump ").append(i + 1).append("</th>");
        }
        sb.append("</tr>\n</thead>\n<tbody>\n");

        List<Map.Entry<ThreadIdentifier, ThreadProgressAnalyzer.ThreadProgress>> sortedEntries =
                progressMap.entrySet().stream()
                        .sorted((a, b) -> {
                            if (a.getValue().isProblem() != b.getValue().isProblem()) {
                                return a.getValue().isProblem() ? -1 : 1;
                            }
                            String nameA = a.getKey().name() != null ? a.getKey().name() : "";
                            String nameB = b.getKey().name() != null ? b.getKey().name() : "";
                            return nameA.compareTo(nameB);
                        })
                        .limit(options.getMaxThreads())
                        .toList();

        for (Map.Entry<ThreadIdentifier, ThreadProgressAnalyzer.ThreadProgress> entry : sortedEntries) {
            ThreadIdentifier id = entry.getKey();
            ThreadProgressAnalyzer.ThreadProgress progress = entry.getValue();

            String rowClass = progress.isProblem() ? " class=\"problem\"" : "";
            sb.append("<tr").append(rowClass).append(">\n");
            sb.append("<td>").append(escapeHtml(id.name())).append("</td>\n");
            sb.append("<td class=\"classification-").append(progress.classification().name().toLowerCase())
              .append("\">").append(progress.classification()).append("</td>\n");

            for (int i = 0; i < progress.history().size(); i++) {
                ThreadInfo ti = progress.history().get(i);
                if (ti != null && ti.state() != null) {
                    sb.append("<td class=\"state-").append(ti.state().name().toLowerCase()).append("\">")
                      .append(formatShortState(ti.state())).append("</td>\n");
                } else {
                    sb.append("<td class=\"state-absent\">-</td>\n");
                }
            }
            sb.append("</tr>\n");
        }

        sb.append("</tbody>\n</table>\n</div>\n");

        // Classification summary
        sb.append("<h2>Classification Summary</h2>\n");
        sb.append("<table class=\"summary-table\">\n<thead>\n<tr><th>Classification</th><th>Count</th></tr>\n</thead>\n<tbody>\n");

        Map<ProgressClassification, Long> classificationCounts = progressMap.values().stream()
                .collect(Collectors.groupingBy(ThreadProgressAnalyzer.ThreadProgress::classification, Collectors.counting()));

        for (ProgressClassification classification : ProgressClassification.values()) {
            long count = classificationCounts.getOrDefault(classification, 0L);
            if (count > 0) {
                sb.append("<tr><td class=\"classification-").append(classification.name().toLowerCase())
                  .append("\">").append(classification).append("</td><td>").append(count).append("</td></tr>\n");
            }
        }
        sb.append("</tbody>\n</table>\n");

        sb.append("<footer><p>Generated at ").append(result.getTimestamp()).append("</p></footer>\n");
        sb.append("</body>\n</html>");

        return sb.toString();
    }

    private void appendHtmlStyles(StringBuilder sb) {
        sb.append("""
            body { font-family: system-ui, sans-serif; margin: 20px; background: #f5f5f5; }
            h1, h2 { color: #333; }
            .summary-cards { display: flex; gap: 20px; margin: 20px 0; flex-wrap: wrap; }
            .summary-card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); min-width: 120px; text-align: center; }
            .summary-card .value { font-size: 2rem; font-weight: bold; }
            .summary-card .label { color: #666; }
            .summary-card.ok .value { color: #28a745; }
            .summary-card.warning .value { color: #ffc107; }
            .summary-card.error .value { color: #dc3545; }
            .table-container { overflow-x: auto; }
            table { border-collapse: collapse; width: 100%; background: white; }
            th, td { padding: 8px 12px; text-align: left; border: 1px solid #ddd; }
            th { background: #f8f9fa; font-weight: bold; }
            tr.problem { background: #fff3cd; }
            .state-runnable { background: #d4edda; color: #155724; }
            .state-blocked { background: #f8d7da; color: #721c24; }
            .state-waiting, .state-timed_waiting { background: #fff3cd; color: #856404; }
            .state-new { background: #cce5ff; color: #004085; }
            .state-terminated { background: #e9ecef; color: #6c757d; }
            .state-absent { color: #aaa; }
            .classification-active { color: #28a745; }
            .classification-stuck, .classification-runnable_no_progress { color: #dc3545; font-weight: bold; }
            .classification-blocked_on_lock { color: #dc3545; }
            .classification-waiting_expected, .classification-timed_waiting_expected { color: #ffc107; }
            .summary-table { max-width: 400px; }
            footer { margin-top: 30px; color: #666; font-size: 0.9rem; }
            """);
    }

    private String summaryCard(String label, String value, String type) {
        String classAttr = type.isEmpty() ? "" : " " + type;
        return String.format("<div class=\"summary-card%s\"><div class=\"value\">%s</div><div class=\"label\">%s</div></div>\n",
                classAttr, value, label);
    }

    private String formatLine(String ch, int width, boolean color) {
        return ch.repeat(width);
    }

    private String centerText(String text, int width) {
        int padding = (width - text.length()) / 2;
        return " ".repeat(Math.max(0, padding)) + text;
    }

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 2) + "..";
    }

    private String formatShortState(Thread.State state) {
        if (state == null) return "?";
        return switch (state) {
            case RUNNABLE -> "RUN";
            case BLOCKED -> "BLK";
            case WAITING -> "WAIT";
            case TIMED_WAITING -> "TWAIT";
            case NEW -> "NEW";
            case TERMINATED -> "TERM";
        };
    }

    private String colorizeState(Thread.State state, String text) {
        return switch (state) {
            case RUNNABLE -> "\u001B[32m" + text + "\u001B[0m"; // Green
            case BLOCKED -> "\u001B[31m" + text + "\u001B[0m"; // Red
            case WAITING, TIMED_WAITING -> "\u001B[33m" + text + "\u001B[0m"; // Yellow
            case NEW -> "\u001B[36m" + text + "\u001B[0m"; // Cyan
            case TERMINATED -> "\u001B[90m" + text + "\u001B[0m"; // Gray
        };
    }

    private String colorizeClassification(ProgressClassification classification, String text) {
        return switch (classification) {
            case ACTIVE -> "\u001B[32m" + text + "\u001B[0m";
            case STUCK, RUNNABLE_NO_PROGRESS, BLOCKED_ON_LOCK -> "\u001B[31m" + text + "\u001B[0m";
            case WAITING_EXPECTED, TIMED_WAITING_EXPECTED -> "\u001B[33m" + text + "\u001B[0m";
            case NEW, RESTARTED -> "\u001B[36m" + text + "\u001B[0m";
            case TERMINATED, IGNORED -> "\u001B[90m" + text + "\u001B[0m";
            case UNKNOWN -> text;
        };
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}