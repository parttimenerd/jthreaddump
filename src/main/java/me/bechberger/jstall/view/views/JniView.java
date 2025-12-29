package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.JniAnalyzer;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for JniAnalyzer results.
 * Shows JNI resource usage and native method activity.
 */
public class JniView extends HandlebarsViewRenderer {

    public JniView() {
        super("jni", "jni", JniAnalyzer.JniResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof JniAnalyzer.JniResult jniResult) {
            JniAnalyzer.JniSummary summary = jniResult.getJniSummary();

            // Summary
            context.put("maxNativeThreads", summary.maxNativeThreads());
            context.put("maxGlobalRefs", summary.maxGlobalRefs());
            context.put("maxWeakRefs", summary.maxWeakRefs());
            context.put("hasIssues", summary.hasIssues());
            context.put("globalRefsGrowing", summary.globalRefsGrowing());
            context.put("highNativeThreads", summary.highNativeThreads());
            context.put("stuckNativeThreads", summary.stuckNativeThreads());

            // Snapshots
            List<JniAnalyzer.JniSnapshot> snapshots = jniResult.getSnapshots();
            context.put("snapshotCount", snapshots.size());
            context.put("isMultiDump", snapshots.size() > 1);

            // Format snapshots for table
            List<Map<String, Object>> formattedSnapshots = new ArrayList<>();
            for (JniAnalyzer.JniSnapshot s : snapshots) {
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("dumpIndex", s.dumpIndex() + 1);
                snap.put("nativeMethodThreads", s.nativeMethodThreads());
                snap.put("globalRefs", s.totalGlobalRefs());
                snap.put("weakRefs", s.totalWeakRefs());
                snap.put("hasJniInfo", s.hasJniInfo());
                formattedSnapshots.add(snap);
            }
            context.put("snapshots", formattedSnapshots);

            // Chart data for multi-dump
            if (snapshots.size() > 1) {
                List<Integer> dumpLabels = new ArrayList<>();
                List<Integer> nativeThreadData = new ArrayList<>();
                List<Long> globalRefData = new ArrayList<>();
                List<Long> weakRefData = new ArrayList<>();
                for (int i = 0; i < snapshots.size(); i++) {
                    dumpLabels.add(i + 1);
                    nativeThreadData.add(snapshots.get(i).nativeMethodThreads());
                    globalRefData.add(snapshots.get(i).totalGlobalRefs());
                    weakRefData.add(snapshots.get(i).totalWeakRefs());
                }
                context.put("dumpLabels", dumpLabels);
                context.put("nativeThreadData", nativeThreadData);
                context.put("globalRefData", globalRefData);
                context.put("weakRefData", weakRefData);

                // Trend info
                if (summary.globalRefsGrowing()) {
                    context.put("globalRefTrend", "growing");
                    context.put("globalRefTrendClass", "change-up");
                }
            }

            // Native threads list (from latest snapshot)
            if (!snapshots.isEmpty()) {
                JniAnalyzer.JniSnapshot latest = snapshots.get(snapshots.size() - 1);
                List<Map<String, Object>> nativeThreads = new ArrayList<>();
                for (var nti : latest.nativeThreads().stream().limit(options.getMaxThreads()).toList()) {
                    Map<String, Object> nt = new LinkedHashMap<>();
                    nt.put("name", nti.name());
                    nt.put("nativeId", nti.nativeId() != null ? "0x" + Long.toHexString(nti.nativeId()) : "?");
                    nt.put("topNativeMethod", nti.topNativeMethod() != null ? nti.topNativeMethod() : "?");
                    nt.put("state", nti.state() != null ? nti.state().name() : "?");
                    nativeThreads.add(nt);
                }
                context.put("nativeThreads", nativeThreads);
                context.put("hasNativeThreads", !nativeThreads.isEmpty());
                context.put("hasMoreNativeThreads", latest.nativeThreads().size() > options.getMaxThreads());
                context.put("moreNativeThreadCount", latest.nativeThreads().size() - nativeThreads.size());
            }
        }

        return context;
    }
}