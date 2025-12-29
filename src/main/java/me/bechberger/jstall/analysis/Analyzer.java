package me.bechberger.jstall.analysis;

import org.jetbrains.annotations.NotNull;

/**
 * Base interface for all analyzers.
 * Each analyzer focuses on detecting specific patterns or issues in thread dumps.
 *
 * @param <R> The type of result this analyzer produces
 */
public interface Analyzer<R extends AnalysisResult> {

    /**
     * Get the unique name of this analyzer
     */
    @NotNull String getName();

    /**
     * Get a description of what this analyzer does
     */
    @NotNull String getDescription();

    /**
     * Check if this analyzer can operate on the given context.
     * Some analyzers require multiple dumps or JFR data.
     */
    boolean canAnalyze(@NotNull AnalysisContext context);

    /**
     * Perform the analysis on the given context
     *
     * @param context The analysis context containing dumps and options
     * @return The analysis result
     * @throws IllegalArgumentException if canAnalyze() returns false
     */
    @NotNull R analyze(@NotNull AnalysisContext context);

    /**
     * Get the priority of this analyzer (higher = runs first).
     * Used for ordering when running multiple analyzers.
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Check if this analyzer requires multiple thread dumps
     */
    default boolean requiresMultipleDumps() {
        return false;
    }

    /**
     * Check if this analyzer requires JFR data
     */
    default boolean requiresJfr() {
        return false;
    }
}