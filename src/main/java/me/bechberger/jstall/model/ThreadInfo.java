package me.bechberger.jstall.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Represents information about a single thread from a thread dump
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThreadInfo(
        String name,
        Long threadId,
        Long nativeId,
        Integer priority,
        Boolean daemon,
        Thread.State state,
        Long cpuTimeMs,
        Long elapsedTimeMs,
        List<StackFrame> stackTrace,
        List<LockInfo> locks,
        String waitingOnLock,
        String additionalInfo
) {
    public ThreadInfo {
        // Defensive copy for immutability
        stackTrace = stackTrace != null ? List.copyOf(stackTrace) : List.of();
        locks = locks != null ? List.copyOf(locks) : List.of();
    }

    /**
     * Equals comparison that ignores threadId, nativeId, and waitingOnLock (hex values).
     * Useful for test comparisons where memory addresses differ between runs.
     */
    public boolean equalsIgnoringHexValues(ThreadInfo other) {
        if (this == other) return true;
        if (other == null) return false;
        return java.util.Objects.equals(name, other.name) &&
               // Intentionally ignore threadId (can be hex)
               // Intentionally ignore nativeId (can be hex)
               java.util.Objects.equals(priority, other.priority) &&
               java.util.Objects.equals(daemon, other.daemon) &&
               java.util.Objects.equals(state, other.state) &&
               java.util.Objects.equals(cpuTimeMs, other.cpuTimeMs) &&
               java.util.Objects.equals(elapsedTimeMs, other.elapsedTimeMs) &&
               java.util.Objects.equals(stackTrace, other.stackTrace) &&
               locksEqualsIgnoringHexValues(locks, other.locks) &&
               // Intentionally ignore waitingOnLock (hex value)
               java.util.Objects.equals(additionalInfo, other.additionalInfo);
    }

    private static boolean locksEqualsIgnoringHexValues(List<LockInfo> list1, List<LockInfo> list2) {
        if (list1 == list2) return true;
        if (list1 == null || list2 == null) return false;
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            LockInfo lock1 = list1.get(i);
            LockInfo lock2 = list2.get(i);
            if (lock1 == lock2) continue;
            if (lock1 == null || lock2 == null) return false;
            if (!lock1.equalsIgnoringHexValues(lock2)) return false;
        }
        return true;
    }
}