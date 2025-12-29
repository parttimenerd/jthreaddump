package me.bechberger.jstall.analysis.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;

/**
 * Utility class for analyzing trends and detecting anomalies across multiple data points.
 * Provides statistical analysis and pattern detection for temporal data.
 */
public class TrendAnalyzer {

    /**
     * Analyze trend from a list of snapshots
     */
    public static <T> Trend<T> analyzeTrend(@NotNull List<AnalyzerSnapshot<T>> snapshots) {
        if (snapshots.isEmpty()) {
            return new Trend<>(TrendDirection.UNKNOWN, 0.0, null, null, List.of(), List.of());
        }

        if (snapshots.size() == 1) {
            return new Trend<>(TrendDirection.STABLE, 0.0, snapshots.getFirst(), snapshots.getFirst(),
                               snapshots, List.of());
        }

        // Extract numeric metrics for trend analysis
        List<Double> values = extractNumericMetric(snapshots, s -> s.metrics().get("value"));

        TrendDirection direction = calculateDirection(values);
        double changeRate = calculateChangeRate(values);
        AnalyzerSnapshot<T> first = snapshots.getFirst();
        AnalyzerSnapshot<T> last = snapshots.getLast();
        List<Anomaly> anomalies = detectAnomalies(snapshots);

        return new Trend<>(direction, changeRate, first, last, snapshots, anomalies);
    }

    /**
     * Detect anomalies in a list of snapshots using statistical analysis
     */
    public static <T> List<Anomaly> detectAnomalies(@NotNull List<AnalyzerSnapshot<T>> snapshots) {
        if (snapshots.size() < 3) {
            return List.of(); // Need at least 3 points for anomaly detection
        }

        List<Anomaly> anomalies = new ArrayList<>();
        List<Double> values = extractNumericMetric(snapshots, s -> s.metrics().get("value"));

        if (values.isEmpty()) {
            return anomalies;
        }

        // Calculate mean and standard deviation
        double mean = calculateMean(values);
        double stdDev = calculateStdDev(values, mean);

        // Detect outliers (values > 2 standard deviations from mean)
        double threshold = 2.0;

        for (int i = 0; i < values.size(); i++) {
            double value = values.get(i);
            double zScore = stdDev > 0 ? Math.abs((value - mean) / stdDev) : 0;

            if (zScore > threshold) {
                AnomalyType type = value > mean ? AnomalyType.SPIKE : AnomalyType.DROP;
                anomalies.add(new Anomaly(
                        snapshots.get(i).dumpIndex(),
                        type,
                        value,
                        mean,
                        zScore,
                        String.format("Value %.2f is %.2f standard deviations from mean %.2f",
                                      value, zScore, mean)
                ));
            }
        }

        return anomalies;
    }

    /**
     * Analyze trend for numeric values
     */
    public static Trend<Double> analyzeTrendNumeric(@NotNull List<Double> values) {
        if (values.isEmpty()) {
            return new Trend<>(TrendDirection.UNKNOWN, 0.0, null, null, List.of(), List.of());
        }

        List<AnalyzerSnapshot<Double>> snapshots = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("value", values.get(i));
            snapshots.add(new AnalyzerSnapshot<>(i, null, values.get(i), metrics));
        }

