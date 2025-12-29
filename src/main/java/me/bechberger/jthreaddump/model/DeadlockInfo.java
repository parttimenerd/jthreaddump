package me.bechberger.jthreaddump.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Represents a deadlock cycle detected in a thread dump
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeadlockInfo(List<DeadlockedThread> threads) {
    public DeadlockInfo {
        threads = threads != null ? List.copyOf(threads) : List.of();
    }

    /**
     * Represents a thread involved in a deadlock
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeadlockedThread(
            String threadName,
            String waitingForMonitor,
            String waitingForObject,
            String waitingForObjectType,
            String heldBy,
            List<StackFrame> stackTrace,
            List<LockInfo> locks
    ) {
        public DeadlockedThread {
            stackTrace = stackTrace != null ? List.copyOf(stackTrace) : List.of();
            locks = locks != null ? List.copyOf(locks) : List.of();
        }

        /**
         * Equals comparison that ignores hex values (waitingForMonitor, waitingForObject).
         * Useful for test comparisons where memory addresses differ between runs.
         */
        public boolean equalsIgnoringHexValues(DeadlockedThread other) {
            if (this == other) return true;
            if (other == null) return false;
            return java.util.Objects.equals(threadName, other.threadName) &&
                   // Intentionally ignore waitingForMonitor (hex value)
                   // Intentionally ignore waitingForObject (hex value)
                   java.util.Objects.equals(waitingForObjectType, other.waitingForObjectType) &&
                   java.util.Objects.equals(heldBy, other.heldBy) &&
                   stackTraceEqualsIgnoringHexValues(stackTrace, other.stackTrace) &&
                   locksEqualsIgnoringHexValues(locks, other.locks);
        }

        private static boolean stackTraceEqualsIgnoringHexValues(List<StackFrame> list1, List<StackFrame> list2) {
            if (list1 == list2) return true;
            if (list1 == null || list2 == null) return false;
            if (list1.size() != list2.size()) return false;
            for (int i = 0; i < list1.size(); i++) {
                if (!java.util.Objects.equals(list1.get(i), list2.get(i))) return false;
            }
            return true;
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

    /**
     * Equals comparison that ignores hex values in deadlocked threads.
     * Useful for test comparisons where memory addresses differ between runs.
     */
    public boolean equalsIgnoringHexValues(DeadlockInfo other) {
        if (this == other) return true;
        if (other == null) return false;
        if (threads.size() != other.threads.size()) return false;
        for (int i = 0; i < threads.size(); i++) {
            if (!threads.get(i).equalsIgnoringHexValues(other.threads.get(i))) {
                return false;
            }
        }
        return true;
    }
}