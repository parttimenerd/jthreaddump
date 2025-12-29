package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.model.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Analyzer that examines GC thread behavior and its impact on application threads.
 */
public class GCActivityAnalyzer implements Analyzer<GCActivityAnalyzer.GCActivityResult> {

    private static final String NAME = "GCActivityAnalyzer";

    // Known GC thread name patterns
    private static final List<String> GC_THREAD_PATTERNS = List.of(
            "GC Thread",
            "G1 Young RemSet",
            "G1 Main Marker",
            "G1 Conc#",
            "G1 Refine#",
            "G1 Service",
            "GC task thread",
            "Gang worker",
            "Concurrent Mark",
            "VM Periodic Task",
            "ZGC",
            "Shenandoah"
    );

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Analyzes GC thread behavior and its impact on application threads";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        return true;
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    public @NotNull GCActivityResult analyze(@NotNull AnalysisContext context) {
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;
        List<GCSnapshot> snapshots = new ArrayList<>();

        for (int dumpIndex = 0; dumpIndex < context.getDumpCount(); dumpIndex++) {
            ThreadDump dump = context.getDumps().get(dumpIndex);

            List<ThreadInfo> gcThreads = new ArrayList<>();
            List<ThreadInfo> appThreads = new ArrayList<>();
            long totalGcCpu = 0;
            long totalAppCpu = 0;
            int runnableGcThreads = 0;

            for (ThreadInfo thread : dump.threads()) {
                if (isGCThread(thread)) {
                    gcThreads.add(thread);
                    if (thread.cpuTimeMs() != null) {
                        totalGcCpu += thread.cpuTimeMs();
                    }
                    if (thread.state() == Thread.State.RUNNABLE) {
                        runnableGcThreads++;
                    }
                } else {
                    appThreads.add(thread);
                    if (thread.cpuTimeMs() != null) {
                        totalAppCpu += thread.cpuTimeMs();
                    }
                }
            }

            // Count threads in safepoint (waiting states that might indicate STW)
            int threadsAtSafepoint = 0;
            for (ThreadInfo thread : appThreads) {
                if (isAtSafepoint(thread)) {
                    threadsAtSafepoint++;
                }
            }

            double gcCpuPercentage = (totalGcCpu + totalAppCpu) > 0
                    ? (totalGcCpu * 100.0) / (totalGcCpu + totalAppCpu)
                    : 0;

            snapshots.add(new GCSnapshot(
                    dumpIndex,
                    dump.timestamp(),
                    gcThreads.size(),
                    runnableGcThreads,
                    appThreads.size(),
                    threadsAtSafepoint,
                    totalGcCpu,
                    totalAppCpu,
                    gcCpuPercentage
            ));
        }

        // Analyze patterns
        GCSummary summary = analyzePatterns(snapshots, findings);

        for (AnalysisResult.Finding finding : findings) {
            if (finding.severity().isWorseThan(worstSeverity)) {
                worstSeverity = finding.severity();
            }
        }

        return new GCActivityResult(worstSeverity, findings, snapshots, summary);
    }

