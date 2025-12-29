package me.bechberger.jstall.parser;

import me.bechberger.jstall.model.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lenient parser for thread dumps from jstack and jcmd output.
 * Designed to be robust and extract as much information as possible.
 */
public class ThreadDumpParser {

    // Thread header patterns
    private static final Pattern THREAD_HEADER_PATTERN = Pattern.compile(
            "\"([^\"]+)\"\\s+#(\\d+)(?:\\s+daemon)?(?:\\s+prio=(\\d+))?.*");

    private static final Pattern THREAD_HEADER_DAEMON = Pattern.compile(".*\\sdaemon\\s.*");

    // Thread state pattern
    private static final Pattern THREAD_STATE_PATTERN = Pattern.compile(
            "java\\.lang\\.Thread\\.State:\\s+(\\w+).*");

    // Native ID pattern
    private static final Pattern NATIVE_ID_PATTERN = Pattern.compile(
            ".*nid=(0x[0-9a-fA-F]+).*");

    // CPU and elapsed time patterns
    private static final Pattern CPU_TIME_PATTERN = Pattern.compile(
            ".*cpu=([0-9.]+)([mun]?s).*");

    private static final Pattern ELAPSED_TIME_PATTERN = Pattern.compile(
            ".*elapsed=([0-9.]+)([mun]?s).*");

    // Stack frame pattern
    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile(
            "at\\s+([^(]+)\\(([^)]+)\\).*");

    // Lock patterns
    private static final Pattern WAITING_ON_PATTERN = Pattern.compile(
            "-\\s+waiting on\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+([^)]+)\\).*");

    private static final Pattern WAITING_TO_LOCK_PATTERN = Pattern.compile(
            "-\\s+waiting to lock\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+([^)]+)\\).*");

    private static final Pattern LOCKED_PATTERN = Pattern.compile(
            "-\\s+locked\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+([^)]+)\\).*");

    private static final Pattern PARKING_PATTERN = Pattern.compile(
            "-\\s+parking to wait for\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+([^)]+)\\).*");

    // JNI info pattern
    private static final Pattern JNI_PATTERN = Pattern.compile(
            "JNI global refs:\\s+(\\d+)(?:,\\s+weak refs:\\s+(\\d+))?.*");

    private static final Pattern JNI_MEMORY_PATTERN = Pattern.compile(
            "JNI global refs memory usage:\\s+(\\d+)(?:,\\s+weak refs:\\s+(\\d+))?.*");

    // Deadlock section patterns
    private static final Pattern DEADLOCK_HEADER_PATTERN = Pattern.compile(
            "Found one Java-level deadlock:");

    private static final Pattern DEADLOCK_THREAD_NAME_PATTERN = Pattern.compile(
            "\"([^\"]+)\":");

    private static final Pattern DEADLOCK_WAITING_PATTERN = Pattern.compile(
            "waiting to lock monitor (0x[0-9a-fA-F]+)\\s+\\(object (0x[0-9a-fA-F]+),\\s+a\\s+([^)]+)\\),.*");

    private static final Pattern DEADLOCK_HELD_BY_PATTERN = Pattern.compile(
            "which is held by \"([^\"]+)\"");

    private static final Pattern DEADLOCK_SUMMARY_PATTERN = Pattern.compile(
            "Found (\\d+) deadlocks?\\.");

    private static final Pattern DEADLOCK_STACK_SECTION_PATTERN = Pattern.compile(
            "Java stack information for the threads listed above:");

