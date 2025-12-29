package me.bechberger.jstall.view.views;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.DeadlockAnalyzer;
import me.bechberger.jstall.model.LockInfo;
import me.bechberger.jstall.model.StackFrame;
import me.bechberger.jstall.model.ThreadInfo;
import me.bechberger.jstall.view.HandlebarsViewRenderer;
import me.bechberger.jstall.view.OutputOptions;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * View renderer for DeadlockAnalyzer results.
 * Shows deadlock chains, affected threads, and lock relationships.
 */
public class DeadlockView extends HandlebarsViewRenderer {

    public DeadlockView() {
        super("deadlock", "deadlock", DeadlockAnalyzer.DeadlockResult.class);
    }

    @Override
    protected Map<String, Object> buildContext(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        Map<String, Object> context = super.buildContext(result, options);

        if (result instanceof DeadlockAnalyzer.DeadlockResult deadlockResult) {
            context.put("hasDeadlocks", deadlockResult.hasDeadlocks());
            context.put("deadlocks", formatDeadlocks(deadlockResult.getDeadlocks(), options));
            context.put("deadlockCount", deadlockResult.getDeadlocks().size());

            DeadlockAnalyzer.DeadlockSummary summary = deadlockResult.getDeadlockSummary();
            if (summary != null) {
                context.put("totalDeadlocks", summary.totalDeadlocks());
                context.put("jvmReportedCount", summary.jvmReportedCount());
                context.put("maxCycleSize", summary.maxCycleSize());
            }

            // Multi-dump persistence tracking
            DeadlockAnalyzer.DeadlockPersistenceAnalysis persistence = deadlockResult.getPersistence();
            if (persistence != null) {
                context.put("hasPersistenceData", true);
                context.put("hasPersistentDeadlocks", persistence.hasPersistentDeadlocks());

                // Format persistent deadlocks with enhanced trend info
                List<Map<String, Object>> persistentList = new ArrayList<>();
                for (DeadlockAnalyzer.DeadlockPersistence p : persistence.persistentDeadlocks()) {
                    Map<String, Object> pInfo = new LinkedHashMap<>();
                    pInfo.put("threadNames", p.representative().getThreadNames());
                    pInfo.put("firstSeen", p.firstSeenDumpIndex() + 1);
                    pInfo.put("lastSeen", p.lastSeenDumpIndex() + 1);
                    pInfo.put("occurrences", p.occurrenceCount());
                    pInfo.put("isPersistent", p.isPersistent());
                    pInfo.put("isOngoing", p.isOngoing());
                    pInfo.put("dumpSpan", p.dumpSpan());

                    // Determine status
                    Map<Integer, List<DeadlockAnalyzer.DetectedDeadlock>> byDump = persistence.deadlocksByDump();
                    int totalDumps = byDump.keySet().stream().max(Integer::compareTo).orElse(0) + 1;
                    boolean isResolved = p.lastSeenDumpIndex() < totalDumps - 1;
                    boolean appearedLater = p.firstSeenDumpIndex() > 0;

                    pInfo.put("isResolved", isResolved);
                    pInfo.put("appearedLater", appearedLater);
                    pInfo.put("totalDumps", totalDumps);

                    // Status message
                    String status;
                    if (isResolved) {
                        status = "RESOLVED (last seen in dump " + (p.lastSeenDumpIndex() + 1) + ")";
                    } else if (p.isPersistent() && p.isOngoing()) {
                        status = "ONGOING CRITICAL";
                    } else if (appearedLater) {
                        status = "APPEARED (first seen in dump " + (p.firstSeenDumpIndex() + 1) + ")";
                    } else {
                        status = "TRANSIENT";
                    }
                    pInfo.put("status", status);

                    // Calculate persistence percentage
                    double persistencePercent = (double) p.occurrenceCount() / totalDumps * 100;
                    pInfo.put("persistencePercent", String.format("%.0f%%", persistencePercent));

                    persistentList.add(pInfo);
                }
                context.put("persistentDeadlocks", persistentList);

                // Per-dump deadlock counts for chart and timeline
                Map<Integer, List<DeadlockAnalyzer.DetectedDeadlock>> byDump = persistence.deadlocksByDump();
                int maxDump = byDump.keySet().stream().max(Integer::compareTo).orElse(0);
                List<Integer> dumpLabels = new ArrayList<>();
                List<Integer> deadlockCounts = new ArrayList<>();
                List<String> dumpStatus = new ArrayList<>();

                for (int i = 0; i <= maxDump; i++) {
                    dumpLabels.add(i + 1);
                    int count = byDump.getOrDefault(i, List.of()).size();
                    deadlockCounts.add(count);

                    // Determine status for each dump
                    String status;
                    if (count == 0) {
                        status = "CLEAR ✓";
                    } else if (i > 0) {
                        int prevCount = byDump.getOrDefault(i - 1, List.of()).size();
                        if (prevCount == 0 && count > 0) {
                            status = "APPEARED ⚠";
                        } else if (prevCount > 0 && count == 0) {
                            status = "RESOLVED ✓";
                        } else if (count > prevCount) {
                            status = "WORSENING ↑";
                        } else if (count < prevCount) {
                            status = "IMPROVING ↓";
                        } else {
                            status = "PERSISTENT 🔴";
                        }
                    } else {
                        status = count > 0 ? "PRESENT ⚠" : "CLEAR ✓";
                    }
                    dumpStatus.add(status);
                }

                context.put("isMultiDump", maxDump > 0);
                context.put("dumpLabels", dumpLabels);
                context.put("deadlockCounts", deadlockCounts);
                context.put("dumpStatus", dumpStatus);
                context.put("maxOccurrences", persistence.getMaxOccurrences());

                // Calculate trend statistics
                int totalDumps = maxDump + 1;
                int dumpsWithDeadlocks = (int) deadlockCounts.stream().filter(c -> c > 0).count();
                int dumpsClear = totalDumps - dumpsWithDeadlocks;

                context.put("totalDumps", totalDumps);
                context.put("dumpsWithDeadlocks", dumpsWithDeadlocks);
                context.put("dumpsClear", dumpsClear);

                // Determine overall trend
                String overallTrend;
                if (dumpsClear == totalDumps) {
                    overallTrend = "ALL CLEAR";
                } else if (dumpsWithDeadlocks == totalDumps) {
                    overallTrend = "CRITICAL - ALWAYS PRESENT";
                } else {
                    int firstDeadlock = -1;
                    int lastDeadlock = -1;
                    for (int i = 0; i <= maxDump; i++) {
                        if (deadlockCounts.get(i) > 0) {
                            if (firstDeadlock == -1) firstDeadlock = i;
                            lastDeadlock = i;
                        }
                    }

                    if (lastDeadlock < maxDump) {
                        overallTrend = "IMPROVING - RESOLVED";
                    } else if (firstDeadlock > 0) {
                        overallTrend = "DEGRADING - NEW DEADLOCK";
                    } else {
                        overallTrend = "INTERMITTENT";
                    }
                }
                context.put("overallTrend", overallTrend);

            } else {
                context.put("hasPersistenceData", false);
                context.put("isMultiDump", false);
            }
        }

        return context;
    }

