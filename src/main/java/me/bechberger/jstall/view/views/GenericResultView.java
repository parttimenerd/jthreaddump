package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.model.StackFrame;
import me.bechberger.jstall.model.ThreadInfo;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Generic view renderer for any AnalysisResult.
 * Provides a basic representation of findings and summary.
 */
public class GenericResultView extends HandlebarsViewRenderer {

    public GenericResultView() {
        super("generic", "generic", AnalysisResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        // Format findings with additional details
        List<Map<String, Object>> formattedFindings = new ArrayList<>();
        for (AnalysisResult.Finding finding : result.getFindings()) {
            formattedFindings.add(formatFinding(finding, options));
        }
        context.put("formattedFindings", formattedFindings);

        // Finding counts by severity
        Map<AnalysisResult.Severity, Integer> findingsBySeverity = new EnumMap<>(AnalysisResult.Severity.class);
        for (AnalysisResult.Finding finding : result.getFindings()) {
            findingsBySeverity.merge(finding.severity(), 1, Integer::sum);
        }
        context.put("findingsBySeverity", findingsBySeverity);

        return context;
    }

    private Map<String, Object> formatFinding(AnalysisResult.Finding finding, OutputOptions options) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("severity", finding.severity());
        info.put("category", finding.category());
        info.put("message", finding.message());

        if (finding.details() != null) {
            info.put("details", finding.details());
        }

        if (finding.affectedThreads() != null && !finding.affectedThreads().isEmpty()) {
            List<Map<String, Object>> threads = new ArrayList<>();
            int maxToShow = Math.min(options.getMaxThreads(), finding.affectedThreads().size());
            for (int i = 0; i < maxToShow; i++) {
                ThreadInfo thread = finding.affectedThreads().get(i);
                threads.add(formatThreadInfo(thread, options));
            }
            info.put("affectedThreads", threads);
            info.put("hasMoreThreads", finding.affectedThreads().size() > maxToShow);
            info.put("moreThreadCount", finding.affectedThreads().size() - maxToShow);
        }

        return info;
    }

    private Map<String, Object> formatThreadInfo(ThreadInfo thread, OutputOptions options) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", thread.name());
        info.put("state", thread.state());

        if (options.isShowThreadIds()) {
            info.put("threadId", thread.threadId());
            info.put("nativeId", thread.nativeId());
        }

        if (thread.daemon() != null) {
            info.put("daemon", thread.daemon());
        }

        if (options.isShowTimestamps()) {
            info.put("cpuTimeMs", thread.cpuTimeMs());
            info.put("elapsedTimeMs", thread.elapsedTimeMs());
        }

        // Include first few stack frames if verbose
        if (options.isVerbose() && thread.stackTrace() != null && !thread.stackTrace().isEmpty()) {
            List<String> stackLines = new ArrayList<>();
            int maxDepth = Math.min(5, thread.stackTrace().size()); // Limit to 5 for compact view
            for (int i = 0; i < maxDepth; i++) {
                StackFrame frame = thread.stackTrace().get(i);
                stackLines.add(formatStackFrame(frame));
            }
            if (thread.stackTrace().size() > maxDepth) {
                stackLines.add("...");
            }
            info.put("stackPreview", stackLines);
        }

        return info;
    }

    private String formatStackFrame(StackFrame frame) {
        StringBuilder sb = new StringBuilder();
        if (frame.className() != null) {
            String className = frame.className();
            int lastDot = className.lastIndexOf('.');
            if (lastDot >= 0) {
                className = className.substring(lastDot + 1);
            }
            sb.append(className);
            if (frame.methodName() != null) {
                sb.append(".").append(frame.methodName());
            }
        } else if (frame.methodName() != null) {
            sb.append(frame.methodName());
        }
        return sb.toString();
    }
}