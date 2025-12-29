package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.view.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for CompositeResult that combines multiple analysis results.
 * Renders each sub-result using its appropriate renderer.
 */
public class CompositeView extends AbstractViewRenderer {

    private final ViewRendererRegistry registry;

    public CompositeView() {
        super("composite");
        this.registry = ViewRendererRegistry.getInstance();
    }

    public CompositeView(ViewRendererRegistry registry) {
        super("composite");
        this.registry = registry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends AnalysisResult>[] getResultTypes() {
        return new Class[] { AnalysisResult.CompositeResult.class };
    }

    @Override
    protected String renderText(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        if (!(result instanceof AnalysisResult.CompositeResult composite)) {
            return result.getSummary();
        }

        StringBuilder sb = new StringBuilder();
        boolean colorEnabled = options.isColorEnabled();

        // Header
        String header = "═══════════════════════════════════════════════════════════════════════════════";
        String subHeader = "───────────────────────────────────────────────────────────────────────────────";

        if (colorEnabled) {
            sb.append(OutputOptions.Colors.bold(header, options)).append("\n");
            sb.append(OutputOptions.Colors.bold("                         ANALYSIS RESULTS", options)).append("\n");
            sb.append(OutputOptions.Colors.bold(header, options)).append("\n\n");
        } else {
            sb.append(header).append("\n");
            sb.append("                         ANALYSIS RESULTS\n");
            sb.append(header).append("\n\n");
        }

        // Overall severity
        AnalysisResult.Severity severity = composite.getSeverity();
        sb.append("Overall Severity: ")
          .append(OutputOptions.Colors.severity(severity, severity.toString(), options))
          .append("\n");
        sb.append("Total Findings: ").append(composite.getFindings().size()).append("\n");
        sb.append("Analyzers Run: ").append(composite.getResults().size()).append("\n\n");

        // Render each sub-result
        for (AnalysisResult subResult : composite.getResults()) {
            if (colorEnabled) {
                sb.append(OutputOptions.Colors.cyan(subHeader, options)).append("\n");
                sb.append(OutputOptions.Colors.bold(subResult.getAnalyzerName(), options)).append("\n");
                sb.append(OutputOptions.Colors.cyan(subHeader, options)).append("\n\n");
            } else {
                sb.append(subHeader).append("\n");
                sb.append(subResult.getAnalyzerName()).append("\n");
                sb.append(subHeader).append("\n\n");
            }

            // Find appropriate renderer for this result type
            ViewRenderer renderer = registry.findBestRenderer(subResult, OutputFormat.TEXT);
            if (renderer != null && renderer != this) { // Avoid infinite recursion
                String rendered = renderer.render(subResult, options);
                sb.append(rendered);
            } else {
                // Fallback to summary
                sb.append("Severity: ")
                  .append(OutputOptions.Colors.severity(subResult.getSeverity(),
                          subResult.getSeverity().toString(), options))
                  .append("\n");
                sb.append("Summary: ").append(subResult.getSummary()).append("\n");

                // List findings
                if (subResult.hasFindings()) {
                    sb.append("\nFindings:\n");
                    for (AnalysisResult.Finding finding : subResult.getFindings()) {
                        sb.append("  • [")
                          .append(OutputOptions.Colors.severity(finding.severity(),
                                  finding.severity().toString(), options))
                          .append("] ")
                          .append(finding.message())
                          .append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        // Footer
        if (colorEnabled) {
            sb.append(OutputOptions.Colors.bold(header, options)).append("\n");
        } else {
            sb.append(header).append("\n");
        }

        return sb.toString();
    }

    @Override
    protected String renderHtml(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        if (!(result instanceof AnalysisResult.CompositeResult composite)) {
            return "<p>" + escapeHtml(result.getSummary()) + "</p>";
        }

        StringBuilder sb = new StringBuilder();

        // HTML document start
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<title>Analysis Results - jstall</title>\n");
        sb.append("<style>\n");
        appendCss(sb);
        sb.append("</style>\n");
        sb.append("</head>\n<body>\n");

        // Header
        sb.append("<header>\n");
        sb.append("<h1>Analysis Results</h1>\n");
        sb.append("<div class=\"overall-severity severity-").append(composite.getSeverity().name().toLowerCase())
          .append("\">Overall: ").append(composite.getSeverity()).append("</div>\n");
        sb.append("<div class=\"stats\">\n");
        sb.append("<span>Total Findings: ").append(composite.getFindings().size()).append("</span>\n");
        sb.append("<span>Analyzers Run: ").append(composite.getResults().size()).append("</span>\n");
        sb.append("</div>\n");
        sb.append("</header>\n\n");

        // Navigation
        sb.append("<nav>\n<ul>\n");
        for (AnalysisResult subResult : composite.getResults()) {
            String id = toId(subResult.getAnalyzerName());
            sb.append("<li><a href=\"#").append(id).append("\">")
              .append(escapeHtml(subResult.getAnalyzerName())).append("</a></li>\n");
        }
        sb.append("</ul>\n</nav>\n\n");

        // Main content
        sb.append("<main>\n");
        for (AnalysisResult subResult : composite.getResults()) {
            String id = toId(subResult.getAnalyzerName());
            sb.append("<section id=\"").append(id).append("\" class=\"result-section\">\n");
            sb.append("<h2>").append(escapeHtml(subResult.getAnalyzerName())).append("</h2>\n");
            sb.append("<div class=\"severity-badge severity-")
              .append(subResult.getSeverity().name().toLowerCase()).append("\">")
              .append(subResult.getSeverity()).append("</div>\n");
            sb.append("<p class=\"summary\">").append(escapeHtml(subResult.getSummary())).append("</p>\n");

            // Findings
            if (subResult.hasFindings()) {
                sb.append("<details open>\n<summary>Findings (").append(subResult.getFindings().size())
                  .append(")</summary>\n<ul class=\"findings\">\n");
                for (AnalysisResult.Finding finding : subResult.getFindings()) {
                    sb.append("<li class=\"finding severity-").append(finding.severity().name().toLowerCase())
                      .append("\">\n");
                    sb.append("<span class=\"finding-severity\">").append(finding.severity()).append("</span>\n");
                    sb.append("<span class=\"finding-category\">[").append(escapeHtml(finding.category()))
                      .append("]</span>\n");
                    sb.append("<span class=\"finding-message\">").append(escapeHtml(finding.message()))
                      .append("</span>\n");
                    sb.append("</li>\n");
                }
                sb.append("</ul>\n</details>\n");
            }

            sb.append("</section>\n\n");
        }
        sb.append("</main>\n\n");

        // Footer
        sb.append("<footer>\n");
        sb.append("<p>Generated by jstall at ").append(result.getTimestamp()).append("</p>\n");
        sb.append("</footer>\n");

        sb.append("</body>\n</html>");

        return sb.toString();
    }

    private void appendCss(StringBuilder sb) {
        sb.append("""
            :root {
                --color-ok: #28a745;
                --color-info: #17a2b8;
                --color-warning: #ffc107;
                --color-error: #dc3545;
                --color-critical: #721c24;
            }
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                line-height: 1.6;
                max-width: 1200px;
                margin: 0 auto;
                padding: 20px;
                background: #f5f5f5;
                color: #333;
            }
            header {
                background: #fff;
                padding: 20px;
                border-radius: 8px;
                margin-bottom: 20px;
                box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            }
            h1 { margin: 0 0 10px 0; color: #333; }
            h2 { margin: 0 0 10px 0; color: #444; }
            nav ul { list-style: none; padding: 0; display: flex; gap: 15px; flex-wrap: wrap; }
            nav a { color: #0066cc; text-decoration: none; }
            nav a:hover { text-decoration: underline; }
            .stats { display: flex; gap: 20px; color: #666; }
            .result-section {
                background: #fff;
                padding: 20px;
                margin-bottom: 20px;
                border-radius: 8px;
                box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            }
            .severity-badge, .overall-severity {
                display: inline-block;
                padding: 4px 12px;
                border-radius: 4px;
                font-weight: bold;
                font-size: 0.9em;
            }
            .severity-ok { background: #d4edda; color: var(--color-ok); }
            .severity-info { background: #d1ecf1; color: var(--color-info); }
            .severity-warning { background: #fff3cd; color: #856404; }
            .severity-error { background: #f8d7da; color: var(--color-error); }
            .severity-critical { background: #f5c6cb; color: var(--color-critical); }
            .summary { color: #666; font-style: italic; }
            .findings { list-style: none; padding: 0; }
            .finding {
                padding: 10px;
                margin: 5px 0;
                border-left: 4px solid #ccc;
                background: #f9f9f9;
            }
            .finding.severity-ok { border-color: var(--color-ok); }
            .finding.severity-info { border-color: var(--color-info); }
            .finding.severity-warning { border-color: var(--color-warning); }
            .finding.severity-error { border-color: var(--color-error); }
            .finding.severity-critical { border-color: var(--color-critical); }
            .finding-severity { font-weight: bold; margin-right: 8px; }
            .finding-category { color: #666; margin-right: 8px; }
            details { margin-top: 15px; }
            summary { cursor: pointer; font-weight: bold; }
            footer { text-align: center; color: #666; padding: 20px; }
            """);
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private String toId(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }
}