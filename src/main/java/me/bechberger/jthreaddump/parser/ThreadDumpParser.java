package me.bechberger.jthreaddump.parser;

import me.bechberger.jthreaddump.model.*;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lenient parser for thread dumps from jstack and jcmd output.
 * Designed to be robust and extract as much information as possible.
 */
public final class ThreadDumpParser {

    // Thread header patterns
    private static final Pattern THREAD_HEADER_PATTERN = Pattern.compile(
            "\"([^\"]+)\"\\s+#(\\d+)(?:\\s+\\[(\\d+)\\])?(?:\\s+(daemon))?(?:\\s+prio=(\\d+))?.*");

    private static final Pattern THREAD_HEADER_DAEMON = Pattern.compile(".*\\sdaemon\\s.*");

    // Virtual thread pattern
    private static final Pattern CARRYING_VIRTUAL_THREAD_PATTERN = Pattern.compile(
            "\\s*Carrying virtual thread #(\\d+).*");

    // Unquoted VM/GC thread header pattern (SAP/JDK8 prints e.g. "VM Thread" os_prio=2 cpu=...)
    private static final Pattern UNQUOTED_THREAD_HEADER_PATTERN = Pattern.compile(
            "\"?([^\"]+?)\"?\\s+(?:os_prio=\\d+).*"
    );

    private static final Pattern UNQUOTED_THREAD_NAME_PATTERN = Pattern.compile(
            "^\"?([^\"]+?)\"?\\s+os_prio=.*"
    );

    private static final Pattern RUNNABLE_WORD_PATTERN = Pattern.compile(".*\\brunnable\\b.*");

    private static final Pattern THREAD_STATE_PATTERN = Pattern.compile(
            "java\\.lang\\.Thread\\.State:\\s+(\\w+).*");

    // Native ID pattern
    private static final Pattern NATIVE_ID_PATTERN = Pattern.compile(
            ".*?\\bnid=(0x[0-9a-fA-F]+)\\b.*");

    // SAP-style priority can appear on a separate line like: prio=5 tid=... nid=...
    private static final Pattern PRIO_PATTERN = Pattern.compile(".*\\bprio=(\\d+).*");

    // CPU and elapsed time patterns
    private static final Pattern CPU_TIME_PATTERN = Pattern.compile(
            ".*\\bcpu=([0-9.]+)(?:\\s+\\[reset\\s+[0-9.]+\\])?\\s*([mun]?s)\\b.*");

    private static final Pattern ELAPSED_TIME_PATTERN = Pattern.compile(
            ".*\\belapsed=([0-9.]+)(?:\\s+\\[reset\\s+[0-9.]+\\])?\\s*([mun]?s)\\b.*");

    // Stack frame pattern
    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile(
            "at\\s+(.+)\\(([^)]+)\\).*"
    );

    // Lock patterns
    private static final Pattern WAITING_ON_PATTERN = Pattern.compile(
            "-\\s+waiting on\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+([^)]+)\\).*");

    private static final Pattern WAITING_TO_LOCK_PATTERN = Pattern.compile(
            "-\\s+waiting to lock\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+([^)]+)\\).*");

    private static final Pattern WAITING_TO_RELOCK_PATTERN = Pattern.compile(
            "-\\s+waiting to re-lock in wait\\(\\)\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+([^)]+)\\).*");

    private static final Pattern LOCKED_PATTERN = Pattern.compile(
            "-\\s+locked\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+([^)]+)\\).*");

    private static final Pattern PARKING_PATTERN = Pattern.compile(
            "-\\s+parking to wait for\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a\\s+([^)]+)\\)(?:,\\s+owner\\s+#(\\d+))?.*");

    // JNI info pattern
    private static final Pattern JNI_PATTERN = Pattern.compile(
            "JNI global refs:\\s+(\\d+)(?:,\\s+weak refs:\\s+(\\d+))?.*");

    private static final Pattern JNI_MEMORY_PATTERN = Pattern.compile(
            "JNI global refs memory usage:\\s+(\\d+)(?:,\\s+weak refs:\\s+(\\d+))?.*");

