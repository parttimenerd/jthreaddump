package me.bechberger.jstall.view;

import me.bechberger.jstall.analysis.AnalysisResult;
import org.jetbrains.annotations.NotNull;

/**
 * Interface for rendering analysis results to various output formats.
 */
public interface ViewRenderer {

    /**
     * Get the name of this renderer
     */
    @NotNull String getName();

    /**
     * Check if this renderer supports the given output format
     */
    boolean supports(@NotNull OutputFormat format);

    /**
     * Render the analysis result to a string
     *
     * @param result The analysis result to render
     * @param options Output options (format, colors, etc.)
     * @return The rendered output
     */
    @NotNull String render(@NotNull AnalysisResult result, @NotNull OutputOptions options);

    /**
     * Get the result types this renderer can handle
     */
    default Class<? extends AnalysisResult>[] getResultTypes() {
        return new Class[] { AnalysisResult.class };
    }
}