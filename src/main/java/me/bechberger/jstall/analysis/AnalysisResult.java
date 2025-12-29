package me.bechberger.jstall.analysis;

import me.bechberger.jstall.model.ThreadInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;

/**
 * Base class for all analysis results.
 * Provides common functionality and metadata for analysis outputs.
 */
public abstract class AnalysisResult {

    private final String analyzerName;
    private final Instant timestamp;
    private final Severity severity;
    private final List<Finding> findings;

    protected AnalysisResult(String analyzerName, Severity severity, List<Finding> findings) {
        this.analyzerName = analyzerName;
        this.timestamp = Instant.now();
        this.severity = severity;
        this.findings = findings != null ? List.copyOf(findings) : List.of();
    }

    public String getAnalyzerName() {
        return analyzerName;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Severity getSeverity() {
        return severity;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public boolean hasFindings() {
        return !findings.isEmpty();
    }

    /**
     * Get a human-readable summary of this result
     */
    public abstract String getSummary();

    /**
     * Severity levels for analysis results
     */
    public enum Severity {
        /** No issues detected */
        OK(0),
        /** Informational finding */
        INFO(1),
        /** Potential issue that may warrant investigation */
        WARNING(2),
        /** Significant issue detected */
        ERROR(3),
        /** Critical issue (e.g., deadlock) */
        CRITICAL(4);

        private final int level;

        Severity(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

        public boolean isWorseThan(Severity other) {
            return this.level > other.level;
        }

        public static Severity worst(Severity a, Severity b) {
            return a.level >= b.level ? a : b;
        }
    }

    /**
     * A single finding from an analysis
     */
    public record Finding(
            @NotNull Severity severity,
            @NotNull String category,
            @NotNull String message,
            @Nullable List<ThreadInfo> affectedThreads,
            @Nullable Map<String, Object> details
    ) {
        public Finding(Severity severity, String category, String message) {
            this(severity, category, message, null, null);
        }

        public Finding(Severity severity, String category, String message, List<ThreadInfo> affectedThreads) {
            this(severity, category, message, affectedThreads, null);
        }

        public static Builder builder(Severity severity, String category, String message) {
            return new Builder(severity, category, message);
        }

        public static class Builder {
            private final Severity severity;
            private final String category;
            private final String message;
            private List<ThreadInfo> affectedThreads;
            private Map<String, Object> details;

            private Builder(Severity severity, String category, String message) {
                this.severity = severity;
                this.category = category;
                this.message = message;
            }

            public Builder affectedThreads(List<ThreadInfo> threads) {
                this.affectedThreads = threads;
                return this;
            }

            public Builder affectedThread(ThreadInfo thread) {
                this.affectedThreads = List.of(thread);
                return this;
            }

            public Builder detail(String key, Object value) {
                if (this.details == null) {
                    this.details = new LinkedHashMap<>();
                }
                this.details.put(key, value);
                return this;
            }

            public Builder details(Map<String, Object> details) {
                this.details = details;
                return this;
            }

            public Finding build() {
                return new Finding(
                        severity,
                        category,
                        message,
                        affectedThreads != null ? List.copyOf(affectedThreads) : null,
                        details != null ? Map.copyOf(details) : null
                );
            }
        }
    }

    /**
     * Composite result combining multiple analysis results
     */
    public static class CompositeResult extends AnalysisResult {
        private final List<AnalysisResult> results;

        public CompositeResult(List<AnalysisResult> results) {
            super("CompositeAnalysis", computeWorstSeverity(results), combineFindings(results));
            this.results = List.copyOf(results);
        }

        public List<AnalysisResult> getResults() {
            return results;
        }

        @SuppressWarnings("unchecked")
        public <T extends AnalysisResult> Optional<T> getResult(Class<T> type) {
            return results.stream()
                    .filter(type::isInstance)
                    .map(r -> (T) r)
                    .findFirst();
        }

        public <T extends AnalysisResult> List<T> getResults(Class<T> type) {
            return results.stream()
                    .filter(type::isInstance)
                    .map(type::cast)
                    .toList();
        }

        @Override
        public String getSummary() {
            if (results.isEmpty()) {
                return "No analysis results";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Combined analysis: %d results, severity: %s%n",
                    results.size(), getSeverity()));
            for (AnalysisResult result : results) {
                sb.append(String.format("  - %s: %s%n", result.getAnalyzerName(), result.getSummary()));
            }
            return sb.toString();
        }

        private static Severity computeWorstSeverity(List<AnalysisResult> results) {
            return results.stream()
                    .map(AnalysisResult::getSeverity)
                    .reduce(Severity.OK, Severity::worst);
        }

        private static List<Finding> combineFindings(List<AnalysisResult> results) {
            List<Finding> combined = new ArrayList<>();
            for (AnalysisResult result : results) {
                combined.addAll(result.getFindings());
            }
            // Sort by severity (worst first)
            combined.sort((a, b) -> Integer.compare(b.severity().getLevel(), a.severity().getLevel()));
            return combined;
        }
    }
}