    private boolean isGCThread(ThreadInfo thread) {
        String name = thread.name();
        if (name == null) return false;

        for (String pattern : GC_THREAD_PATTERNS) {
            if (name.startsWith(pattern) || name.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAtSafepoint(ThreadInfo thread) {
        if (thread.state() == Thread.State.RUNNABLE) {
            return false;
        }

        // Check for safepoint-related stack frames
        if (thread.stackTrace() != null) {
            for (StackFrame frame : thread.stackTrace()) {
                String className = frame.className();
                if (className != null && (
                        className.contains("SafepointSynchronize") ||
                        className.contains("VMThread"))) {
                    return true;
                }
            }
        }

        return thread.state() == Thread.State.BLOCKED ||
               thread.state() == Thread.State.WAITING;
    }

    private GCSummary analyzePatterns(List<GCSnapshot> snapshots, List<AnalysisResult.Finding> findings) {
        if (snapshots.isEmpty()) {
            return new GCSummary(0, 0, 0, 0, false, false);
        }

        double avgGcCpuPercentage = snapshots.stream()
                .mapToDouble(GCSnapshot::gcCpuPercentage)
                .average()
                .orElse(0);

        double maxGcCpuPercentage = snapshots.stream()
                .mapToDouble(GCSnapshot::gcCpuPercentage)
                .max()
                .orElse(0);

        int maxRunnableGcThreads = snapshots.stream()
                .mapToInt(GCSnapshot::runnableGcThreads)
                .max()
                .orElse(0);

        int maxThreadsAtSafepoint = snapshots.stream()
                .mapToInt(GCSnapshot::threadsAtSafepoint)
                .max()
                .orElse(0);

        // Detect potential STW pause
        boolean potentialSTW = false;
        for (GCSnapshot snapshot : snapshots) {
            if (snapshot.threadsAtSafepoint() > snapshot.appThreadCount() * 0.8) {
                potentialSTW = true;
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.WARNING, "gc-stw",
                        String.format("Potential STW GC pause: %d/%d app threads at safepoint",
                                snapshot.threadsAtSafepoint(), snapshot.appThreadCount()))
                        .detail("dumpIndex", snapshot.dumpIndex())
                        .detail("threadsAtSafepoint", snapshot.threadsAtSafepoint())
                        .build());
            }
        }

        // Detect high GC CPU usage
        boolean highGcCpu = avgGcCpuPercentage > 20;
        if (highGcCpu) {
            findings.add(AnalysisResult.Finding.builder(
                    AnalysisResult.Severity.WARNING, "gc-high-cpu",
                    String.format("High GC CPU usage: %.1f%% average (max %.1f%%)",
                            avgGcCpuPercentage, maxGcCpuPercentage))
                    .detail("avgGcCpuPercentage", avgGcCpuPercentage)
                    .detail("maxGcCpuPercentage", maxGcCpuPercentage)
                    .build());
        }

        return new GCSummary(
                avgGcCpuPercentage,
                maxGcCpuPercentage,
                maxRunnableGcThreads,
                maxThreadsAtSafepoint,
                potentialSTW,
                highGcCpu
        );
    }

    public record GCSnapshot(
            int dumpIndex,
            java.time.Instant timestamp,
            int gcThreadCount,
            int runnableGcThreads,
            int appThreadCount,
            int threadsAtSafepoint,
            long totalGcCpuMs,
            long totalAppCpuMs,
            double gcCpuPercentage
    ) {}

    public record GCSummary(
            double avgGcCpuPercentage,
            double maxGcCpuPercentage,
            int maxRunnableGcThreads,
            int maxThreadsAtSafepoint,
            boolean potentialSTW,
            boolean highGcCpu
    ) {}

    public static class GCActivityResult extends AnalysisResult {
        private final List<GCSnapshot> snapshots;
        private final GCSummary summary;

        public GCActivityResult(Severity severity, List<Finding> findings,
                               List<GCSnapshot> snapshots, GCSummary summary) {
            super(NAME, severity, findings);
            this.snapshots = List.copyOf(snapshots);
            this.summary = summary;
        }

        public List<GCSnapshot> getSnapshots() {
            return snapshots;
        }

        public GCSummary getGCSummary() {
            return summary;
        }

        @Override
        public String getSummary() {
            if (summary.potentialSTW()) {
                return String.format("GC activity: potential STW detected, %.1f%% avg CPU",
                        summary.avgGcCpuPercentage());
            }
            if (summary.highGcCpu()) {
                return String.format("GC activity: high CPU usage %.1f%% avg",
                        summary.avgGcCpuPercentage());
            }
            return String.format("GC activity: %.1f%% avg CPU", summary.avgGcCpuPercentage());
        }
    }
}