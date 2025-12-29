package me.bechberger.jstall.analysis;

import me.bechberger.jstall.model.ThreadInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Options for configuring analysis behavior.
 * Includes thread filtering, thresholds, and feature toggles.
 */
public class AnalysisOptions {

    // Thread filtering
    private boolean includeDaemon = false;
    private boolean includeGc = false;
    private boolean includeVm = false;
    private List<Pattern> ignorePatterns = new ArrayList<>();
    private List<Pattern> focusPatterns = new ArrayList<>();

    // Thresholds
    private long cpuEpsilonMs = 2;
    private int minDumpsForStall = 2;
    private double stuckThreadThreshold = 0.9;
    private long longHeldLockThresholdMs = 5000;

    // Feature toggles
    private boolean detectDeadlocks = true;
    private boolean detectNoProgress = true;
    private boolean detectLongHeldLocks = true;
    private boolean groupIdenticalStacks = true;
    private boolean groupSimilarStacks = true;
    private boolean trackThreadChurn = true;

    private AnalysisOptions() {}

    public static AnalysisOptions defaults() {
        return new AnalysisOptions();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isIncludeDaemon() { return includeDaemon; }
    public boolean isIncludeGc() { return includeGc; }
    public boolean isIncludeVm() { return includeVm; }
    public List<Pattern> getIgnorePatterns() { return ignorePatterns; }
    public List<Pattern> getFocusPatterns() { return focusPatterns; }
    public long getCpuEpsilonMs() { return cpuEpsilonMs; }
    public int getMinDumpsForStall() { return minDumpsForStall; }
    public double getStuckThreadThreshold() { return stuckThreadThreshold; }
    public long getLongHeldLockThresholdMs() { return longHeldLockThresholdMs; }
    public boolean isDetectDeadlocks() { return detectDeadlocks; }
    public boolean isDetectNoProgress() { return detectNoProgress; }
    public boolean isDetectLongHeldLocks() { return detectLongHeldLocks; }
    public boolean isGroupIdenticalStacks() { return groupIdenticalStacks; }
    public boolean isGroupSimilarStacks() { return groupSimilarStacks; }
    public boolean isTrackThreadChurn() { return trackThreadChurn; }

    public Predicate<ThreadInfo> getThreadFilter() {
        return thread -> {
            String name = thread.name();
            if (name == null) name = "";

            if (!focusPatterns.isEmpty()) {
                boolean matches = false;
                for (Pattern p : focusPatterns) {
                    if (p.matcher(name).find()) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) return false;
            }

            for (Pattern p : ignorePatterns) {
                if (p.matcher(name).find()) return false;
            }

            if (!includeDaemon && Boolean.TRUE.equals(thread.daemon())) return false;
            if (!includeGc && isGcThread(name)) return false;
            if (!includeVm && isVmThread(name)) return false;

            return true;
        };
    }

    private static boolean isGcThread(String name) {
        return name.startsWith("GC ") || name.startsWith("G1 ") ||
               name.startsWith("ZGC ") || name.startsWith("Shenandoah ") ||
               name.equals("GC Thread") || name.contains("GC Thread");
    }

    private static boolean isVmThread(String name) {
        return name.startsWith("VM ") || name.equals("VM Thread") ||
               name.equals("VM Periodic Task Thread") || name.equals("Finalizer") ||
               name.equals("Reference Handler") || name.equals("Signal Dispatcher") ||
               name.equals("Attach Listener") || name.equals("Service Thread") ||
               name.equals("Common-Cleaner") || name.equals("Notification Thread");
    }

    public static class Builder {
        private final AnalysisOptions options = new AnalysisOptions();

        public Builder includeDaemon(boolean include) { options.includeDaemon = include; return this; }
        public Builder includeGc(boolean include) { options.includeGc = include; return this; }
        public Builder includeVm(boolean include) { options.includeVm = include; return this; }

        public Builder includeAll() {
            options.includeDaemon = true;
            options.includeGc = true;
            options.includeVm = true;
            return this;
        }

        public Builder addIgnorePattern(@NotNull String regex) {
            options.ignorePatterns.add(Pattern.compile(regex));
            return this;
        }

        public Builder addFocusPattern(@NotNull String regex) {
            options.focusPatterns.add(Pattern.compile(regex));
            return this;
        }

        public Builder cpuEpsilonMs(long ms) { options.cpuEpsilonMs = ms; return this; }
        public Builder minDumpsForStall(int count) { options.minDumpsForStall = count; return this; }
        public Builder stuckThreadThreshold(double threshold) { options.stuckThreadThreshold = threshold; return this; }
        public Builder longHeldLockThresholdMs(long ms) { options.longHeldLockThresholdMs = ms; return this; }
        public Builder detectDeadlocks(boolean detect) { options.detectDeadlocks = detect; return this; }
        public Builder detectNoProgress(boolean detect) { options.detectNoProgress = detect; return this; }
        public Builder detectLongHeldLocks(boolean detect) { options.detectLongHeldLocks = detect; return this; }
        public Builder groupIdenticalStacks(boolean group) { options.groupIdenticalStacks = group; return this; }
        public Builder groupSimilarStacks(boolean group) { options.groupSimilarStacks = group; return this; }
        public Builder trackThreadChurn(boolean track) { options.trackThreadChurn = track; return this; }

        public AnalysisOptions build() {
            options.ignorePatterns = List.copyOf(options.ignorePatterns);
            options.focusPatterns = List.copyOf(options.focusPatterns);
            return options;
        }
    }
}