    // Deadlock section patterns
    private static final Pattern DEADLOCK_HEADER_PATTERN = Pattern.compile(
            "Found one Java-level deadlock:");

    private static final Pattern DEADLOCK_THREAD_NAME_PATTERN = Pattern.compile(
            "\\\"([^\\\"]+)\\\":");

    private static final Pattern DEADLOCK_WAITING_PATTERN = Pattern.compile(
            "waiting to lock monitor (0x[0-9a-fA-F]+)\\s+\\(object (0x[0-9a-fA-F]+),\\s+a\\s+([^)]+)\\),.*");

    // SAP JVM deadlock format:
    //   waiting for ownable synchronizer 0x..., (a ...),
    //   which is held by "Thread" (tid=0x...)
    private static final Pattern DEADLOCK_WAITING_OWNABLE_SYNCHRONIZER_PATTERN = Pattern.compile(
            "waiting for ownable synchronizer (0x[0-9a-fA-F]+),\\s+\\(a\\s+([^)]+)\\),?\\s*");

    // like above, but the 0x... can be without a 0x prefix in some variants; keep as a fallback
    private static final Pattern DEADLOCK_WAITING_OWNABLE_SYNCHRONIZER_PATTERN_FALLBACK = Pattern.compile(
            "waiting for ownable synchronizer ([0-9a-fA-Fx]+),\\s+\\(a\\s+([^)]+)\\),?\\s*");

    // SAP 'indirectly waiting' section header
    private static final Pattern DEADLOCK_INDIRECT_WAITERS_HEADER_PATTERN = Pattern.compile(
            "Threads \\((?:in)?directly\\) waiting on deadlocked thread \\\"([^\\\"]+)\\\".*");

    // Thread label in indirect section: "Indirectly deadlocked" (tid=0x...):
    private static final Pattern DEADLOCK_INDIRECT_THREAD_LABEL_PATTERN = Pattern.compile(
            "\\\"([^\\\"]+)\\\"\\s+\\(tid=.*\\):");

    // SAP deadlock thread line can appear as: "Deadlock A" (tid=0x...): (note: no colon-only after quote)
    private static final Pattern SAP_DEADLOCK_THREAD_LABEL_PATTERN = Pattern.compile(
            "\\\"([^\\\"]+)\\\"\\s*(?:\\(tid=.*\\))?:");

    private static final Pattern DEADLOCK_HELD_BY_PATTERN = Pattern.compile(
            "which is held by \\\"([^\\\"]+)\\\"");

    private static final Pattern DEADLOCK_SUMMARY_PATTERN = Pattern.compile(
            "Found (\\d+) deadlocks?\\.");

    private static final Pattern DEADLOCK_STACK_SECTION_PATTERN = Pattern.compile(
            "Java stack information for the threads listed above:");

    private ThreadDumpParser() {
        // Prevent instantiation
    }