        return analyzeTrend(snapshots);
    }

    /**
     * Calculate if a trend is growing, shrinking, or stable
     */
    private static TrendDirection calculateDirection(List<Double> values) {
        if (values.size() < 2) {
            return TrendDirection.STABLE;
        }

        double first = values.getFirst();
        double last = values.getLast();
        double change = last - first;
        double percentChange = first != 0 ? (change / Math.abs(first)) * 100 : 0;

        // Consider change significant if > 10%
        if (Math.abs(percentChange) < 10) {
            return TrendDirection.STABLE;
        }

        // Check if trend is consistent (not oscillating)
        int increases = 0;
        int decreases = 0;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) > values.get(i - 1)) increases++;
            else if (values.get(i) < values.get(i - 1)) decreases++;
        }

        // If mostly increasing or decreasing, determine direction
        if (increases > decreases * 1.5) {
            return TrendDirection.INCREASING;
        } else if (decreases > increases * 1.5) {
            return TrendDirection.DECREASING;
        } else if (Math.abs(percentChange) > 20) {
            // Large overall change despite oscillation
            return change > 0 ? TrendDirection.INCREASING : TrendDirection.DECREASING;
        }

        return TrendDirection.OSCILLATING;
    }

    /**
     * Calculate rate of change (percentage)
     */
    private static double calculateChangeRate(List<Double> values) {
        if (values.size() < 2) {
            return 0.0;
        }

        double first = values.getFirst();
        double last = values.getLast();

        if (first == 0) {
            return last > 0 ? Double.POSITIVE_INFINITY : 0.0;
        }

        return ((last - first) / Math.abs(first)) * 100.0;
    }

    /**
     * Extract numeric metric from snapshots
     */
    private static <T> List<Double> extractNumericMetric(
            List<AnalyzerSnapshot<T>> snapshots,
            Function<AnalyzerSnapshot<T>, Object> extractor) {

        List<Double> values = new ArrayList<>();

        for (AnalyzerSnapshot<T> snapshot : snapshots) {
            Object value = extractor.apply(snapshot);
            if (value instanceof Number num) {
                values.add(num.doubleValue());
            }
        }

        return values;
    }

    /**
     * Calculate mean of values
     */
    private static double calculateMean(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * Calculate standard deviation
     */
    private static double calculateStdDev(List<Double> values, double mean) {
        if (values.size() < 2) {
            return 0.0;
        }

        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance);
    }

    /**
     * Snapshot of analyzer data at a specific point in time
     */
    public record AnalyzerSnapshot<T>(
            int dumpIndex,
            @Nullable java.time.Instant timestamp,
            T data,
            Map<String, Object> metrics
    ) {
        public AnalyzerSnapshot {
            metrics = metrics != null ? Map.copyOf(metrics) : Map.of();
        }
    }

    /**
     * Trend analysis result
     */
    public record Trend<T>(
            TrendDirection direction,
            double changeRate,           // Percentage change
            @Nullable AnalyzerSnapshot<T> first,
            @Nullable AnalyzerSnapshot<T> last,
            List<AnalyzerSnapshot<T>> snapshots,
            List<Anomaly> anomalies
    ) {
        public Trend {
            snapshots = snapshots != null ? List.copyOf(snapshots) : List.of();
            anomalies = anomalies != null ? List.copyOf(anomalies) : List.of();
        }

        public boolean isIncreasing() {
            return direction == TrendDirection.INCREASING;
        }

        public boolean isDecreasing() {
            return direction == TrendDirection.DECREASING;
        }

        public boolean isStable() {
            return direction == TrendDirection.STABLE;
        }

        public boolean hasAnomalies() {
            return !anomalies.isEmpty();
        }

        public String getChangeDisplay() {
            if (changeRate == Double.POSITIVE_INFINITY) {
                return "∞ ↑";
            } else if (changeRate == Double.NEGATIVE_INFINITY) {
                return "-∞ ↓";
            } else if (changeRate > 0) {
                return String.format("+%.1f%% ↑", changeRate);
            } else if (changeRate < 0) {
                return String.format("%.1f%% ↓", changeRate);
            }
            return "→";
        }
    }

    /**
     * Anomaly detected in temporal data
     */
    public record Anomaly(
            int dumpIndex,
            AnomalyType type,
            double value,
            double expectedValue,
            double severity,             // Z-score or similar metric
            String description
    ) {
        public boolean isSevere() {
            return severity > 3.0;
        }
    }

    /**
     * Direction of a trend
     */
    public enum TrendDirection {
        INCREASING,
        DECREASING,
        STABLE,
        OSCILLATING,
        UNKNOWN
    }

    /**
     * Type of anomaly
     */
    public enum AnomalyType {
        SPIKE,       // Sudden increase
        DROP,        // Sudden decrease
        PLATEAU,     // Flat period after change
        OSCILLATION  // Rapid fluctuation
    }
}