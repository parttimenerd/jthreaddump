package me.bechberger.jthreaddump.model;

import java.util.List;

/**
 * Compact pretty-printers for the data model.
 *
 * <p>Intent: readable and information-complete output, stable enough for tests/logging.
 */
public final class PrettyPrinter {

    private PrettyPrinter() {
    }

    /**
     * Pretty-print a full {@link ThreadDump}.
     *
     * <p>The output is designed to be:
     * <ul>
     *   <li><b>compact</b>: mostly single-line key/value pairs</li>
     *   <li><b>information-complete</b>: no model fields are intentionally omitted</li>
     *   <li><b>log-friendly</b>: no ANSI color codes, no platform-specific formatting</li>
     * </ul>
     *
     * <p><b>Compatibility:</b> formatting might evolve between minor versions.
     * If you need a stable machine-readable format, use the JSON-annotated model.
     */
    public static String dump(ThreadDump dump) {
        StringBuilder sb = new StringBuilder(8_192);

        // Header
        if (dump.timestamp() != null) {
            sb.append("timestamp=").append(dump.timestamp()).append('\n');
        }
        if (dump.sourceType() != null) {
            sb.append("source=").append(dump.sourceType()).append('\n');
        }
        if (dump.jvmInfo() != null) {
            sb.append("jvm=").append(oneLine(dump.jvmInfo())).append('\n');
        }
        if (dump.jniInfo() != null) {
            sb.append("jni=").append(jni(dump.jniInfo())).append('\n');
        }

        // Threads
        List<ThreadInfo> threads = dump.threads();
        sb.append("threads[").append(threads.size()).append("]").append('\n');
        for (ThreadInfo t : threads) {
            sb.append(thread(t)).append('\n');
        }

        // Deadlocks
        List<DeadlockInfo> deadlocks = dump.deadlockInfos();
        if (deadlocks != null && !deadlocks.isEmpty()) {
            sb.append("deadlocks[").append(deadlocks.size()).append("]").append('\n');
            for (DeadlockInfo d : deadlocks) {
                sb.append(deadlock(d)).append('\n');
            }
        }

        // Don't end with an extra blank line
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }

        return sb.toString();
    }

    /**
     * Pretty-print a single {@link ThreadInfo}.
     *
     * <p>Compact one-line summary, plus an indented stack trace (if present).
     */
    public static String thread(ThreadInfo t) {
        // One-line summary + optional detail blocks
        StringBuilder sb = new StringBuilder(512);
        sb.append('"').append(nullToEmpty(t.name())).append('"');
        if (t.threadId() != null) sb.append(" id=").append(t.threadId());
        if (t.nativeId() != null) sb.append(" nid=").append(t.nativeId());
        if (t.priority() != null) sb.append(" prio=").append(t.priority());
        if (t.daemon() != null) sb.append(" daemon=").append(t.daemon());
        if (t.state() != null) sb.append(" state=").append(t.state());
        if (t.cpuTimeSec() != null) sb.append(" cpu=").append(trimDouble(t.cpuTimeSec())).append('s');
        if (t.elapsedTimeSec() != null) sb.append(" elapsed=").append(trimDouble(t.elapsedTimeSec())).append('s');
        if (t.carryingVirtualThreadId() != null) sb.append(" carrier=").append(t.carryingVirtualThreadId());

        if (t.additionalInfo() != null && !t.additionalInfo().isBlank()) {
            sb.append(" info=").append(oneLine(t.additionalInfo()));
        }

        List<LockInfo> locks = t.locks();
        if (locks != null && !locks.isEmpty()) {
            sb.append(" locks=");
            for (int i = 0; i < locks.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(lock(locks.get(i)));
            }
        }

        List<StackFrame> frames = t.stackTrace();
        if (frames != null && !frames.isEmpty()) {
            sb.append('\n');
            for (StackFrame f : frames) {
                sb.append("  ").append(f.toString()).append('\n');
            }
            // trim trailing newline
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * Pretty-print {@link DeadlockInfo}.
     *
     * <p>Shows the cycle in a single line and then per-thread details.
     */
    public static String deadlock(DeadlockInfo d) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("cycle[").append(d.threads().size()).append("]: ");
        for (int i = 0; i < d.threads().size(); i++) {
            DeadlockInfo.DeadlockedThread t = d.threads().get(i);
            if (i > 0) sb.append(" -> ");
            sb.append(nullToEmpty(t.threadName()));
            if (t.waitingForObjectType() != null) {
                sb.append(" waits(").append(t.waitingForObjectType()).append(')');
            }
            if (t.heldBy() != null) sb.append(" heldBy=").append(t.heldBy());
        }
        sb.append(" -> ").append(nullToEmpty(d.threads().getFirst().threadName()));

        // Show per-thread details, but keep compact.
        for (DeadlockInfo.DeadlockedThread t : d.threads()) {
            sb.append('\n').append("  ").append(nullToEmpty(t.threadName()));
            if (t.waitingForMonitor() != null) sb.append(" waitingForMonitor=").append(t.waitingForMonitor());
            if (t.waitingForObject() != null) sb.append(" waitingForObject=").append(t.waitingForObject());
            if (t.waitingForObjectType() != null) sb.append(" type=").append(t.waitingForObjectType());
            if (t.heldBy() != null) sb.append(" heldBy=").append(t.heldBy());

            List<LockInfo> locks = t.locks();
            if (locks != null && !locks.isEmpty()) {
                sb.append(" locks=");
                for (int i = 0; i < locks.size(); i++) {
                    if (i > 0) sb.append("; ");
                    sb.append(lock(locks.get(i)));
                }
            }

            List<StackFrame> frames = t.stackTrace();
            if (frames != null && !frames.isEmpty()) {
                sb.append('\n');
                for (StackFrame f : frames) {
                    sb.append("    ").append(f.toString()).append('\n');
                }
                sb.setLength(sb.length() - 1);
            }
        }

        return sb.toString();
    }

    /**
     * Pretty-print {@link JniInfo}.
     */
    public static String jni(JniInfo j) {
        if (j == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("globalRefs=").append(j.globalRefs());
        if (j.weakRefs() != null) sb.append(" weakRefs=").append(j.weakRefs());
        if (j.globalRefsMemory() != null) sb.append(" globalMem=").append(j.globalRefsMemory());
        if (j.weakRefsMemory() != null) sb.append(" weakMem=").append(j.weakRefsMemory());
        return sb.toString();
    }

    private static String lock(LockInfo l) {
        if (l == null) return "<null-lock>";
        // Use existing toString but avoid newlines.
        return oneLine(l.toString());
    }

    private static String oneLine(String s) {
        if (s == null) return "null";
        return s.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String trimDouble(double d) {
        // Keep readable, avoid 0.046880000000000005-style output.
        String s = Double.toString(d);
        if (s.indexOf('E') >= 0 || s.indexOf('e') >= 0) return s;
        if (s.indexOf('.') < 0) return s;
        // trim trailing zeros
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}