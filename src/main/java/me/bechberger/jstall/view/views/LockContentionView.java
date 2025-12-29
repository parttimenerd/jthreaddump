package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.LockContentionAnalyzer;
import me.bechberger.jstall.model.ThreadInfo;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for LockContentionAnalyzer results.
 * Shows lock contention issues, hot locks, and JFR lock analysis.
 */
public class LockContentionView extends HandlebarsViewRenderer {

    public LockContentionView() {
        super("lock-contention", "lock-contention", LockContentionAnalyzer.LockContentionResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof LockContentionAnalyzer.LockContentionResult contentionResult) {
            LockContentionAnalyzer.LockContentionSummary summary = contentionResult.getLockSummary();

            // Summary data
            context.put("totalContentedLocks", summary.totalContentedLocks());
            context.put("maxWaiters", summary.maxWaiters());
            context.put("hotLocks", summary.hotLocks());
            context.put("hasContention", summary.totalContentedLocks() > 0);

            // Format contentions
            List<Map<String, Object>> contentions = new ArrayList<>();
            int maxToShow = Math.min(options.getMaxThreads(), contentionResult.getContentions().size());
            for (int i = 0; i < maxToShow; i++) {
                LockContentionAnalyzer.LockContention contention = contentionResult.getContentions().get(i);
                contentions.add(formatContention(contention, options));
            }
            context.put("contentions", contentions);
            context.put("hasMoreContentions", contentionResult.getContentions().size() > maxToShow);
            context.put("moreContentionCount", contentionResult.getContentions().size() - maxToShow);

            // JFR analysis if available
            if (contentionResult.hasJfrData()) {
                LockContentionAnalyzer.JfrLockAnalysis jfrAnalysis = contentionResult.getJfrAnalysis();
                Map<String, Object> jfrData = new LinkedHashMap<>();
                jfrData.put("totalEvents", jfrAnalysis.summary().totalEvents());
                jfrData.put("uniqueLocks", jfrAnalysis.summary().uniqueLocks());
                jfrData.put("totalBlockedMs", jfrAnalysis.summary().totalBlockedTime().toMillis());
                jfrData.put("additionalFindingsCount", jfrAnalysis.additionalFindings().size());
                context.put("jfrAnalysis", jfrData);
                context.put("hasJfrData", true);
            } else {
                context.put("hasJfrData", false);
            }
        }

        return context;
    }

    private Map<String, Object> formatContention(LockContentionAnalyzer.LockContention contention,
                                                   OutputOptions options) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("lockId", formatLockId(contention.lockId()));
        info.put("fullLockId", contention.lockId());
        info.put("lockClassName", contention.lockClassName());
        info.put("waiterCount", contention.waiterCount());
        info.put("dumpIndex", contention.dumpIndex());

        // Owner info
        if (contention.owner() != null) {
            Map<String, Object> owner = new LinkedHashMap<>();
            owner.put("name", contention.owner().name());
            owner.put("state", contention.owner().state());
            info.put("owner", owner);
            info.put("hasOwner", true);
        } else {
            info.put("hasOwner", false);
        }

        // Waiter info
        List<Map<String, Object>> waiters = new ArrayList<>();
        int maxWaiters = Math.min(10, contention.waiters().size());
        for (int i = 0; i < maxWaiters; i++) {
            ThreadInfo waiter = contention.waiters().get(i);
            Map<String, Object> waiterInfo = new LinkedHashMap<>();
            waiterInfo.put("name", waiter.name());
            waiterInfo.put("state", waiter.state());
            waiters.add(waiterInfo);
        }
        info.put("waiters", waiters);
        info.put("hasMoreWaiters", contention.waiters().size() > maxWaiters);
        info.put("moreWaiterCount", contention.waiters().size() - maxWaiters);

        return info;
    }

    private String formatLockId(String lockId) {
        if (lockId != null && lockId.length() > 16) {
            return lockId.substring(0, 16) + "...";
        }
        return lockId;
    }
}