    private List<Map<String, Object>> formatDeadlocks(List<DeadlockAnalyzer.DetectedDeadlock> deadlocks,
                                                       OutputOptions options) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = 0; i < deadlocks.size(); i++) {
            DeadlockAnalyzer.DetectedDeadlock deadlock = deadlocks.get(i);
            Map<String, Object> dlInfo = new LinkedHashMap<>();
            dlInfo.put("index", i + 1);
            dlInfo.put("jvmReported", deadlock.jvmReported());
            dlInfo.put("dumpIndex", deadlock.dumpIndex());
            dlInfo.put("threadCount", deadlock.size());
            dlInfo.put("threadNames", deadlock.getThreadNames());

            // Format thread chain
            List<Map<String, Object>> threadChain = new ArrayList<>();
            for (ThreadInfo thread : deadlock.threads()) {
                Map<String, Object> threadInfo = new LinkedHashMap<>();
                threadInfo.put("name", thread.name());
                threadInfo.put("state", thread.state());
                threadInfo.put("waitingOn", thread.waitingOnLock());

                // Find what lock this thread is waiting for
                if (thread.locks() != null) {
                    for (LockInfo lock : thread.locks()) {
                        if ("waiting to lock".equals(lock.lockType())) {
                            threadInfo.put("waitingForLock", lock.lockId());
                            threadInfo.put("waitingForClass", lock.className());
                            break;
                        }
                    }
                }

                // Find held locks
                List<String> heldLocks = new ArrayList<>();
                if (thread.locks() != null) {
                    for (LockInfo lock : thread.locks()) {
                        if ("locked".equals(lock.lockType())) {
                            heldLocks.add(lock.lockId());
                        }
                    }
                }
                threadInfo.put("heldLocks", heldLocks);

                // Include abbreviated stack trace if verbose
                if (options.isVerbose() && thread.stackTrace() != null) {
                    List<String> stackLines = new ArrayList<>();
                    int maxDepth = Math.min(options.getMaxStackDepth(), thread.stackTrace().size());
                    for (int j = 0; j < maxDepth; j++) {
                        StackFrame frame = thread.stackTrace().get(j);
                        stackLines.add(formatStackFrame(frame));
                    }
                    if (thread.stackTrace().size() > maxDepth) {
                        stackLines.add("... " + (thread.stackTrace().size() - maxDepth) + " more");
                    }
                    threadInfo.put("stackTrace", stackLines);
                }

                threadChain.add(threadInfo);
            }
            dlInfo.put("threads", threadChain);

            result.add(dlInfo);
        }

        return result;
    }

    private String formatStackFrame(StackFrame frame) {
        StringBuilder sb = new StringBuilder();
        sb.append("at ");
        if (frame.className() != null) {
            sb.append(frame.className());
            if (frame.methodName() != null) {
                sb.append(".").append(frame.methodName());
            }
        } else if (frame.methodName() != null) {
            sb.append(frame.methodName());
        }
        if (frame.fileName() != null) {
            sb.append("(").append(frame.fileName());
            if (frame.lineNumber() != null && frame.lineNumber() > 0) {
                sb.append(":").append(frame.lineNumber());
            }
            sb.append(")");
        }
        return sb.toString();
    }
}