package me.bechberger.jstall.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Represents a complete thread dump
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThreadDump(
        Instant timestamp,
        String jvmInfo,
        List<ThreadInfo> threads,
        JniInfo jniInfo,
        String sourceType,  // "jstack" or "jcmd"
        List<DeadlockInfo> deadlockInfos // Deadlock information if detected
) {
    public ThreadDump {
        threads = threads != null ? List.copyOf(threads) : List.of();
    }

    public ThreadDump(Instant timestamp, String jvmInfo, List<ThreadInfo> threads, JniInfo jniInfo, String sourceType) {
        this(timestamp, jvmInfo, threads, jniInfo, sourceType, List.of());
    }

    /**
     * Equals comparison that ignores hex values (memory addresses, thread IDs, etc.).
     * Useful for test comparisons where memory addresses differ between runs.
     */
    public boolean equalsIgnoringHexValues(ThreadDump other) {
        if (this == other) return true;
        if (other == null) return false;
        return java.util.Objects.equals(timestamp, other.timestamp) &&
               java.util.Objects.equals(jvmInfo, other.jvmInfo) &&
               threadsEqualsIgnoringHexValues(threads, other.threads) &&
               java.util.Objects.equals(jniInfo, other.jniInfo) &&
               java.util.Objects.equals(sourceType, other.sourceType) &&
               deadlocksEqualsIgnoringHexValues(deadlockInfos, other.deadlockInfos);
    }

    private static boolean threadsEqualsIgnoringHexValues(List<ThreadInfo> list1, List<ThreadInfo> list2) {
        if (list1 == list2) return true;
        if (list1 == null || list2 == null) return false;
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            ThreadInfo t1 = list1.get(i);
            ThreadInfo t2 = list2.get(i);
            if (t1 == t2) continue;
            if (t1 == null || t2 == null) return false;
            if (!t1.equalsIgnoringHexValues(t2)) return false;
        }
        return true;
    }

    private static boolean deadlocksEqualsIgnoringHexValues(List<DeadlockInfo> list1, List<DeadlockInfo> list2) {
        if (list1 == list2) return true;
        if (list1 == null || list2 == null) return false;
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            DeadlockInfo d1 = list1.get(i);
            DeadlockInfo d2 = list2.get(i);
            if (d1 == d2) continue;
            if (d1 == null || d2 == null) return false;
            if (!d1.equalsIgnoringHexValues(d2)) return false;
        }
        return true;
    }
}