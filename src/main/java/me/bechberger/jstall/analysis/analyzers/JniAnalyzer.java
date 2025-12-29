package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.model.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Analyzer for JNI resource usage and potential issues.
 * Tracks JNI global/local refs, native threads, and native method calls.
 */
public class JniAnalyzer implements Analyzer<JniAnalyzer.JniResult> {

    private static final String NAME = "JniAnalyzer";

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDescription() {
        return "Analyzes JNI resource usage, native method calls, and potential native issues";
    }

    @Override
    public boolean canAnalyze(@NotNull AnalysisContext context) {
        return true;
    }

    @Override
    public int getPriority() {
        return 35;
    }

    @Override
    public @NotNull JniResult analyze(@NotNull AnalysisContext context) {
        List<AnalysisResult.Finding> findings = new ArrayList<>();
        AnalysisResult.Severity worstSeverity = AnalysisResult.Severity.OK;
        List<JniSnapshot> snapshots = new ArrayList<>();

        for (int dumpIndex = 0; dumpIndex < context.getDumpCount(); dumpIndex++) {
            ThreadDump dump = context.getDumps().get(dumpIndex);

            int nativeMethodThreads = 0;
            List<NativeThreadInfo> nativeThreads = new ArrayList<>();

            for (ThreadInfo thread : context.getFilteredThreads(dump)) {
                // Check for native methods in stack
                boolean hasNativeMethod = false;
                String topNativeMethod = null;
                if (thread.stackTrace() != null) {
                    for (StackFrame frame : thread.stackTrace()) {
                        if (frame.nativeMethod()) {
                            hasNativeMethod = true;
                            if (topNativeMethod == null) {
                                topNativeMethod = formatFrame(frame);
                            }
                        }
                    }
                }
                if (hasNativeMethod) {
                    nativeMethodThreads++;
                    nativeThreads.add(new NativeThreadInfo(
                            thread.name(),
                            thread.nativeId(),
                            topNativeMethod,
                            thread.state()
                    ));
                }
            }

            // Get JNI info from dump level (not per-thread)
            long globalRefs = 0;
            long weakRefs = 0;
            if (dump.jniInfo() != null) {
                JniInfo jni = dump.jniInfo();
                if (jni.globalRefs() != null) globalRefs = jni.globalRefs();
                if (jni.weakRefs() != null) weakRefs = jni.weakRefs();
            }

            snapshots.add(new JniSnapshot(
                    dumpIndex,
                    dump.timestamp(),
                    nativeMethodThreads,
                    dump.jniInfo() != null,
                    globalRefs,
                    weakRefs,
                    nativeThreads
            ));
        }

        // Analyze trends
        JniSummary summary = analyzeTrends(snapshots, findings);

        for (AnalysisResult.Finding finding : findings) {
            if (finding.severity().isWorseThan(worstSeverity)) {
                worstSeverity = finding.severity();
            }
        }

        return new JniResult(worstSeverity, findings, snapshots, summary);
    }

