package me.bechberger.jstall.analysis;

import me.bechberger.jstall.analysis.analyzers.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Registry and orchestrator for analyzers.
 * Provides centralized management of analyzers and coordinated analysis execution.
 */
public class AnalysisEngine {

    private final List<Analyzer<?>> analyzers = new ArrayList<>();
    private final Map<String, Analyzer<?>> analyzersByName = new HashMap<>();

    /**
     * Create an engine with default analyzers
     */
    public static AnalysisEngine createDefault() {
        AnalysisEngine engine = new AnalysisEngine();
        // Register default analyzers in priority order
        engine.register(new DeadlockAnalyzer());
        engine.register(new ThreadProgressAnalyzer());
        engine.register(new LockContentionAnalyzer());
        engine.register(new JfrProfilingAnalyzer());
        engine.register(new ThreadChurnAnalyzer());
        engine.register(new IOBlockAnalyzer());
        engine.register(new GCActivityAnalyzer());
        engine.register(new ThreadPoolAnalyzer());
        engine.register(new StackGroupAnalyzer());
        engine.register(new SimilarStackAnalyzer());
        engine.register(new ClassLoadingAnalyzer());
        return engine;
    }

    /**
     * Create an empty engine (for custom analyzer selection)
     */
    public static AnalysisEngine createEmpty() {
        return new AnalysisEngine();
    }

    /**
     * Register an analyzer
     */
    public AnalysisEngine register(@NotNull Analyzer<?> analyzer) {
        if (analyzersByName.containsKey(analyzer.getName())) {
            throw new IllegalArgumentException("Analyzer already registered: " + analyzer.getName());
        }
        analyzers.add(analyzer);
        analyzersByName.put(analyzer.getName(), analyzer);
        // Keep sorted by priority (descending)
        analyzers.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        return this;
    }

    /**
     * Unregister an analyzer by name
     */
    public AnalysisEngine unregister(@NotNull String name) {
        Analyzer<?> removed = analyzersByName.remove(name);
        if (removed != null) {
            analyzers.remove(removed);
        }
        return this;
    }

    /**
     * Get an analyzer by name
     */
    @SuppressWarnings("unchecked")
    public <T extends Analyzer<?>> Optional<T> getAnalyzer(String name, Class<T> type) {
        Analyzer<?> analyzer = analyzersByName.get(name);
        if (analyzer != null && type.isInstance(analyzer)) {
            return Optional.of((T) analyzer);
        }
        return Optional.empty();
    }

    /**
     * Get all registered analyzers
     */
    public List<Analyzer<?>> getAnalyzers() {
        return Collections.unmodifiableList(analyzers);
    }

    /**
     * Get analyzers that can analyze the given context
     */
    public List<Analyzer<?>> getApplicableAnalyzers(@NotNull AnalysisContext context) {
        return analyzers.stream()
                .filter(a -> a.canAnalyze(context))
                .toList();
    }

    /**
     * Run all applicable analyzers on the context
     *
     * @param context The analysis context
     * @return A composite result containing all individual results
     */
    public AnalysisResult.CompositeResult analyzeAll(@NotNull AnalysisContext context) {
        List<AnalysisResult> results = new ArrayList<>();
        for (Analyzer<?> analyzer : analyzers) {
            if (analyzer.canAnalyze(context)) {
                try {
                    results.add(analyzer.analyze(context));
                } catch (Exception e) {
                    // Log error but continue with other analyzers
                    results.add(new ErrorResult(analyzer.getName(), e));
                }
            }
        }
        return new AnalysisResult.CompositeResult(results);
    }

    /**
     * Run specific analyzers by name
     *
     * @param context The analysis context
     * @param analyzerNames Names of analyzers to run
     * @return A composite result containing the specified analyzer results
     */
    public AnalysisResult.CompositeResult analyze(@NotNull AnalysisContext context, @NotNull String... analyzerNames) {
        List<AnalysisResult> results = new ArrayList<>();
        for (String name : analyzerNames) {
            Analyzer<?> analyzer = analyzersByName.get(name);
            if (analyzer == null) {
                results.add(new ErrorResult(name, new IllegalArgumentException("Unknown analyzer: " + name)));
            } else if (!analyzer.canAnalyze(context)) {
                results.add(new ErrorResult(name, new IllegalArgumentException(
                        "Analyzer cannot analyze this context: " + name)));
            } else {
                try {
                    results.add(analyzer.analyze(context));
                } catch (Exception e) {
                    results.add(new ErrorResult(name, e));
                }
            }
        }
        return new AnalysisResult.CompositeResult(results);
    }

    /**
     * Run a single analyzer by type
     */
    public <R extends AnalysisResult> R analyze(@NotNull AnalysisContext context, @NotNull Analyzer<R> analyzer) {
        if (!analyzer.canAnalyze(context)) {
            throw new IllegalArgumentException("Analyzer cannot analyze this context: " + analyzer.getName());
        }
        return analyzer.analyze(context);
    }

    /**
     * Result representing an error during analysis
     */
    private static class ErrorResult extends AnalysisResult {
        private final Exception exception;

        ErrorResult(String analyzerName, Exception exception) {
            super(analyzerName, Severity.ERROR, List.of(
                    new Finding(Severity.ERROR, "error",
                            "Analysis failed: " + exception.getMessage())
            ));
            this.exception = exception;
        }

        public Exception getException() {
            return exception;
        }

        @Override
        public String getSummary() {
            return "Error: " + exception.getMessage();
        }
    }

    /**
     * Builder for creating a customized analysis engine
     */
    public static class Builder {
        private final AnalysisEngine engine = new AnalysisEngine();
        private boolean includeDefaults = false;

        public Builder withDefaults() {
            this.includeDefaults = true;
            return this;
        }

        public Builder with(@NotNull Analyzer<?> analyzer) {
            engine.register(analyzer);
            return this;
        }

        public Builder without(@NotNull String analyzerName) {
            engine.unregister(analyzerName);
            return this;
        }

        public AnalysisEngine build() {
            if (includeDefaults) {
                // Add default analyzers that aren't already registered
                registerIfAbsent(new DeadlockAnalyzer());
                registerIfAbsent(new ThreadProgressAnalyzer());
                registerIfAbsent(new LockContentionAnalyzer());
                registerIfAbsent(new ThreadChurnAnalyzer());
                registerIfAbsent(new StackGroupAnalyzer());
                registerIfAbsent(new SimilarStackAnalyzer());
            }
            return engine;
        }

        private void registerIfAbsent(Analyzer<?> analyzer) {
            if (!engine.analyzersByName.containsKey(analyzer.getName())) {
                engine.register(analyzer);
            }
        }
    }
}