    /**
     * Parse a thread dump from a string
     */
    public ThreadDump parse(String content) throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(content));

        Instant timestamp = Instant.now(); // Default to now, override if found
        String jvmInfo = null;
        String sourceType = detectSourceType(content);
        List<ThreadInfo> threads = new ArrayList<>();
        JniInfo jniInfo = null;
        List<DeadlockInfo> deadlockInfos = new ArrayList<>();

        String line;
        ThreadInfoBuilder currentThread = null;
        ThreadInfoBuilder pendingInfo = null; // For state/stack info appearing before thread header
        boolean inDeadlockSection = false;
        boolean isReverseOrder = false; // Track if file is in reverse order

        while ((line = reader.readLine()) != null) {
            line = line.trim();

            if (line.isEmpty()) {
                // Empty line might indicate end of current thread
                if (currentThread != null && !inDeadlockSection) {
                    threads.add(currentThread.build());
                    currentThread = null;
                }
                // Clear pending info on empty line if no thread follows
                if (pendingInfo != null && currentThread == null) {
                    pendingInfo = null;
                }
                continue;
            }

            // Check for deadlock section start
            if (DEADLOCK_HEADER_PATTERN.matcher(line).find()) {
                inDeadlockSection = true;
                // Save any pending thread
                if (currentThread != null) {
                    threads.add(currentThread.build());
                    currentThread = null;
                }
                pendingInfo = null;
                // Parse deadlock section
                DeadlockInfo deadlockInfo = parseDeadlockSection(reader);
                if (deadlockInfo != null) {
                    deadlockInfos.add(deadlockInfo);
                }
                // Note: inDeadlockSection stays true, but we might encounter another deadlock
                inDeadlockSection = false;
                continue;
            }

            // If in deadlock section, skip (already parsed)
            if (inDeadlockSection) {
                continue;
            }

            // Try to extract JVM info from first line
            if (jvmInfo == null && (line.contains("Full thread dump") || line.contains("Thread dump"))) {
                jvmInfo = line;
                continue;
            }

            // Try to parse JNI info
            Matcher jniMatcher = JNI_PATTERN.matcher(line);
            if (jniMatcher.matches()) {
                Integer globalRefs = parseIntSafe(jniMatcher.group(1));
                Integer weakRefs = parseIntSafe(jniMatcher.group(2));
                if (jniInfo != null) {
                    // Merge with existing memory info
                    jniInfo = new JniInfo(globalRefs, weakRefs, jniInfo.globalRefsMemory(), jniInfo.weakRefsMemory());
                } else {
                    jniInfo = new JniInfo(globalRefs, weakRefs, null, null);
                }
                continue;
            }

            Matcher jniMemMatcher = JNI_MEMORY_PATTERN.matcher(line);
            if (jniMemMatcher.matches()) {
                Long globalMem = parseLongSafe(jniMemMatcher.group(1));
                Long weakMem = parseLongSafe(jniMemMatcher.group(2));
                if (jniInfo != null) {
                    jniInfo = new JniInfo(jniInfo.globalRefs(), jniInfo.weakRefs(), globalMem, weakMem);
                } else {
                    jniInfo = new JniInfo(null, null, globalMem, weakMem);
                }
                continue;
            }

            // Try to parse thread header
            if (line.startsWith("\"")) {
                // Save previous thread if any
                if (currentThread != null) {
                    threads.add(currentThread.build());
                }
                currentThread = parseThreadHeader(line);
                // Merge any pending info that appeared before the header
                if (pendingInfo != null) {
                    mergePendingInfo(currentThread, pendingInfo);
                    pendingInfo = null;
                    isReverseOrder = true; // File has info before headers, so it's in reverse order
                }
                continue;
            }

            // If we have a current thread, parse additional info
            if (currentThread != null) {
                parseThreadLine(line, currentThread);
            } else {
                // No current thread yet - might be info appearing before thread header (reverse order)
                // Try to parse state, stack frames, or lock info
                if (couldBeThreadInfo(line)) {
                    if (pendingInfo == null) {
                        pendingInfo = new ThreadInfoBuilder();
                    }
                    parseThreadLine(line, pendingInfo);
                }
            }
        }

        // Don't forget the last thread
        if (currentThread != null) {
            threads.add(currentThread.build());
        }

        // If file was in reverse order, reverse the threads list
        if (isReverseOrder) {
            Collections.reverse(threads);
        }

        return new ThreadDump(timestamp, jvmInfo, threads, jniInfo, sourceType, deadlockInfos);
    }

    private String detectSourceType(String content) {
        // jcmd has explicit "jcmd" in header
        if (content.contains("jcmd") || content.contains("Thread.print")) {
            return "jcmd";
        }
        // jstack typically has "Full thread dump" with VM info (HotSpot, OpenJDK, etc)
        if (content.contains("Full thread dump") || content.contains("Thread dump")) {
            return "jstack";
        }
        return "unknown";
    }

    /**
     * Check if a line could be thread-related info (state, stack frame, lock)
     */
    private boolean couldBeThreadInfo(String line) {
        return line.contains("java.lang.Thread.State:") ||
               line.startsWith("at ") ||
               line.contains("- waiting on") ||
               line.contains("- waiting to lock") ||
               line.contains("- locked") ||
               line.contains("- parking to wait for");
    }

    /**
     * Merge pending info into current thread builder
     */
    private void mergePendingInfo(ThreadInfoBuilder current, ThreadInfoBuilder pending) {
        if (pending.state != null && current.state == null) {
            current.state = pending.state;
        }
        if (!pending.stackTrace.isEmpty()) {
            // Reverse the stack trace since it was read in reverse order
            List<StackFrame> reversedStack = new ArrayList<>(pending.stackTrace);
            Collections.reverse(reversedStack);
            current.stackTrace.addAll(0, reversedStack);
        }
        if (!pending.locks.isEmpty()) {
            // Reverse the locks since they were read in reverse order
            List<LockInfo> reversedLocks = new ArrayList<>(pending.locks);
            Collections.reverse(reversedLocks);
            current.locks.addAll(0, reversedLocks);
        }
        if (pending.waitingOnLock != null && current.waitingOnLock == null) {
            current.waitingOnLock = pending.waitingOnLock;
        }
        if (pending.additionalInfo != null) {
            if (current.additionalInfo == null) {
                current.additionalInfo = pending.additionalInfo;
            } else {
                current.additionalInfo = pending.additionalInfo + "\n" + current.additionalInfo;
            }
        }
    }

    @NotNull
    private ThreadInfoBuilder parseThreadHeader(String line) {
        ThreadInfoBuilder builder = new ThreadInfoBuilder();

        Matcher headerMatcher = THREAD_HEADER_PATTERN.matcher(line);
        if (headerMatcher.matches()) {
            builder.name = headerMatcher.group(1);
            builder.threadId = parseLongSafe(headerMatcher.group(2));
            builder.priority = parseIntSafe(headerMatcher.group(3));
        } else {
            // Lenient: just extract the thread name from quotes
            int firstQuote = line.indexOf('"');
            int lastQuote = line.lastIndexOf('"');
            if (firstQuote >= 0 && lastQuote > firstQuote) {
                builder.name = line.substring(firstQuote + 1, lastQuote);
            } else {
                builder.name = "unknown";
            }
        }

        // Check for daemon - only set if explicitly mentioned
        if (THREAD_HEADER_DAEMON.matcher(line).matches()) {
            builder.daemon = true;
        }

        // Extract native ID
        Matcher nidMatcher = NATIVE_ID_PATTERN.matcher(line);
        if (nidMatcher.matches()) {
            builder.nativeId = parseHexLongSafe(nidMatcher.group(1));
        }

        // Extract CPU time
        Matcher cpuMatcher = CPU_TIME_PATTERN.matcher(line);
        if (cpuMatcher.matches()) {
            builder.cpuTimeMs = parseTimeToMs(cpuMatcher.group(1), cpuMatcher.group(2));
        }

        // Extract elapsed time
        Matcher elapsedMatcher = ELAPSED_TIME_PATTERN.matcher(line);
        if (elapsedMatcher.matches()) {
            builder.elapsedTimeMs = parseTimeToMs(elapsedMatcher.group(1), elapsedMatcher.group(2));
        }

        return builder;
    }

    private void parseThreadLine(String line, ThreadInfoBuilder builder) {
        // Parse thread state
        Matcher stateMatcher = THREAD_STATE_PATTERN.matcher(line);
        if (stateMatcher.matches()) {
            builder.state = parseThreadState(stateMatcher.group(1));
            return;
        }

        // Parse stack frame
        Matcher stackMatcher = STACK_FRAME_PATTERN.matcher(line);
        if (stackMatcher.matches()) {
            StackFrame frame = parseStackFrame(stackMatcher.group(1), stackMatcher.group(2));
            builder.stackTrace.add(frame);
            return;
        }

        // Parse lock info - waiting on
        Matcher waitingMatcher = WAITING_ON_PATTERN.matcher(line);
        if (waitingMatcher.matches()) {
            String lockId = waitingMatcher.group(1);
            String className = waitingMatcher.group(2);
            builder.locks.add(new LockInfo(lockId, className, "waiting on"));
            builder.waitingOnLock = lockId;
            return;
        }

        // Parse lock info - waiting to lock
        Matcher waitingToLockMatcher = WAITING_TO_LOCK_PATTERN.matcher(line);
        if (waitingToLockMatcher.matches()) {
            String lockId = waitingToLockMatcher.group(1);
            String className = waitingToLockMatcher.group(2);
            builder.locks.add(new LockInfo(lockId, className, "waiting on"));
            builder.waitingOnLock = lockId;
            return;
        }

        // Parse lock info - locked
        Matcher lockedMatcher = LOCKED_PATTERN.matcher(line);
        if (lockedMatcher.matches()) {
            String lockId = lockedMatcher.group(1);
            String className = lockedMatcher.group(2);
            builder.locks.add(new LockInfo(lockId, className, "locked"));
            return;
        }

        // Parse lock info - parking
        Matcher parkingMatcher = PARKING_PATTERN.matcher(line);
        if (parkingMatcher.matches()) {
            String lockId = parkingMatcher.group(1);
            String className = parkingMatcher.group(2);
            builder.locks.add(new LockInfo(lockId, className, "parking"));
            builder.waitingOnLock = lockId;
            return;
        }

        // Store any other info (skip if it's whitespace-only or known patterns)
        String trimmed = line.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("at ") && !trimmed.startsWith("-")
                && !trimmed.contains("java.lang.Thread.State:")) {
            if (builder.additionalInfo == null) {
                builder.additionalInfo = trimmed;
            } else {
                builder.additionalInfo += "\n" + trimmed;
            }
        }
    }

    private StackFrame parseStackFrame(String method, String location) {
        // Parse method: "className.methodName"
        int lastDot = method.lastIndexOf('.');
        String className = lastDot > 0 ? method.substring(0, lastDot) : method;
        String methodName = lastDot > 0 ? method.substring(lastDot + 1) : "unknown";

        // Strip module info from location (e.g., "java.base@21.0.1/FileName.java:123" -> "FileName.java:123")
        if (location.contains("/")) {
            location = location.substring(location.lastIndexOf('/') + 1);
        }

        // Parse location: "FileName.java:123" or "Native Method" or "Unknown Source"
        String fileName = null;
        Integer lineNumber = null;
        boolean isNativeMethod = false;

        if (location.equals("Native Method")) {
            isNativeMethod = true;
        } else if (location.equals("Unknown Source")) {
            // Keep fileName as null for Unknown Source
            fileName = null;
        } else {
            int colonPos = location.indexOf(':');
            if (colonPos > 0) {
                fileName = location.substring(0, colonPos);
                lineNumber = parseIntSafe(location.substring(colonPos + 1));
            } else {
                fileName = location;
            }
        }

        return new StackFrame(className, methodName, fileName, lineNumber, isNativeMethod ? true : null);
    }

    private Thread.State parseThreadState(String state) {
        try {
            return Thread.State.valueOf(state);
        } catch (IllegalArgumentException e) {
            // Lenient: default to RUNNABLE if unknown
            return Thread.State.RUNNABLE;
        }
    }

    private Long parseTimeToMs(String value, String unit) {
        try {
            double time = Double.parseDouble(value);
            return switch (unit) {
                case "s", "" -> (long) (time * 1000);
                case "ms" -> (long) time;
                case "us" -> (long) (time / 1000);
                case "ns" -> (long) (time / 1_000_000);
                default -> (long) (time * 1000); // Default to seconds
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private Integer parseIntSafe(String value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private Long parseLongSafe(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private Long parseHexLongSafe(String value) {
        if (value == null) return null;
        try {
            // Remove 0x prefix
            if (value.startsWith("0x") || value.startsWith("0X")) {
                value = value.substring(2);
            }
            return Long.parseLong(value, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse the deadlock section at the end of a thread dump
     */
    private DeadlockInfo parseDeadlockSection(BufferedReader reader) throws IOException {
        List<DeadlockInfo.DeadlockedThread> deadlockedThreads = new ArrayList<>();
        String summary = null;
        String line;

        // First section: deadlock descriptions
        DeadlockInfo.DeadlockedThread currentDeadlockedThread = null;
        String currentThreadName = null;
        String waitingForMonitor = null;
        String waitingForObject = null;
        String waitingForObjectType = null;
        String heldBy = null;

        while ((line = reader.readLine()) != null) {
            line = line.trim();

            if (line.isEmpty()) {
                continue;
            }

            // Check for summary line (e.g., "Found 2 deadlocks.") - ignore it
            Matcher summaryMatcher = DEADLOCK_SUMMARY_PATTERN.matcher(line);
            if (summaryMatcher.find()) {
                summary = line;
                // Continue parsing - don't break on summary lines
                continue;
            }

            // Check for stack information section
            if (DEADLOCK_STACK_SECTION_PATTERN.matcher(line).find()) {
                // Save current thread if any
                if (currentThreadName != null) {
                    deadlockedThreads.add(new DeadlockInfo.DeadlockedThread(
                            currentThreadName, waitingForMonitor, waitingForObject,
                            waitingForObjectType, heldBy, List.of(), List.of()));
                    currentThreadName = null; // Clear to avoid duplicate save at end
                }
                // Parse stack information section
                parseDeadlockStackSection(reader, deadlockedThreads);
                break;
            }

            // Parse thread name
            Matcher threadNameMatcher = DEADLOCK_THREAD_NAME_PATTERN.matcher(line);
            if (threadNameMatcher.find()) {
                // Save previous thread if any
                if (currentThreadName != null) {
                    deadlockedThreads.add(new DeadlockInfo.DeadlockedThread(
                            currentThreadName, waitingForMonitor, waitingForObject,
                            waitingForObjectType, heldBy, List.of(), List.of()));
                }
                currentThreadName = threadNameMatcher.group(1);
                waitingForMonitor = null;
                waitingForObject = null;
                waitingForObjectType = null;
                heldBy = null;
                continue;
            }

            // Parse waiting for lock
            Matcher waitingMatcher = DEADLOCK_WAITING_PATTERN.matcher(line);
            if (waitingMatcher.find()) {
                waitingForMonitor = waitingMatcher.group(1);
                waitingForObject = waitingMatcher.group(2);
                waitingForObjectType = waitingMatcher.group(3);
                continue;
            }

            // Parse held by
            Matcher heldByMatcher = DEADLOCK_HELD_BY_PATTERN.matcher(line);
            if (heldByMatcher.find()) {
                heldBy = heldByMatcher.group(1);
                continue;
            }
        }

        // Save the last thread if not yet saved
        if (currentThreadName != null) {
            deadlockedThreads.add(new DeadlockInfo.DeadlockedThread(
                    currentThreadName, waitingForMonitor, waitingForObject,
                    waitingForObjectType, heldBy, List.of(), List.of()));
        }

        return new DeadlockInfo(deadlockedThreads);
    }

    /**
     * Parse the stack information section of deadlock info
     */
    private void parseDeadlockStackSection(BufferedReader reader,
            List<DeadlockInfo.DeadlockedThread> deadlockedThreads) throws IOException {
        String line;
        String currentThreadName = null;
        List<StackFrame> currentStackTrace = new ArrayList<>();
        List<LockInfo> currentLocks = new ArrayList<>();

        while ((line = reader.readLine()) != null) {
            line = line.trim();

            if (line.isEmpty()) {
                continue;
            }

            // Check for summary line (e.g., "Found 2 deadlocks.") - ignore it
            if (DEADLOCK_SUMMARY_PATTERN.matcher(line).find()) {
                // Ignore summary line, continue parsing
                continue;
            }

            // Check for thread name
            Matcher threadNameMatcher = DEADLOCK_THREAD_NAME_PATTERN.matcher(line);
            if (threadNameMatcher.find()) {
                // Save previous thread
                if (currentThreadName != null) {
                    updateDeadlockedThreadWithStack(deadlockedThreads, currentThreadName,
                            currentStackTrace, currentLocks);
                }
                currentThreadName = threadNameMatcher.group(1);
                currentStackTrace = new ArrayList<>();
                currentLocks = new ArrayList<>();
                continue;
            }

            // Parse stack frame
            Matcher stackMatcher = STACK_FRAME_PATTERN.matcher(line);
            if (stackMatcher.matches()) {
                StackFrame frame = parseStackFrame(stackMatcher.group(1), stackMatcher.group(2));
                currentStackTrace.add(frame);
                continue;
            }

            // Parse lock info - waiting to lock
            if (line.contains("- waiting to lock") || line.contains("- waiting on")) {
                // Try waiting to lock pattern first
                Matcher waitingToLockMatcher = WAITING_TO_LOCK_PATTERN.matcher(line);
                if (waitingToLockMatcher.matches()) {
                    String lockId = waitingToLockMatcher.group(1);
                    String className = waitingToLockMatcher.group(2);
                    currentLocks.add(new LockInfo(lockId, className, "waiting to lock"));
                    continue;
                }
                // Try waiting on pattern
                Matcher waitingMatcher = WAITING_ON_PATTERN.matcher(line);
                if (waitingMatcher.matches()) {
                    String lockId = waitingMatcher.group(1);
                    String className = waitingMatcher.group(2);
                    currentLocks.add(new LockInfo(lockId, className, "waiting to lock"));
                }
                continue;
            }

            // Parse lock info - locked
            Matcher lockedMatcher = LOCKED_PATTERN.matcher(line);
            if (lockedMatcher.matches()) {
                String lockId = lockedMatcher.group(1);
                String className = lockedMatcher.group(2);
                currentLocks.add(new LockInfo(lockId, className, "locked"));
                continue;
            }
        }

        // Save last thread
        if (currentThreadName != null) {
            updateDeadlockedThreadWithStack(deadlockedThreads, currentThreadName,
                    currentStackTrace, currentLocks);
        }
    }

    /**
     * Update a deadlocked thread with stack trace and lock information
     */
    private void updateDeadlockedThreadWithStack(List<DeadlockInfo.DeadlockedThread> deadlockedThreads,
            String threadName, List<StackFrame> stackTrace, List<LockInfo> locks) {
        for (int i = 0; i < deadlockedThreads.size(); i++) {
            DeadlockInfo.DeadlockedThread thread = deadlockedThreads.get(i);
            if (thread.threadName().equals(threadName)) {
                // Replace with updated version
                deadlockedThreads.set(i, new DeadlockInfo.DeadlockedThread(
                        thread.threadName(),
                        thread.waitingForMonitor(),
                        thread.waitingForObject(),
                        thread.waitingForObjectType(),
                        thread.heldBy(),
                        stackTrace,
                        locks
                ));
                break;
            }
        }
    }

    /**
     * Builder for ThreadInfo to accumulate data during parsing
     */
    private static class ThreadInfoBuilder {
        String name;
        Long threadId;
        Long nativeId;
        Integer priority;
        Boolean daemon;
        Thread.State state;
        Long cpuTimeMs;
        Long elapsedTimeMs;
        List<StackFrame> stackTrace = new ArrayList<>();
        List<LockInfo> locks = new ArrayList<>();
        String waitingOnLock;
        String additionalInfo;

        ThreadInfo build() {
            return new ThreadInfo(
                    name,
                    threadId,
                    nativeId,
                    priority,
                    daemon,
                    state,
                    cpuTimeMs,
                    elapsedTimeMs,
                    stackTrace,
                    locks,
                    waitingOnLock,
                    additionalInfo
            );
        }
    }
}