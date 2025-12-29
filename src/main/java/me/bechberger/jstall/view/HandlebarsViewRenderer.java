package me.bechberger.jstall.view;

import me.bechberger.jstall.analysis.AnalysisResult;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ViewRenderer implementation that uses Handlebars templates for TEXT and HTML output.
 * Extends AbstractViewRenderer to inherit JSON/YAML serialization.
 */
public class HandlebarsViewRenderer extends AbstractViewRenderer {

    private final String cliTemplateName;
    private final String htmlTemplateName;
    private final Class<? extends AnalysisResult>[] resultTypes;

    /**
     * Create a renderer with separate CLI and HTML templates
     */
    @SafeVarargs
    public HandlebarsViewRenderer(String name, String cliTemplateName, String htmlTemplateName,
                                   Class<? extends AnalysisResult>... resultTypes) {
        super(name);
        this.cliTemplateName = cliTemplateName;
        this.htmlTemplateName = htmlTemplateName;
        this.resultTypes = resultTypes;
    }

    /**
     * Create a renderer with the same template name for both CLI and HTML (different paths)
     */
    @SafeVarargs
    public HandlebarsViewRenderer(String name, String templateName,
                                   Class<? extends AnalysisResult>... resultTypes) {
        this(name, templateName, templateName, resultTypes);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends AnalysisResult>[] getResultTypes() {
        return resultTypes != null ? resultTypes : new Class[] { AnalysisResult.class };
    }

    @Override
    protected String renderText(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        try {
            Map<String, Object> context = buildContext(result, options);
            return HandlebarsEngine.getInstance().render(cliTemplateName, context, options);
        } catch (IOException e) {
            throw new RuntimeException("Failed to render CLI template: " + cliTemplateName, e);
        }
    }

    @Override
    protected String renderHtml(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        try {
            Map<String, Object> context = buildContext(result, options);
            // Use the format-aware render method which automatically selects HTML templates
            OutputOptions htmlOptions = OutputOptions.builder()
                    .format(OutputFormat.HTML)
                    .colorEnabled(options.isColorEnabled())
                    .verbose(options.isVerbose())
                    .maxThreads(options.getMaxThreads())
                    .maxStackDepth(options.getMaxStackDepth())
                    .showTimestamps(options.isShowTimestamps())
                    .showThreadIds(options.isShowThreadIds())
                    .build();
            return HandlebarsEngine.getInstance().render(htmlTemplateName, context, htmlOptions);
        } catch (IOException e) {
            throw new RuntimeException("Failed to render HTML template: " + htmlTemplateName, e);
        }
    }

    /**
     * Build the context map for template rendering.
     * Subclasses can override to add custom data.
     */
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("result", result);
        context.put("analyzerName", result.getAnalyzerName());
        context.put("timestamp", result.getTimestamp());
        context.put("severity", result.getSeverity());
        context.put("findings", result.getFindings());
        context.put("summary", result.getSummary());
        context.put("hasFindings", result.hasFindings());

        // Add options
        context.put("options", options);
        context.put("colorEnabled", options.isColorEnabled());
        context.put("verbose", options.isVerbose());
        context.put("maxThreads", options.getMaxThreads());
        context.put("maxStackDepth", options.getMaxStackDepth());
        context.put("showTimestamps", options.isShowTimestamps());
        context.put("showThreadIds", options.isShowThreadIds());

        // Add embedded Chart.js for HTML output (single-file, offline-capable)
        if (options.getFormat() == OutputFormat.HTML) {
            context.put("chartJsScript", ResourceCache.getInstance().getChartJsScriptTag());
            context.put("interactiveScript", ResourceCache.getInstance().getInteractiveScript());
            context.put("visualizationsScript", ResourceCache.getInstance().getVisualizationsScript());
        }

        return context;
    }
}