    private JniSummary analyzeTrends(List<JniSnapshot> snapshots, List<AnalysisResult.Finding> findings) {
        if (snapshots.isEmpty()) {
            return new JniSummary(0, 0, 0, 0, false, false, false);
        }

        int maxNativeThreads = snapshots.stream().mapToInt(JniSnapshot::nativeMethodThreads).max().orElse(0);
        long maxGlobalRefs = snapshots.stream().mapToLong(JniSnapshot::totalGlobalRefs).max().orElse(0);
        long maxWeakRefs = snapshots.stream().mapToLong(JniSnapshot::totalWeakRefs).max().orElse(0);

        // Check for growing global refs (potential leak)
        boolean globalRefsGrowing = false;
        if (snapshots.size() >= 2) {
            long first = snapshots.get(0).totalGlobalRefs();
            long last = snapshots.get(snapshots.size() - 1).totalGlobalRefs();
            if (last > first * 1.5 && last - first > 100) {
                globalRefsGrowing = true;
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.WARNING, "jni-global-refs-growing",
                        String.format("JNI global refs growing: %d → %d", first, last))
                        .detail("first", first)
                        .detail("last", last)
                        .build());
            }
        }

        // High native thread count
        boolean highNativeThreads = maxNativeThreads > 20;
        if (highNativeThreads) {
            findings.add(AnalysisResult.Finding.builder(
                    AnalysisResult.Severity.INFO, "jni-high-native-threads",
                    String.format("%d threads executing native methods", maxNativeThreads))
                    .detail("count", maxNativeThreads)
                    .build());
        }

        // Check for stuck native threads
        boolean stuckNativeThreads = false;
        if (snapshots.size() >= 2) {
            Set<String> firstNative = new HashSet<>();
            Set<String> lastNative = new HashSet<>();
            for (NativeThreadInfo nti : snapshots.get(0).nativeThreads()) {
                if (nti.topNativeMethod() != null) firstNative.add(nti.name() + "@" + nti.topNativeMethod());
            }
            for (NativeThreadInfo nti : snapshots.get(snapshots.size() - 1).nativeThreads()) {
                if (nti.topNativeMethod() != null) lastNative.add(nti.name() + "@" + nti.topNativeMethod());
            }
            firstNative.retainAll(lastNative);
            if (firstNative.size() >= 3) {
                stuckNativeThreads = true;
                findings.add(AnalysisResult.Finding.builder(
                        AnalysisResult.Severity.WARNING, "jni-stuck-native-threads",
                        String.format("%d threads stuck in same native method across dumps", firstNative.size()))
                        .detail("count", firstNative.size())
                        .build());
            }
        }

        return new JniSummary(
                maxNativeThreads,
                maxGlobalRefs,
                maxWeakRefs,
                snapshots.size(),
                globalRefsGrowing,
                highNativeThreads,
                stuckNativeThreads
        );
    }

    private String formatFrame(StackFrame frame) {
        StringBuilder sb = new StringBuilder();
        if (frame.className() != null) {
            String className = frame.className();
            int lastDot = className.lastIndexOf('.');
            if (lastDot >= 0) className = className.substring(lastDot + 1);
            sb.append(className);
        }
        if (frame.methodName() != null) {
            sb.append(".").append(frame.methodName());
        }
        return sb.toString();
    }

    public record NativeThreadInfo(String name, Long nativeId, String topNativeMethod, Thread.State state) {}

    public record JniSnapshot(
            int dumpIndex,
            java.time.Instant timestamp,
            int nativeMethodThreads,
            boolean hasJniInfo,
            long totalGlobalRefs,
            long totalWeakRefs,
            List<NativeThreadInfo> nativeThreads
    ) {
        public JniSnapshot {
            nativeThreads = nativeThreads != null ? List.copyOf(nativeThreads) : List.of();
        }
    }

    public record JniSummary(
            int maxNativeThreads,
            long maxGlobalRefs,
            long maxWeakRefs,
            int snapshotCount,
            boolean globalRefsGrowing,
            boolean highNativeThreads,
            boolean stuckNativeThreads
    ) {
        public boolean hasIssues() {
            return globalRefsGrowing || stuckNativeThreads;
        }
    }

    public static class JniResult extends AnalysisResult {
        private final List<JniSnapshot> snapshots;
        private final JniSummary summary;

        public JniResult(Severity severity, List<Finding> findings,
                        List<JniSnapshot> snapshots, JniSummary summary) {
            super(NAME, severity, findings);
            this.snapshots = List.copyOf(snapshots);
            this.summary = summary;
        }

        public List<JniSnapshot> getSnapshots() { return snapshots; }
        public JniSummary getJniSummary() { return summary; }

        @Override
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(summary.maxNativeThreads()).append(" native threads");
            if (summary.maxGlobalRefs() > 0) {
                sb.append(", ").append(summary.maxGlobalRefs()).append(" global refs");
            }
            if (summary.hasIssues()) {
                sb.append(" (issues detected)");
            }
            return sb.toString();
        }
    }
}