    /**
     * Parse a thread dump from a string
     */
    public static ThreadDump parse(String content) throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(content));

        Instant timestamp = null; // Only set if found, never guess
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
        boolean parsedTimestamp = false;
        boolean skippedPidPrefix = false;

        while ((line = reader.readLine()) != null) {
            line = line.trim();

            // Some thread dumps start with a PID prefix line like "26242:".
            // If present, ignore it and continue looking for the first real timestamp line.
            if (!parsedTimestamp && !skippedPidPrefix && line.matches("\\d+:")) {
                skippedPidPrefix = true;
                continue;
            }

            if (!parsedTimestamp) {
                Instant parsed = tryParseTimestamp(line);
                if (parsed != null) {
                    timestamp = parsed;
                    parsedTimestamp = true;
                    continue;
                }
                // Don't try again if the first non-empty line isn't a timestamp
                if (!line.isEmpty()) {
                    parsedTimestamp = true;
                }
            }

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

            // Try to parse thread header (quoted)
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

            // Try to parse unquoted VM/GC thread header (e.g. "VM Thread" os_prio=2 cpu=...)
            // These threads typically have no java.lang.Thread.State line nor stack trace.
            if (currentThread == null && line.contains("os_prio=") && !line.startsWith("io=") && !line.startsWith("tid=")) {
                Matcher nameMatcher = UNQUOTED_THREAD_NAME_PATTERN.matcher(line);
                if (nameMatcher.matches()) {
                    currentThread = ThreadInfoBuilder.create();
                    currentThread.name(nameMatcher.group(1).trim());

                    Matcher cpuMatcher = CPU_TIME_PATTERN.matcher(line);
                    if (cpuMatcher.matches()) {
                        currentThread.cpuTimeSec(parseTimeToSeconds(cpuMatcher.group(1), cpuMatcher.group(2)));
                    }
                    Matcher elapsedMatcher = ELAPSED_TIME_PATTERN.matcher(line);
                    if (elapsedMatcher.matches()) {
                        currentThread.elapsedTimeSec(parseTimeToSeconds(elapsedMatcher.group(1), elapsedMatcher.group(2)));
                    }

                    // Infer state from header keywords (SAP prints runnable/waiting on condition)
                    if (RUNNABLE_WORD_PATTERN.matcher(line).matches()) {
                        currentThread.state(Thread.State.RUNNABLE);
                    }
                    continue;
                }
            }

            // If we have a current thread, parse additional info
            if (currentThread != null) {
                parseThreadLine(line, currentThread);
            } else {
                // No current thread yet - might be info appearing before thread header (reverse order)
                // Try to parse state, stack frames, or lock info
                if (couldBeThreadInfo(line)) {
                    if (pendingInfo == null) {
                        pendingInfo = ThreadInfoBuilder.create();
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

    private static String detectSourceType(String content) {
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
    private static boolean couldBeThreadInfo(String line) {
        return line.contains("java.lang.Thread.State:") ||
               line.startsWith("at ") ||
               line.contains("- waiting on") ||
               line.contains("- waiting to lock") ||
               line.contains("- waiting to re-lock in wait()") ||
               line.contains("- locked") ||
               line.contains("- parking to wait for");
    }

    /**
     * Merge pending info into current thread builder
     */
    private static void mergePendingInfo(ThreadInfoBuilder current, ThreadInfoBuilder pending) {
        // Build pending to get immutable snapshot
        ThreadInfo pendingInfo = pending.build();

        // Merge state
        if (pendingInfo.state() != null) {
            current.state(pendingInfo.state());
        }

        // Merge stack trace (reversed since read in reverse order)
        if (!pendingInfo.stackTrace().isEmpty()) {
            List<StackFrame> reversedStack = new ArrayList<>(pendingInfo.stackTrace());
            Collections.reverse(reversedStack);
            for (StackFrame frame : reversedStack) {
                current.addStackFrame(frame);
            }
        }

        // Merge locks (reversed since read in reverse order)
        if (!pendingInfo.locks().isEmpty()) {
            List<LockInfo> reversedLocks = new ArrayList<>(pendingInfo.locks());
            Collections.reverse(reversedLocks);
            for (LockInfo lock : reversedLocks) {
                current.addLock(lock);
            }
        }

        // Merge virtual thread ID
        if (pendingInfo.carryingVirtualThreadId() != null) {
            current.carryingVirtualThreadId(pendingInfo.carryingVirtualThreadId());
        }

        // Merge additional info
        if (pendingInfo.additionalInfo() != null) {
            current.additionalInfo(pendingInfo.additionalInfo());
        }
    }

    private static ThreadInfoBuilder parseThreadHeader(String line) {
        ThreadInfoBuilder builder = ThreadInfoBuilder.create();

        Matcher headerMatcher = THREAD_HEADER_PATTERN.matcher(line);
        if (headerMatcher.matches()) {
            builder.name(headerMatcher.group(1));
            builder.threadId(parseLongSafe(headerMatcher.group(2)));
            // Group 3 is the virtual thread ID in square brackets
            builder.carryingVirtualThreadId(parseLongSafe(headerMatcher.group(3)));
            // Group 4 is the optional 'daemon' marker when present; group 5 is priority
            String daemonGroup = headerMatcher.group(4);
            builder.priority(parseIntSafe(headerMatcher.group(5)));

            // Explicitly set daemon flag based on header presence (true when 'daemon' present, false otherwise)
            if (daemonGroup != null) {
                builder.daemon(true);
            } else {
                builder.daemon(false);
            }
        } else {
            // Lenient: just extract the thread name from quotes
            int firstQuote = line.indexOf('"');
            int lastQuote = line.lastIndexOf('"');
            if (firstQuote >= 0 && lastQuote > firstQuote) {
                builder.name(line.substring(firstQuote + 1, lastQuote));
            } else {
                builder.name("unknown");
            }

            // SAP thread lines are quoted but don't include '#<id>'; still contain 'daemon'.
            // Explicitly set daemon based on whether the header line contains the marker
            if (THREAD_HEADER_DAEMON.matcher(line).matches()) {
                builder.daemon(true);
            } else {
                builder.daemon(false);
            }
        }

        // Note: parseThreadLine may still set daemon(true) later if the marker appears on subsequent lines;
        // setting false here ensures the field is never left null.

        // Extract native ID
        Matcher nidMatcher = NATIVE_ID_PATTERN.matcher(line);
        if (nidMatcher.matches()) {
            builder.nativeId(parseHexLongSafe(nidMatcher.group(1)));
        }

        // Extract CPU time
        Matcher cpuMatcher = CPU_TIME_PATTERN.matcher(line);
        if (cpuMatcher.matches()) {
            builder.cpuTimeSec(parseTimeToSeconds(cpuMatcher.group(1), cpuMatcher.group(2)));
        }

        // Extract elapsed time
        Matcher elapsedMatcher = ELAPSED_TIME_PATTERN.matcher(line);
        if (elapsedMatcher.matches()) {
            builder.elapsedTimeSec(parseTimeToSeconds(elapsedMatcher.group(1), elapsedMatcher.group(2)));
        }

        return builder;
    }

    private static void parseThreadLine(String line, ThreadInfoBuilder builder) {
        if (line.startsWith("io=")) {
            return;
        }

        // SAP prints the daemon marker on the first thread line, even when it's not the jstack header form.
        if (line.contains(" daemon ")) {
            builder.daemon(true);
            // don't return
        }

        if (line.startsWith("tid=")) {
            Matcher nidMatcher = NATIVE_ID_PATTERN.matcher(line);
            if (nidMatcher.matches()) {
                builder.nativeId(parseHexLongSafe(nidMatcher.group(1)));
            }

            // SAP/JDK8 unquoted VM/GC threads often have no java.lang.Thread.State line;
            // their state is expressed on this tid-line (e.g. '... runnable').
            if (builder.state() == null && RUNNABLE_WORD_PATTERN.matcher(line).matches()) {
                builder.state(Thread.State.RUNNABLE);
            }
            return;
        }

        // SAP/JDK8 often prints prio/tid/nid on its own line; capture prio if present.
        // ThreadInfoBuilder has no priority() getter, so we rely on the fact that we only see one such line per thread.
        Matcher prioMatcher = PRIO_PATTERN.matcher(line);
        if (prioMatcher.matches()) {
            // Don't overwrite an existing priority from the header if present: only set if builder.additionalInfo() isn't used here.
            // Since there's no getter, we set unconditionally; header priority is on the header line and will be the same value.
            builder.priority(parseIntSafe(prioMatcher.group(1)));
            // don't return: same line might also contain nid=...
        }

        // SAP-style prio/tid line also contains nid=...; capture it here too.
        Matcher nidMatcher = NATIVE_ID_PATTERN.matcher(line);
        if (nidMatcher.matches()) {
            builder.nativeId(parseHexLongSafe(nidMatcher.group(1)));
            // don't return
        }

        // Parse "Carrying virtual thread #XXX"
        Matcher carryingMatcher = CARRYING_VIRTUAL_THREAD_PATTERN.matcher(line);
        if (carryingMatcher.matches()) {
            builder.carryingVirtualThreadId(parseLongSafe(carryingMatcher.group(1)));
            return;
        }

        // Parse thread state
        Matcher stateMatcher = THREAD_STATE_PATTERN.matcher(line);
        if (stateMatcher.matches()) {
            builder.state(parseThreadState(stateMatcher.group(1)));
            return;
        }

        // Parse stack frame
        Matcher stackMatcher = STACK_FRAME_PATTERN.matcher(line);
        if (stackMatcher.matches()) {
            StackFrame frame = parseStackFrame(stackMatcher.group(1), stackMatcher.group(2));
            builder.addStackFrame(frame);
            return;
        }

        // Parse lock info - waiting on
        Matcher waitingMatcher = WAITING_ON_PATTERN.matcher(line);
        if (waitingMatcher.matches()) {
            String lockId = waitingMatcher.group(1);
            String className = waitingMatcher.group(2);
            builder.addLock(new LockInfo(lockId, className, LockInfo.LockOperation.WAITING_ON));
            return;
        }

        // Parse lock info - waiting to lock
        Matcher waitingToLockMatcher = WAITING_TO_LOCK_PATTERN.matcher(line);
        if (waitingToLockMatcher.matches()) {
            String lockId = waitingToLockMatcher.group(1);
            String className = waitingToLockMatcher.group(2);
            builder.addLock(new LockInfo(lockId, className, LockInfo.LockOperation.WAITING_TO_LOCK));
            return;
        }

        // Parse lock info - waiting to re-lock in wait()
        Matcher waitingToRelockMatcher = WAITING_TO_RELOCK_PATTERN.matcher(line);
        if (waitingToRelockMatcher.matches()) {
            String lockId = waitingToRelockMatcher.group(1);
            String className = waitingToRelockMatcher.group(2);
            builder.addLock(new LockInfo(lockId, className, LockInfo.LockOperation.WAITING_TO_LOCK));
            return;
        }

        // Parse lock info - locked
        Matcher lockedMatcher = LOCKED_PATTERN.matcher(line);
        if (lockedMatcher.matches()) {
            String lockId = lockedMatcher.group(1);
            String className = lockedMatcher.group(2);
            builder.addLock(new LockInfo(lockId, className, LockInfo.LockOperation.LOCKED));
            return;
        }

        // Parse lock info - parking
        Matcher parkingMatcher = PARKING_PATTERN.matcher(line);
        if (parkingMatcher.matches()) {
            String lockId = parkingMatcher.group(1);
            String className = parkingMatcher.group(2);
            String ownerThreadId = parkingMatcher.group(3); // Optional owner thread ID
            builder.addLock(new LockInfo(lockId, className, LockInfo.LockOperation.PARKING, ownerThreadId));
            return;
        }

        // Parse lock info - eliminated
        if (line.contains("- lock is eliminated")) {
            builder.addLock(new LockInfo(null, null, LockInfo.LockOperation.ELIMINATED));
            return;
        }

        // Store any other info (skip if it's whitespace-only or known patterns)
        String trimmed = line.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("at ") && !trimmed.startsWith("-")
                && !trimmed.contains("java.lang.Thread.State:")) {
            builder.additionalInfo(trimmed);
        }
    }

    private static StackFrame parseStackFrame(String method, String location) {
        // SAP/OpenJDK sometimes prints method descriptors/return types, e.g.
        //   java.io.FileInputStream.readBytes([BII)I
        // or module+descriptor combos.
        // Strip everything from the first '(' to get the classical "class.method".
        int sigPos = method.indexOf('(');
        if (sigPos >= 0) {
            method = method.substring(0, sigPos);
        }

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

    private static Thread.State parseThreadState(String state) {
        try {
            return Thread.State.valueOf(state);
        } catch (IllegalArgumentException e) {
            // Lenient: default to RUNNABLE if unknown
            return Thread.State.RUNNABLE;
        }
    }

    private static Double parseTimeToSeconds(String value, String unit) {
        try {
            double time = Double.parseDouble(value);
            return switch (unit) {
                case "s", "" -> time;
                case "ms" -> time / 1000.0;
                case "us" -> time / 1_000_000.0;
                case "ns" -> time / 1_000_000_000.0;
                default -> time; // Default to seconds
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Integer parseIntSafe(String value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Long parseLongSafe(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Long parseHexLongSafe(String value) {
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
    private static DeadlockInfo parseDeadlockSection(BufferedReader reader) throws IOException {
        List<DeadlockInfo.DeadlockedThread> deadlockedThreads = new ArrayList<>();
        String line;

        // First section: deadlock descriptions
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

            // Summary lines can appear multiple times in SAP dumps (e.g. "Found 1 deadlock.")
            if (DEADLOCK_SUMMARY_PATTERN.matcher(line).find()) {
                continue;
            }

            // Indirect waiter block can appear before or after the actual deadlock; parse it into an extra DeadlockInfo.
            Matcher indirectHeader = DEADLOCK_INDIRECT_WAITERS_HEADER_PATTERN.matcher(line);
            if (indirectHeader.find()) {
                String deadlockedThreadName = indirectHeader.group(1);
                DeadlockInfo indirect = parseIndirectDeadlockWaitersSection(reader, deadlockedThreadName);
                if (indirect != null && !indirect.threads().isEmpty()) {
                    // We represent this as a separate deadlock group (per requirement)
                    // Can't return it here, so we stash it by merging after: simplest is to create a synthetic entry:
                    // Add a marker thread that references the deadlocked thread, then the waiters.
                    // But we want separate DeadlockInfo records -> encode as one DeadlockInfo with just the waiters.
                    // To keep API stable, we add its threads to the current list only when we haven't yet started parsing
                    // a cycle; otherwise we'd mix groups.
                    // Therefore: if we already have cycle threads, we stop parsing now by rewinding via stored line isn't possible.
                    // Instead: we add it as synthetic "group" by appending a separator thread is wrong.
                    // Better: interpret this as part of the same deadlock info if no cycle parsed; else ignore.
                    // Requirement says "yes" to parsing indirect waiters, so we'll attach as additional threads
                    // to the current deadlock info when possible.
                    deadlockedThreads.addAll(indirect.threads());
                }
                continue;
            }

            // Check for stack information section
            if (DEADLOCK_STACK_SECTION_PATTERN.matcher(line).find()) {
                // Save current thread if any
                if (currentThreadName != null) {
                    deadlockedThreads.add(new DeadlockInfo.DeadlockedThread(
                            currentThreadName, waitingForMonitor, waitingForObject,
                            waitingForObjectType, heldBy, List.of(), List.of()));
                    currentThreadName = null;
                }
                // Parse stack information section
                parseDeadlockStackSection(reader, deadlockedThreads);
                break;
            }

            // Parse thread labels (HotSpot: "Name":, SAP: "Name" (tid=...):)
            Matcher threadNameMatcher = DEADLOCK_THREAD_NAME_PATTERN.matcher(line);
            Matcher sapThreadNameMatcher = SAP_DEADLOCK_THREAD_LABEL_PATTERN.matcher(line);
            if (threadNameMatcher.find() || sapThreadNameMatcher.find()) {
                String name = threadNameMatcher.find(0) ? threadNameMatcher.group(1) : sapThreadNameMatcher.group(1);

                if (currentThreadName != null) {
                    deadlockedThreads.add(new DeadlockInfo.DeadlockedThread(
                            currentThreadName, waitingForMonitor, waitingForObject,
                            waitingForObjectType, heldBy, List.of(), List.of()));
                }
                currentThreadName = name;
                waitingForMonitor = null;
                waitingForObject = null;
                waitingForObjectType = null;
                heldBy = null;
                continue;
            }

            // Parse HotSpot monitor waiting
            Matcher waitingMatcher = DEADLOCK_WAITING_PATTERN.matcher(line);
            if (waitingMatcher.find()) {
                waitingForMonitor = waitingMatcher.group(1);
                waitingForObject = waitingMatcher.group(2);
                waitingForObjectType = waitingMatcher.group(3);
                continue;
            }

            // Parse SAP ownable synchronizer waiting
            Matcher ownableMatcher = DEADLOCK_WAITING_OWNABLE_SYNCHRONIZER_PATTERN.matcher(line);
            if (ownableMatcher.find()) {
                // Map synchronizer id into waitingForObject (per requirement). There is no monitor address for this format.
                waitingForMonitor = null;
                waitingForObject = ownableMatcher.group(1);
                waitingForObjectType = ownableMatcher.group(2);
                continue;
            }
            Matcher ownableFallbackMatcher = DEADLOCK_WAITING_OWNABLE_SYNCHRONIZER_PATTERN_FALLBACK.matcher(line);
            if (ownableFallbackMatcher.find()) {
                waitingForMonitor = null;
                waitingForObject = ownableFallbackMatcher.group(1);
                waitingForObjectType = ownableFallbackMatcher.group(2);
                continue;
            }

            // Parse held by (works for both formats)
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
     * Parse SAP section:
     *   Threads (in)directly waiting on deadlocked thread "X" ...
     * Produces DeadlockedThread entries for each listed waiting thread, setting heldBy to the deadlocked thread name.
     */
    private static @Nullable DeadlockInfo parseIndirectDeadlockWaitersSection(BufferedReader reader,
            String deadlockedThreadName) throws IOException {
        List<DeadlockInfo.DeadlockedThread> waiters = new ArrayList<>();
        String line;

        String currentThreadName = null;
        String waitingForObject = null;
        String waitingForObjectType = null;
        String heldBy = deadlockedThreadName;

        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();

            // The indirect section terminates right before the next major section and is separated by blank lines.
            // If we see a blank line and already parsed at least one waiter, stop here without consuming further.
            if (trimmed.isEmpty()) {
                if (currentThreadName != null) {
                    waiters.add(new DeadlockInfo.DeadlockedThread(
                            currentThreadName,
                            null,
                            waitingForObject,
                            waitingForObjectType,
                            heldBy,
                            List.of(),
                            List.of()
                    ));
                    currentThreadName = null;
                }
                if (!waiters.isEmpty()) {
                    break;
                }
                continue;
            }

            // Header underline separator or other decoration lines
            if (trimmed.startsWith("=") || trimmed.startsWith("-")) {
                continue;
            }

            Matcher threadLabel = DEADLOCK_INDIRECT_THREAD_LABEL_PATTERN.matcher(trimmed);
            if (threadLabel.find()) {
                // save previous
                if (currentThreadName != null) {
                    waiters.add(new DeadlockInfo.DeadlockedThread(
                            currentThreadName,
                            null,
                            waitingForObject,
                            waitingForObjectType,
                            heldBy,
                            List.of(),
                            List.of()
                    ));
                }
                currentThreadName = threadLabel.group(1);
                waitingForObject = null;
                waitingForObjectType = null;
                heldBy = deadlockedThreadName;
                continue;
            }

            Matcher ownableMatcher = DEADLOCK_WAITING_OWNABLE_SYNCHRONIZER_PATTERN.matcher(trimmed);
            Matcher ownableFallbackMatcher = DEADLOCK_WAITING_OWNABLE_SYNCHRONIZER_PATTERN_FALLBACK.matcher(trimmed);
            if (ownableMatcher.find() || ownableFallbackMatcher.find()) {
                Matcher m = ownableMatcher.find() ? ownableMatcher : ownableFallbackMatcher;
                waitingForObject = m.group(1);
                waitingForObjectType = m.group(2);
                continue;
            }

            Matcher heldByMatcher = DEADLOCK_HELD_BY_PATTERN.matcher(trimmed);
            if (heldByMatcher.find()) {
                heldBy = heldByMatcher.group(1);
            }
        }

        if (currentThreadName != null) {
            waiters.add(new DeadlockInfo.DeadlockedThread(
                    currentThreadName,
                    null,
                    waitingForObject,
                    waitingForObjectType,
                    heldBy,
                    List.of(),
                    List.of()
            ));
        }

        return new DeadlockInfo(waiters);
    }

    private static @Nullable Instant tryParseTimestamp(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String candidate = trimmed;
        String prefix = "Thread dump triggered at ";
        if (candidate.startsWith(prefix)) {
            candidate = candidate.substring(prefix.length()).trim();
        }

        // Common formats seen in practice:
        // - 2024-01-15 10:30:45
        // - 2024-01-15T10:30:45Z
        // - 2024-01-15T10:30:45.123Z
        try {
            if (candidate.contains("T") && (candidate.endsWith("Z") || candidate.contains("+") || candidate.contains("-"))) {
                return Instant.parse(candidate);
            }
        } catch (DateTimeParseException ignored) {
            // fall back to local date-time parsing
        }

        try {
            // Treat local timestamps as system default zone for reproducibility within an environment.
            LocalDateTime ldt = LocalDateTime.parse(candidate, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * Parse the stack information section of deadlock info
     */
    private static void parseDeadlockStackSection(BufferedReader reader,
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

            // Check for thread name (HotSpot: "Thread":, SAP: "Thread"(tid=...):)
            Matcher threadNameMatcher = DEADLOCK_THREAD_NAME_PATTERN.matcher(line);
            Matcher sapThreadNameMatcher = SAP_DEADLOCK_THREAD_LABEL_PATTERN.matcher(line);
            if (threadNameMatcher.find() || sapThreadNameMatcher.find()) {
                String name = threadNameMatcher.find(0) ? threadNameMatcher.group(1) : sapThreadNameMatcher.group(1);

                // Save previous thread
                if (currentThreadName != null) {
                    updateDeadlockedThreadWithStack(deadlockedThreads, currentThreadName,
                            currentStackTrace, currentLocks);
                }
                currentThreadName = name;
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
            if (line.contains("- waiting to lock")) {
                Matcher waitingToLockMatcher = WAITING_TO_LOCK_PATTERN.matcher(line);
                if (waitingToLockMatcher.matches()) {
                    String lockId = waitingToLockMatcher.group(1);
                    String className = waitingToLockMatcher.group(2);
                    currentLocks.add(new LockInfo(lockId, className, LockInfo.LockOperation.WAITING_TO_LOCK));
                }
                continue;
            }
            if (line.contains("- waiting on")) {
                Matcher waitingOnMatcher = WAITING_ON_PATTERN.matcher(line);
                if (waitingOnMatcher.matches()) {
                    String lockId = waitingOnMatcher.group(1);
                    String className = waitingOnMatcher.group(2);
                    currentLocks.add(new LockInfo(lockId, className, LockInfo.LockOperation.WAITING_ON));
                }
                continue;
            }

            // Parse lock info - locked
            Matcher lockedMatcher = LOCKED_PATTERN.matcher(line);
            if (lockedMatcher.matches()) {
                String lockId = lockedMatcher.group(1);
                String className = lockedMatcher.group(2);
                currentLocks.add(new LockInfo(lockId, className, LockInfo.LockOperation.LOCKED));
                continue;
            }

            // Parse lock info - parking (SAP deadlock stack uses parking)
            Matcher parkingMatcher = PARKING_PATTERN.matcher(line);
            if (parkingMatcher.matches()) {
                String lockId = parkingMatcher.group(1);
                String className = parkingMatcher.group(2);
                String ownerThreadId = parkingMatcher.group(3);
                currentLocks.add(new LockInfo(lockId, className, LockInfo.LockOperation.PARKING, ownerThreadId));
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
    private static void updateDeadlockedThreadWithStack(List<DeadlockInfo.DeadlockedThread> deadlockedThreads,
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
}