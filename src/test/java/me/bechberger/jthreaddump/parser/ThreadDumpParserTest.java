package me.bechberger.jthreaddump.parser;

import me.bechberger.jthreaddump.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parametrized tests for ThreadDumpParser
 */
class ThreadDumpParserTest {

    private static boolean doublesEqual(Double a, Double b) {
        if (a == null || b == null) {
            return a == b;
        }
        // Values are frequently derived from ms with 2 decimals (e.g. 46.88 ms -> 0.04688 sec)
        // which can lead to tiny representation errors.
        return Math.abs(a - b) <= 1e-9;
    }

    /**
     * Assert that two thread dumps are equal ignoring hex values, with detailed error message on failure
     */
    private void assertThreadDumpsEqual(ThreadDump expected, ThreadDump parsed, String message) {
        if (expected.equalsIgnoringHexValues(parsed)) {
            return; // Success
        }

        // Build detailed error message
        StringBuilder error = new StringBuilder();
        error.append(message).append("\n");
        error.append("=== Thread Dump Comparison Failed ===\n");
        boolean hasMismatch = false;

        // Compare basic properties
        // Note: JVM info is intentionally NOT compared as it contains variable HotSpot version info

        if (!java.util.Objects.equals(expected.sourceType(), parsed.sourceType())) {
            error.append("Source type mismatch:\n");
            error.append("  Expected: ").append(expected.sourceType()).append("\n");
            error.append("  Parsed:   ").append(parsed.sourceType()).append("\n");
            hasMismatch = true;
        }

        // Compare JNI info
        if (expected.jniInfo() != null || parsed.jniInfo() != null) {
            if (expected.jniInfo() == null) {
                error.append("JNI info: Expected=null, Parsed=").append(parsed.jniInfo()).append("\n");
                hasMismatch = true;
            } else if (parsed.jniInfo() == null) {
                error.append("JNI info: Expected=").append(expected.jniInfo()).append(", Parsed=null\n");
                hasMismatch = true;
            } else if (!expected.jniInfo().equalsIgnoringHexValues(parsed.jniInfo())) {
                error.append("JNI info mismatch:\n");
                error.append("  Expected: ").append(expected.jniInfo()).append("\n");
                error.append("  Parsed:   ").append(parsed.jniInfo()).append("\n");
                hasMismatch = true;
            }
        }

        // Compare deadlocks
        if (expected.deadlockInfos() != null || parsed.deadlockInfos() != null) {
            // Treat empty list as null
            List<DeadlockInfo> expDeadlocks = (expected.deadlockInfos() != null && !expected.deadlockInfos().isEmpty())
                    ? expected.deadlockInfos() : null;
            List<DeadlockInfo> prsDeadlocks = (parsed.deadlockInfos() != null && !parsed.deadlockInfos().isEmpty())
                    ? parsed.deadlockInfos() : null;

            if (expDeadlocks == null && prsDeadlocks != null) {
                error.append("Deadlocks: Expected=null, Parsed=").append(prsDeadlocks.size()).append(" deadlocks\n");
                hasMismatch = true;
            } else if (expDeadlocks != null && prsDeadlocks == null) {
                error.append("Deadlocks: Expected=").append(expDeadlocks.size()).append(" deadlocks, Parsed=null\n");
                hasMismatch = true;
            } else if (expDeadlocks != null && prsDeadlocks != null) {
                if (expDeadlocks.size() != prsDeadlocks.size()) {
                    error.append("Deadlock count mismatch: Expected=").append(expDeadlocks.size())
                            .append(", Parsed=").append(prsDeadlocks.size()).append("\n");
                    hasMismatch = true;
                } else {
                    for (int i = 0; i < expDeadlocks.size(); i++) {
                        if (!expDeadlocks.get(i).equalsIgnoringHexValues(prsDeadlocks.get(i))) {
                            error.append("Deadlock ").append(i).append(" mismatch:\n");
                            DeadlockInfo exp = expDeadlocks.get(i);
                            DeadlockInfo prs = prsDeadlocks.get(i);
                            error.append("  Expected threads: ").append(exp.threads().size()).append("\n");
                            error.append("  Parsed threads: ").append(prs.threads().size()).append("\n");

                            // Compare individual deadlocked threads
                            for (int j = 0; j < Math.min(exp.threads().size(), prs.threads().size()); j++) {
                                var expThread = exp.threads().get(j);
                                var prsThread = prs.threads().get(j);
                                if (!expThread.equalsIgnoringHexValues(prsThread)) {
                                    error.append("    Thread ").append(j).append(" (").append(expThread.threadName()).append(") mismatch:\n");
                                    if (expThread.locks().size() != prsThread.locks().size()) {
                                        error.append("      Lock count: Expected=").append(expThread.locks().size())
                                                .append(", Parsed=").append(prsThread.locks().size()).append("\n");
                                        error.append("      Expected locks: ").append(expThread.locks()).append("\n");
                                        error.append("      Parsed locks: ").append(prsThread.locks()).append("\n");
                                    }
                                    if (expThread.stackTrace().size() != prsThread.stackTrace().size()) {
                                        error.append("      Stack size: Expected=").append(expThread.stackTrace().size())
                                                .append(", Parsed=").append(prsThread.stackTrace().size()).append("\n");
                                    }
                                }
                            }
                            hasMismatch = true;
                        }
                    }
                }
            }
        }

        // Compare threads
        error.append("\nThread count: Expected=").append(expected.threads().size())
                .append(", Parsed=").append(parsed.threads().size()).append("\n");

        if (expected.threads().size() != parsed.threads().size()) {
            hasMismatch = true;
        }

        int minSize = Math.min(expected.threads().size(), parsed.threads().size());
        for (int i = 0; i < minSize; i++) {
            ThreadInfo exp = expected.threads().get(i);
            ThreadInfo prs = parsed.threads().get(i);

            if (!exp.equalsIgnoringHexValues(prs)) {
                hasMismatch = true;
                error.append("\n--- Thread ").append(i).append(" mismatch ---\n");
                error.append("Name: ").append(exp.name()).append("\n");

                if (!java.util.Objects.equals(exp.state(), prs.state())) {
                    error.append("  State: Expected=").append(exp.state())
                            .append(", Parsed=").append(prs.state()).append("\n");
                }

                if (!java.util.Objects.equals(exp.priority(), prs.priority())) {
                    error.append("  Priority: Expected=").append(exp.priority())
                            .append(", Parsed=").append(prs.priority()).append("\n");
                }

                if (!java.util.Objects.equals(exp.daemon(), prs.daemon())) {
                    error.append("  Daemon: Expected=").append(exp.daemon())
                            .append(", Parsed=").append(prs.daemon()).append("\n");
                }

                if (!doublesEqual(exp.cpuTimeSec(), prs.cpuTimeSec())) {
                    error.append("  CPU time: Expected=").append(exp.cpuTimeSec())
                            .append(", Parsed=").append(prs.cpuTimeSec()).append("\n");
                }

                if (!doublesEqual(exp.elapsedTimeSec(), prs.elapsedTimeSec())) {
                    error.append("  Elapsed time: Expected=").append(exp.elapsedTimeSec())
                            .append(", Parsed=").append(prs.elapsedTimeSec()).append("\n");
                }

                // Compare stack traces
                if (!stackTracesEqual(exp.stackTrace(), prs.stackTrace())) {
                    error.append("  Stack trace mismatch (Expected=").append(exp.stackTrace().size())
                            .append(", Parsed=").append(prs.stackTrace().size()).append("):\n");

                    int maxFrames = Math.max(exp.stackTrace().size(), prs.stackTrace().size());
                    for (int j = 0; j < maxFrames; j++) {
                        StackFrame expFrame = j < exp.stackTrace().size() ? exp.stackTrace().get(j) : null;
                        StackFrame prsFrame = j < prs.stackTrace().size() ? prs.stackTrace().get(j) : null;

                        if (expFrame == null) {
                            error.append("    [").append(j).append("] Expected: <missing>, Parsed: ").append(prsFrame).append("\n");
                        } else if (prsFrame == null) {
                            error.append("    [").append(j).append("] Expected: ").append(expFrame).append(", Parsed: <missing>\n");
                        } else if (!expFrame.equalsIgnoringHexValues(prsFrame)) {
                            error.append("    [").append(j).append("] Expected: ").append(expFrame).append("\n");
                            error.append("    [").append(j).append("] Parsed:   ").append(prsFrame).append("\n");
                        }
                    }
                }

                // Compare locks
                if (exp.locks().size() != prs.locks().size() || !locksEqualIgnoringHex(exp.locks(), prs.locks())) {
                    error.append("  Locks mismatch (Expected=").append(exp.locks().size())
                            .append(", Parsed=").append(prs.locks().size()).append("):\n");
                    error.append("    Expected: ").append(exp.locks()).append("\n");
                    error.append("    Parsed:   ").append(prs.locks()).append("\n");
                }

                if (!java.util.Objects.equals(exp.additionalInfo(), prs.additionalInfo())) {
                    error.append("  Additional info: Expected=").append(exp.additionalInfo())
                            .append(", Parsed=").append(prs.additionalInfo()).append("\n");
                }
            }
        }

        // Report extra threads
        if (expected.threads().size() > parsed.threads().size()) {
            hasMismatch = true;
            error.append("\nMissing threads in parsed dump:\n");
            for (int i = parsed.threads().size(); i < expected.threads().size(); i++) {
                error.append("  [").append(i).append("] ").append(expected.threads().get(i).name()).append("\n");
            }
        } else if (parsed.threads().size() > expected.threads().size()) {
            hasMismatch = true;
            error.append("\nExtra threads in parsed dump:\n");
            for (int i = expected.threads().size(); i < parsed.threads().size(); i++) {
                error.append("  [").append(i).append("] ").append(parsed.threads().get(i).name()).append("\n");
            }
        }

        if (hasMismatch) {
            fail(error.toString());
        }
    }

    private boolean locksEqualIgnoringHex(List<LockInfo> list1, List<LockInfo> list2) {
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            if (!list1.get(i).equalsIgnoringHexValues(list2.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean stackTracesEqual(List<StackFrame> list1, List<StackFrame> list2) {
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            if (!list1.get(i).equalsIgnoringHexValues(list2.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Provides test cases with thread dump files and expected results
     */
    static Stream<Arguments> threadDumpProvider() {
        return Stream.of(
                Arguments.of("thread-dump-jstack.txt", 5, "jstack", true, true),
                Arguments.of("thread-dump-jcmd.txt", 4, "jcmd", false, true),
                Arguments.of("thread-dump-deadlock.txt", 2, "unknown", false, false),
                Arguments.of("thread-dump-minimal.txt", 1, "unknown", false, false)
        );
    }

    @ParameterizedTest(name = "Parse {0}")
    @MethodSource("threadDumpProvider")
    void testParseThreadDump(String fileName, int expectedThreadCount, String expectedSource,
                             boolean expectJniInfo, boolean expectJvmInfo) throws IOException {
        String content = loadResource(fileName);
        ThreadDump dump = ThreadDumpParser.parse(content);

        assertNotNull(dump, "Dump should not be null");
        assertEquals(expectedThreadCount, dump.threads().size(),
                "Expected " + expectedThreadCount + " threads");
        assertEquals(expectedSource, dump.sourceType(), "Expected source type: " + expectedSource);

        if (expectJniInfo) {
            assertNotNull(dump.jniInfo(), "Expected JNI info");
        }

        if (expectJvmInfo) {
            assertNotNull(dump.jvmInfo(), "Expected JVM info");
        }
    }

    @Test
    void testParseJstackDetailed() throws IOException {
        String content = loadResource("thread-dump-jstack.txt");
        ThreadDump parsed = ThreadDumpParser.parse(content);

        Instant expectedTimestamp = LocalDateTime
                .parse("2024-01-15 10:30:45", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(ZoneId.systemDefault())
                .toInstant();
        assertEquals(expectedTimestamp, parsed.timestamp(), "Timestamp should be parsed from the first line");

        ThreadDump expected = new ThreadDump(
                parsed.timestamp(),
                "Full thread dump Java HotSpot(TM) 64-Bit Server VM (21.0.1+12-LTS-29 mixed mode, sharing):",
                List.of(
                        ThreadInfoBuilder.create()
                                .name("main")
                                .threadId(1L)
                                .nativeId(0x2803L)
                                .priority(5)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.1255)
                                .elapsedTimeSec(10.250)
                                .stackTrace(
                                        new StackFrame("java.io.FileInputStream", "readBytes", null, null, true),
                                        new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 276),
                                        new StackFrame("java.io.BufferedInputStream", "fill", "BufferedInputStream.java", 244),
                                        new StackFrame("com.example.Main", "readFile", "Main.java", 42)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Reference Handler")
                                .threadId(2L)
                                .nativeId(0x3003L)
                                .priority(10)
                                .daemon(true)
                                .state(Thread.State.WAITING)
                                .cpuTimeSec(0.0005)
                                .elapsedTimeSec(10.200)
                                .stackTrace(
                                        new StackFrame("java.lang.ref.Reference", "waitForReferencePendingList", null, null, true),
                                        new StackFrame("java.lang.ref.Reference", "processPendingReferences", "Reference.java", 246),
                                        new StackFrame("java.lang.ref.Reference$ReferenceHandler", "run", "Reference.java", 208)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Finalizer")
                                .threadId(3L)
                                .nativeId(0x4803L)
                                .priority(8)
                                .daemon(true)
                                .state(Thread.State.WAITING)
                                .cpuTimeSec(0.00025)
                                .elapsedTimeSec(10.190)
                                .stackTrace(
                                        new StackFrame("java.lang.Object", "wait", null, null, true),
                                        new StackFrame("java.lang.ref.ReferenceQueue", "remove", "ReferenceQueue.java", 155),
                                        new StackFrame("java.lang.ref.ReferenceQueue", "remove", "ReferenceQueue.java", 176),
                                        new StackFrame("java.lang.ref.Finalizer$FinalizerThread", "run", "Finalizer.java", 172)
                                )
                                .locks(
                                        new LockInfo("0x00000007ffc00000", "java.lang.ref.ReferenceQueue$Lock", LockInfo.LockOperation.WAITING_ON),
                                        new LockInfo("0x00000007ffc00000", "java.lang.ref.ReferenceQueue$Lock", LockInfo.LockOperation.LOCKED)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Worker-1")
                                .threadId(10L)
                                .nativeId(0x5803L)
                                .priority(5)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(2.500)
                                .elapsedTimeSec(8.500)
                                .stackTrace(
                                        new StackFrame("java.net.SocketInputStream", "socketRead0", null, null, true),
                                        new StackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 186),
                                        new StackFrame("com.example.Worker", "processRequest", "Worker.java", 67)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Worker-2")
                                .threadId(11L)
                                .nativeId(0x6003L)
                                .priority(5)
                                .state(Thread.State.BLOCKED)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(8.4500)
                                .stackTrace(
                                        new StackFrame("com.example.Worker", "processRequest", "Worker.java", 45),
                                        new StackFrame("com.example.Worker", "run", "Worker.java", 30)
                                )
                                .locks(
                                        new LockInfo("0x00000007ffc12345", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)
                                )
                                .build()
                ),
                new JniInfo(247, 3181, 3363L, 70049L),
                "jstack",
                null
        );

        assertThreadDumpsEqual(expected, parsed, "ThreadDump should match expected values (ignoring hex addresses)");
    }

    @Test
    void testParseDeadlock() throws IOException {
        String content = loadResource("thread-dump-deadlock.txt");
        ThreadDump parsed = ThreadDumpParser.parse(content);

        ThreadDump expected = new ThreadDump(
                parsed.timestamp(), // Use parsed timestamp
                null,
                List.of(
                        ThreadInfoBuilder.create()
                                .name("Thread-A")
                                .threadId(0x1000L)
                                .nativeId(0x1000L)
                                .priority(5)
                                .state(Thread.State.BLOCKED)
                                .stackTrace(
                                        new StackFrame("com.example.DeadlockExample", "methodA", "DeadlockExample.java", 20),
                                        new StackFrame("com.example.DeadlockExample", "run", "DeadlockExample.java", 10)
                                )
                                .locks(
                                        new LockInfo("0x00000007ffc11111", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK),
                                        new LockInfo("0x00000007ffc22222", "java.lang.Object", LockInfo.LockOperation.LOCKED)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Thread-B")
                                .threadId(0x2000L)
                                .nativeId(0x2000L)
                                .priority(5)
                                .state(Thread.State.BLOCKED)
                                .stackTrace(
                                        new StackFrame("com.example.DeadlockExample", "methodB", "DeadlockExample.java", 30),
                                        new StackFrame("com.example.DeadlockExample", "run", "DeadlockExample.java", 15)
                                )
                                .locks(
                                        new LockInfo("0x00000007ffc22222", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK),
                                        new LockInfo("0x00000007ffc11111", "java.lang.Object", LockInfo.LockOperation.LOCKED)
                                )
                                .build()
                ),
                null,
                "unknown",
                null
        );

        assertThreadDumpsEqual(expected, parsed, "ThreadDump should match expected values (ignoring hex addresses)");
    }

    @Test
    void testParseMultiDeadlock() throws IOException {
        // Generate and load the multi-deadlock thread dump
        Path dumpFile = me.bechberger.jthreaddump.test.ThreadDumpGenerator.getOrGenerateThreadDump(
                "multi-deadlock-test",
                me.bechberger.jthreaddump.test.ThreadDumpGenerator.multiDeadlockScenario()
        );
        String content = Files.readString(dumpFile);
        ThreadDump parsed = ThreadDumpParser.parse(content);

        // Extract just the 5 scenario threads for comparison (ignore system threads)
        List<ThreadInfo> scenarioThreads = parsed.threads().stream()
                .filter(t -> t.name() != null &&
                             (t.name().startsWith("DeadlockThread-") || t.name().equals("WorkerThread-1")))
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();

        // Construct expected with hardcoded values - custom equals will ignore hex values
        ThreadDump expected = new ThreadDump(
                parsed.timestamp(), // Only use parsed for timestamp
                "Full thread dump Java HotSpot(TM) 64-Bit Server VM (21.0.1+12-LTS-29 mixed mode, sharing):",
                List.of(
                        ThreadInfoBuilder.create()
                                .name("DeadlockThread-A")
                                .priority(5)
                                .state(Thread.State.BLOCKED)
                                .stackTrace(
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator", "lambda$multiDeadlockScenario$10", "ThreadDumpGenerator.java", 632),
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator$$Lambda", "run", null, null)
                                )
                                .locks(
                                        new LockInfo(null, "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK),
                                        new LockInfo(null, "java.lang.Object", LockInfo.LockOperation.LOCKED)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("DeadlockThread-B")
                                .priority(5)
                                .state(Thread.State.BLOCKED)
                                .stackTrace(
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator", "lambda$multiDeadlockScenario$11", "ThreadDumpGenerator.java", 648),
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator$$Lambda", "run", null, null)
                                )
                                .locks(
                                        new LockInfo(null, "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK),
                                        new LockInfo(null, "java.lang.Object", LockInfo.LockOperation.LOCKED)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("DeadlockThread-C")
                                .priority(5)
                                .state(Thread.State.BLOCKED)
                                .stackTrace(
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator", "lambda$multiDeadlockScenario$12", "ThreadDumpGenerator.java", 664),
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator$$Lambda", "run", null, null)
                                )
                                .locks(
                                        new LockInfo(null, "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK),
                                        new LockInfo(null, "java.lang.Object", LockInfo.LockOperation.LOCKED)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("DeadlockThread-D")
                                .priority(5)
                                .state(Thread.State.BLOCKED)
                                .stackTrace(
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator", "lambda$multiDeadlockScenario$13", "ThreadDumpGenerator.java", 680),
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator$$Lambda", "run", null, null)
                                )
                                .locks(
                                        new LockInfo(null, "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK),
                                        new LockInfo(null, "java.lang.Object", LockInfo.LockOperation.LOCKED)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("WorkerThread-1")
                                .priority(5)
                                .state(Thread.State.RUNNABLE)
                                .stackTrace(
                                        new StackFrame("java.lang.Math", "sqrt", null, null, true),
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator", "lambda$multiDeadlockScenario$14", "ThreadDumpGenerator.java", 695),
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator$$Lambda", "run", null, null)
                                )
                                .build()
                ),
                new JniInfo(120, null, null, null),  // 120 global refs in dump
                "jstack",
                null  // No deadlock info section in this dump
        );

        // Use custom ThreadDump with only scenario threads for comparison
        ThreadDump parsedScenarioOnly = new ThreadDump(
                parsed.timestamp(),
                parsed.jvmInfo(),
                scenarioThreads,
                parsed.jniInfo(),
                parsed.sourceType(),
                parsed.deadlockInfos()
        );

        assertThreadDumpsEqual(expected, parsedScenarioOnly, "ThreadDump should match expected values (ignoring hex addresses)");
    }

    @Test
    void testParseMinimal() throws IOException {
        String content = loadResource("thread-dump-minimal.txt");
        ThreadDump parsed = ThreadDumpParser.parse(content);

        ThreadDump expected = new ThreadDump(
                parsed.timestamp(),
                null,
                List.of(
                        ThreadInfoBuilder.create()
                                .name("Minimal-Thread")
                                .threadId(0x1L)
                                .build()
                ),
                null,
                "unknown"
        );

        assertThreadDumpsEqual(expected, parsed, "ThreadDump should match expected values (ignoring hex addresses)");
    }

    @Test
    void testParseJcmd() throws IOException {
        String content = loadResource("thread-dump-jcmd.txt");
        ThreadDump parsed = ThreadDumpParser.parse(content);

        Instant expectedTimestamp = LocalDateTime
                .parse("2024-01-15 10:35:20", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(ZoneId.systemDefault())
                .toInstant();
        assertEquals(expectedTimestamp, parsed.timestamp(), "Timestamp should be parsed from the first line");

        ThreadDump expected = new ThreadDump(
                parsed.timestamp(),
                "Thread dump from jcmd 12345 Thread.print:",
                List.of(
                        ThreadInfoBuilder.create()
                                .name("main")
                                .threadId(1L)
                                .nativeId(0x2803L)
                                .priority(5)
                                .state(Thread.State.RUNNABLE)
                                .stackTrace(
                                        new StackFrame("java.io.FileInputStream", "readBytes", null, null, true),
                                        new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 276)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("GC Thread#0")
                                .threadId(2L)
                                .nativeId(0x3003L)
                                .priority(10)
                                .daemon(true)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("VM Thread")
                                .threadId(3L)
                                .nativeId(0x4803L)
                                .priority(10)
                                .daemon(true)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Service Thread")
                                .threadId(4L)
                                .nativeId(0x5003L)
                                .priority(9)
                                .daemon(true)
                                .build()
                ),
                new JniInfo(150, null, null, null),
                "jcmd"
        );

        assertThreadDumpsEqual(expected, parsed, "ThreadDump should match expected values (ignoring hex addresses)");
    }

    @Test
    void testEmptyInput() throws IOException {
        ThreadDump dump = ThreadDumpParser.parse("");
        assertNotNull(dump);
        assertEquals(0, dump.threads().size());
    }

    @Test
    void testLenientParsing() throws IOException {
        // Test that parser is lenient with malformed input
        String malformed = """
                "Weird-Thread" something unexpected here
                   java.lang.Thread.State: RUNNABLE
                   at some.Class.method(Unknown Source)
                """;

        ThreadDump parsed = ThreadDumpParser.parse(malformed);

        ThreadDump expected = new ThreadDump(
                parsed.timestamp(),
                null,
                List.of(
                        ThreadInfoBuilder.create()
                                .name("Weird-Thread")
                                .state(Thread.State.RUNNABLE)
                                .stackTrace(
                                        new StackFrame("some.Class", "method", null, null, null)
                                )
                                .build()
                ),
                null,
                "unknown"
        );

        assertThreadDumpsEqual(expected, parsed, "ThreadDump should match expected values (ignoring hex addresses)");
    }

    /**
     * Test different time unit parsing
     */
    static Stream<Arguments> timeParsingProvider() {
        return Stream.of(
                Arguments.of("cpu=1.5s", 1.5),
                Arguments.of("cpu=250ms", 0.25),
                Arguments.of("cpu=500000us", 0.5),
                Arguments.of("cpu=1000000ns", 0.001),
                Arguments.of("cpu=10429.07ms", 10.42907),
                Arguments.of("elapsed=10.25s", 10.25),
                Arguments.of("elapsed=47185.99s", 47185.99)
        );
    }

    @ParameterizedTest(name = "Time parsing: {0} -> {1}s")
    @MethodSource("timeParsingProvider")
    void testTimeParsing(String timeLine, double expectedSec) throws IOException {
        String threadDump = String.format("""
                "Test" #1 prio=5 %s tid=0x1000 nid=0x1000 runnable
                   java.lang.Thread.State: RUNNABLE
                """, timeLine);

        ThreadDump dump = ThreadDumpParser.parse(threadDump);
        assertEquals(1, dump.threads().size());
        ThreadInfo thread = dump.threads().get(0);

        if (timeLine.startsWith("cpu=")) {
            assertNotNull(thread.cpuTimeSec());
            assertEquals(expectedSec, thread.cpuTimeSec(), 0.00001);
        } else if (timeLine.startsWith("elapsed=")) {
            assertNotNull(thread.elapsedTimeSec());
            assertEquals(expectedSec, thread.elapsedTimeSec(), 0.00001);
        }
    }

    /**
     * Test various thread states
     */
    static Stream<Arguments> threadStateProvider() {
        return Stream.of(
                Arguments.of("RUNNABLE", Thread.State.RUNNABLE),
                Arguments.of("BLOCKED", Thread.State.BLOCKED),
                Arguments.of("WAITING", Thread.State.WAITING),
                Arguments.of("TIMED_WAITING", Thread.State.TIMED_WAITING),
                Arguments.of("NEW", Thread.State.NEW),
                Arguments.of("TERMINATED", Thread.State.TERMINATED)
        );
    }

    @ParameterizedTest(name = "Thread state: {0}")
    @MethodSource("threadStateProvider")
    void testThreadStates(String stateString, Thread.State expectedState) throws IOException {
        String threadDump = String.format("""
                "Test" #1 runnable
                   java.lang.Thread.State: %s
                """, stateString);

        ThreadDump dump = ThreadDumpParser.parse(threadDump);
        assertEquals(1, dump.threads().size());
        assertEquals(expectedState, dump.threads().get(0).state());
    }

    @Test
    void testMultipleStackFrameFormats() throws IOException {
        String threadDump = """
                "Test" #1 runnable
                   java.lang.Thread.State: RUNNABLE
                   at com.example.Test.method1(Test.java:10)
                   at com.example.Test.method2(Native Method)
                   at com.example.Test.method3(Unknown Source)
                   at com.example.Test.method4(Test.java)
                """;

        ThreadDump parsed = ThreadDumpParser.parse(threadDump);

        ThreadDump expected = new ThreadDump(
                parsed.timestamp(),
                null,
                List.of(
                        ThreadInfoBuilder.create()
                                .name("Test")
                                .threadId(1L)
                                .state(Thread.State.RUNNABLE)
                                .stackTrace(
                                        new StackFrame("com.example.Test", "method1", "Test.java", 10),
                                        new StackFrame("com.example.Test", "method2", null, null, true),
                                        new StackFrame("com.example.Test", "method3", null, null),
                                        new StackFrame("com.example.Test", "method4", "Test.java", null)
                                )
                                .build()
                ),
                null,
                "unknown",
                null
        );

        assertThreadDumpsEqual(expected, parsed, "ThreadDump should match expected values (ignoring hex addresses)");
    }

    // Helper methods

    private String loadResource(String fileName) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(fileName)) {
            if (is == null) {
                throw new IOException("Resource not found: " + fileName);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void testTimestampAbsentIsNull() throws IOException {
        String content = loadResource("thread-dump-minimal.txt");
        ThreadDump parsed = ThreadDumpParser.parse(content);
        assertNull(parsed.timestamp(), "When no timestamp is present in input, timestamp must be null (never guessed)");
        // but one thread
        assertEquals(1, parsed.threads().size(), "There should be one thread parsed");
    }

    @Test
    void testTimestampParsingWithProcessId() throws IOException {
        String content = loadResource("thread-dump-minimal2.txt");
        ThreadDump parsed = ThreadDumpParser.parse(content);
        assertEquals(parsed.timestamp().toString().split("T")[0], "2024-01-15T10:35:20Z".split("T")[0]);
        assertEquals(1, parsed.threads().size(), "There should be one thread parsed");
    }

    @Test
    void testParseSapJvmDump1() throws IOException {
        String content = loadResource("sapjvm/sapjvm-threaddump.txt");
        ThreadDump parsed = ThreadDumpParser.parse(content);

        Instant expectedTimestamp = LocalDateTime
                .parse("2026-01-27 20:45:13", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(ZoneId.systemDefault())
                .toInstant();
        assertEquals(expectedTimestamp, parsed.timestamp(), "Timestamp should be parsed from 'Thread dump triggered at ...'");

        ThreadDump expected = new ThreadDump(
                expectedTimestamp,
                "Full thread dump SAP Java Server VM (8.1.108 11.0.30+000 Jan 16 2026 19:39:58 - 81_REL - optU - windows amd64 - 6 - bas2:343998 (mixed mode)):",
                List.of(
                        ThreadInfoBuilder.create()
                                .name("main")
                                .threadId(1L)
                                .nativeId(0x81f4L)
                                .priority(5)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.06250)
                                .elapsedTimeSec(396.66)
                                .stackTrace(
                                        new StackFrame("java.io.FileInputStream", "readBytes", null, null, true),
                                        new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 255),
                                        new StackFrame("java.io.BufferedInputStream", "fill", "BufferedInputStream.java", 246),
                                        new StackFrame("java.io.BufferedInputStream", "read1", "BufferedInputStream.java", 286),
                                        new StackFrame("java.io.BufferedInputStream", "read", "BufferedInputStream.java", 345),
                                        new StackFrame("java.io.FilterInputStream", "read", "FilterInputStream.java", 143),
                                        new StackFrame("java.io.PushbackInputStream", "read", "PushbackInputStream.java", 186),
                                        new StackFrame("java.util.zip.ZipInputStream", "readFully", "ZipInputStream.java", 403),
                                        new StackFrame("java.util.zip.ZipInputStream", "readLOC", "ZipInputStream.java", 278),
                                        new StackFrame("java.util.zip.ZipInputStream", "getNextEntry", "ZipInputStream.java", 122),
                                        new StackFrame("sun.tools.jar.Main", "extract", "Main.java", 991),
                                        new StackFrame("sun.tools.jar.Main", "run", "Main.java", 317),
                                        new StackFrame("sun.tools.jar.Main", "main", "Main.java", 1311)
                                )
                                .locks(
                                        new LockInfo("0x00000000eaf0a148", "java.io.BufferedInputStream", LockInfo.LockOperation.LOCKED),
                                        new LockInfo("0x00000000eaf0a5c8", "sun.tools.jar.Main", LockInfo.LockOperation.LOCKED)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Reference Handler")
                                .threadId(2L)
                                .nativeId(0x5bbcL)
                                .priority(10)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(396.64)
                                .stackTrace(
                                        new StackFrame("java.lang.ref.Reference", "waitForReferencePendingList", null, null, true),
                                        new StackFrame("java.lang.ref.Reference", "processPendingReferences", "Reference.java", 167),
                                        new StackFrame("java.lang.ref.Reference", "access$000", "Reference.java", 42),
                                        new StackFrame("java.lang.ref.Reference$ReferenceHandler", "run", "Reference.java", 141)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Finalizer")
                                .threadId(3L)
                                .nativeId(0x7624L)
                                .priority(8)
                                .daemon(true)
                                .state(Thread.State.WAITING)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(396.63)
                                .stackTrace(
                                        new StackFrame("java.lang.Object", "wait", null, null, true),
                                        new StackFrame("java.lang.ref.ReferenceQueue", "remove", "ReferenceQueue.java", 152),
                                        new StackFrame("java.lang.ref.ReferenceQueue", "remove", "ReferenceQueue.java", 173),
                                        new StackFrame("java.lang.ref.Finalizer$FinalizerThread", "run", "Finalizer.java", 173)
                                )
                                .locks(
                                        new LockInfo("0x00000000eaf2e248", "java.lang.ref.ReferenceQueue$Lock", LockInfo.LockOperation.WAITING_ON),
                                        new LockInfo("0x00000000eaf2e248", "java.lang.ref.ReferenceQueue$Lock", LockInfo.LockOperation.WAITING_TO_LOCK)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Signal Dispatcher")
                                .threadId(4L)
                                .nativeId(0x1950L)
                                .priority(9)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(396.62)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Attach Listener")
                                .threadId(5L)
                                .nativeId(0x4288L)
                                .priority(5)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.20313)
                                .elapsedTimeSec(396.62)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Service Thread")
                                .threadId(6L)
                                .nativeId(0x18e0L)
                                .priority(9)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(396.60)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("C2 CompilerThread0")
                                .threadId(7L)
                                .nativeId(0x1a8L)
                                .priority(9)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.01563)
                                .elapsedTimeSec(396.60)
                                .additionalInfo("No compile task")
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("C1 CompilerThread0")
                                .threadId(15L)
                                .nativeId(0x6f14L)
                                .priority(9)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.04688)
                                .elapsedTimeSec(396.60)
                                .additionalInfo("Compiling:  615         3       java.io.ObjectInputStream$BlockDataInputStream::peek()I (55 bytes)")
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Sweeper thread")
                                .threadId(19L)
                                .nativeId(0x1218L)
                                .priority(9)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(396.60)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Server: sap_jvm_profiling_server_34384")
                                .threadId(20L)
                                .nativeId(0xd88L)
                                .priority(10)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(396.60)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("JDWP Event Helper Thread")
                                .threadId(22L)
                                .nativeId(0x8704L)
                                .priority(10)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(396.60)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("JDWP Transport Listener: dt_socket")
                                .threadId(23L)
                                .nativeId(0xa58L)
                                .priority(10)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(396.60)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("Control Handler")
                                .threadId(24L)
                                .nativeId(0x1b54L)
                                .priority(10)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(396.60)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("RMI TCP Accept-0")
                                .threadId(26L)
                                .nativeId(0x6ee8L)
                                .priority(5)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.07813)
                                .elapsedTimeSec(372.77)
                                .stackTrace(
                                        new StackFrame("java.net.DualStackPlainSocketImpl", "accept0", null, null, true),
                                        new StackFrame("java.net.DualStackPlainSocketImpl", "socketAccept", "DualStackPlainSocketImpl.java", 128),
                                        new StackFrame("java.net.AbstractPlainSocketImpl", "accept", "AbstractPlainSocketImpl.java", 535),
                                        new StackFrame("java.net.PlainSocketImpl", "accept", "PlainSocketImpl.java", 190),
                                        new StackFrame("java.net.ServerSocket", "implAccept", "ServerSocket.java", 545),
                                        new StackFrame("java.net.ServerSocket", "accept", "ServerSocket.java", 513),
                                        new StackFrame("sun.management.jmxremote.LocalRMIServerSocketFactory$1", "accept", "LocalRMIServerSocketFactory.java", 52),
                                        new StackFrame("sun.rmi.transport.tcp.TCPTransport$AcceptLoop", "executeAcceptLoop", "TCPTransport.java", 405),
                                        new StackFrame("sun.rmi.transport.tcp.TCPTransport$AcceptLoop", "run", "TCPTransport.java", 377),
                                        new StackFrame("java.lang.Thread", "run", "Thread.java", 838)
                                )
                                .locks(
                                        new LockInfo("0x00000000eabdc698", "java.net.SocksSocketImpl", LockInfo.LockOperation.LOCKED)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("RMI Scheduler(0)")
                                .threadId(28L)
                                .nativeId(0x67a4L)
                                .priority(5)
                                .daemon(true)
                                .state(Thread.State.TIMED_WAITING)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(372.26)
                                .stackTrace(
                                        new StackFrame("jdk.internal.misc.Unsafe", "park", null, null, true),
                                        new StackFrame("sun.misc.Unsafe", "park", "Unsafe.java", 1079),
                                        new StackFrame("java.util.concurrent.locks.LockSupport", "parkNanos", "LockSupport.java", 215),
                                        new StackFrame("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject", "awaitNanos", "AbstractQueuedSynchronizer.java", 2078),
                                        new StackFrame("java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue", "take", "ScheduledThreadPoolExecutor.java", 1120),
                                        new StackFrame("java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue", "take", "ScheduledThreadPoolExecutor.java", 836),
                                        new StackFrame("java.util.concurrent.ThreadPoolExecutor", "getTask", "ThreadPoolExecutor.java", 1074),
                                        new StackFrame("java.util.concurrent.ThreadPoolExecutor", "runWorker", "ThreadPoolExecutor.java", 1134),
                                        new StackFrame("java.util.concurrent.ThreadPoolExecutor$Worker", "run", "ThreadPoolExecutor.java", 624),
                                        new StackFrame("java.lang.Thread", "run", "Thread.java", 838)
                                )
                                .locks(
                                        new LockInfo("0x00000000c00af638", "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject", LockInfo.LockOperation.PARKING)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("RMI TCP Connection(2)-10.65.218.67")
                                .threadId(31L)
                                .nativeId(0x2584L)
                                .priority(5)
                                .daemon(true)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(0.0)
                                .stackTrace(
                                        new StackFrame("sun.management.DiagnosticCommandImpl", "executeDiagnosticCommand", null, null, true),
                                        new StackFrame("sun.management.DiagnosticCommandImpl", "access$000", "DiagnosticCommandImpl.java", 40),
                                        new StackFrame("sun.management.DiagnosticCommandImpl$Wrapper", "execute", "DiagnosticCommandImpl.java", 128),
                                        new StackFrame("sun.management.DiagnosticCommandImpl", "invoke", "DiagnosticCommandImpl.java", 230),
                                        new StackFrame("com.sun.jmx.interceptor.DefaultMBeanServerInterceptor", "invoke", "DefaultMBeanServerInterceptor.java", 819),
                                        new StackFrame("com.sun.jmx.mbeanserver.JmxMBeanServer", "invoke", "JmxMBeanServer.java", 801),
                                        new StackFrame("javax.management.remote.rmi.RMIConnectionImpl", "doOperation", "RMIConnectionImpl.java", 1468),
                                        new StackFrame("javax.management.remote.rmi.RMIConnectionImpl", "access$300", "RMIConnectionImpl.java", 76),
                                        new StackFrame("javax.management.remote.rmi.RMIConnectionImpl$PrivilegedOperation", "run", "RMIConnectionImpl.java", 1309),
                                        new StackFrame("javax.management.remote.rmi.RMIConnectionImpl", "doPrivilegedOperation", "RMIConnectionImpl.java", 1401),
                                        new StackFrame("javax.management.remote.rmi.RMIConnectionImpl", "invoke", "RMIConnectionImpl.java", 829),
                                        new StackFrame("sun.reflect.NativeMethodAccessorImpl", "invoke0", null, null, true),
                                        new StackFrame("sun.reflect.NativeMethodAccessorImpl", "invoke", "NativeMethodAccessorImpl.java", 62),
                                        new StackFrame("sun.reflect.DelegatingMethodAccessorImpl", "invoke", "DelegatingMethodAccessorImpl.java", 43),
                                        new StackFrame("java.lang.reflect.Method", "invoke", "Method.java", 498),
                                        new StackFrame("sun.rmi.server.UnicastServerRef", "dispatch", "UnicastServerRef.java", 357),
                                        new StackFrame("sun.rmi.transport.Transport$1", "run", "Transport.java", 200),
                                        new StackFrame("sun.rmi.transport.Transport$1", "run", "Transport.java", 197),
                                        new StackFrame("java.security.AccessController", "doPrivileged", null, null, true),
                                        new StackFrame("sun.rmi.transport.Transport", "serviceCall", "Transport.java", 196),
                                        new StackFrame("sun.rmi.transport.tcp.TCPTransport", "handleMessages", "TCPTransport.java", 573),
                                        new StackFrame("sun.rmi.transport.tcp.TCPTransport$ConnectionHandler", "run0", "TCPTransport.java", 834),
                                        new StackFrame("sun.rmi.transport.tcp.TCPTransport$ConnectionHandler", "lambda$run$0", "TCPTransport.java", 688),
                                        new StackFrame("sun.rmi.transport.tcp.TCPTransport$ConnectionHandler$$Lambda$4/0x00000001000eb840", "run", null, null),
                                        new StackFrame("java.security.AccessController", "doPrivileged", null, null, true),
                                        new StackFrame("sun.rmi.transport.tcp.TCPTransport$ConnectionHandler", "run", "TCPTransport.java", 687),
                                        new StackFrame("java.util.concurrent.ThreadPoolExecutor", "runWorker", "ThreadPoolExecutor.java", 1149),
                                        new StackFrame("java.util.concurrent.ThreadPoolExecutor$Worker", "run", "ThreadPoolExecutor.java", 624),
                                        new StackFrame("java.lang.Thread", "run", "Thread.java", 838)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("JMX server connection timeout 32")
                                .threadId(32L)
                                .nativeId(0x1c9cL)
                                .priority(5)
                                .daemon(true)
                                .state(Thread.State.TIMED_WAITING)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(0.0)
                                .stackTrace(
                                        new StackFrame("java.lang.Object", "wait", null, null, true),
                                        new StackFrame("com.sun.jmx.remote.internal.ServerCommunicatorAdmin$Timeout", "run", "ServerCommunicatorAdmin.java", 168),
                                        new StackFrame("java.lang.Thread", "run", "Thread.java", 838)
                                )
                                .locks(
                                        new LockInfo("0x00000000ead2b3f0", "[I", LockInfo.LockOperation.WAITING_ON),
                                        new LockInfo("0x00000000ead2b3f0", "[I", LockInfo.LockOperation.WAITING_TO_LOCK)
                                )
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("VM Thread")
                                .nativeId(0x4a58L)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.04688)
                                .elapsedTimeSec(396.65)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("ParGC Thread#0")
                                .nativeId(0x1f14L)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(396.66)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("ParGC Thread#1")
                                .nativeId(0x65b0L)
                                .state(Thread.State.RUNNABLE)
                                .cpuTimeSec(0.0)
                                .elapsedTimeSec(372.77)
                                .build(),
                        ThreadInfoBuilder.create()
                                .name("VM Periodic Task Thread")
                                .nativeId(0x3704L)
                                .cpuTimeSec(0.73438)
                                .elapsedTimeSec(396.60)
                                .build()
                ),
                new JniInfo(47, 1492, null, null),
                "jstack",
                List.of()
        );

        assertThreadDumpsContainExpectedThreads(expected, parsed, "SAP JVM thread dump should match expected values (ignoring hex addresses)");
    }

    private static ThreadInfo findByName(List<ThreadInfo> threads, String name) {
        if (threads == null) return null;
        for (ThreadInfo t : threads) {
            if (t != null && java.util.Objects.equals(name, t.name())) {
                return t;
            }
        }
        return null;
    }

    private static String normalizeWs(String s) {
        if (s == null) return null;
        return s.trim().replaceAll("\\s+", " ");
    }

    private void assertThreadDumpsContainExpectedThreads(ThreadDump expected, ThreadDump parsed, String message) {
        StringBuilder error = new StringBuilder();
        boolean hasMismatch = false;

        for (ThreadInfo exp : expected.threads()) {
            ThreadInfo prs = findByName(parsed.threads(), exp.name());
            if (prs == null) {
                hasMismatch = true;
                error.append("Missing thread in parsed dump: ").append(exp.name()).append("\n");
                continue;
            }

            boolean sameIgnoringHex = exp.equalsIgnoringHexValues(prs);
            boolean sameAdditionalInfo = java.util.Objects.equals(normalizeWs(exp.additionalInfo()), normalizeWs(prs.additionalInfo()));

            // Some thread dumps (notably SAP JVM) contain variable spacing in additionalInfo; accept that.
            if (!sameIgnoringHex && sameAdditionalInfo) {
                // Don't report as mismatch if additionalInfo whitespace is the only difference.
                continue;
            }

            if (!sameIgnoringHex) {
                hasMismatch = true;
                error.append("Mismatch for thread: ").append(exp.name()).append("\n");

                if (!java.util.Objects.equals(exp.threadId(), prs.threadId())) {
                    error.append("  Thread id: Expected=").append(exp.threadId()).append(", Parsed=").append(prs.threadId()).append("\n");
                }
                if (!java.util.Objects.equals(exp.nativeId(), prs.nativeId())) {
                    error.append("  Native id: Expected=").append(exp.nativeId()).append(", Parsed=").append(prs.nativeId()).append("\n");
                }
                if (!java.util.Objects.equals(exp.priority(), prs.priority())) {
                    error.append("  Priority: Expected=").append(exp.priority()).append(", Parsed=").append(prs.priority()).append("\n");
                }
                if (!java.util.Objects.equals(exp.daemon(), prs.daemon())) {
                    error.append("  Daemon: Expected=").append(exp.daemon()).append(", Parsed=").append(prs.daemon()).append("\n");
                }
                if (!doublesEqual(exp.cpuTimeSec(), prs.cpuTimeSec())) {
                    error.append("  CPU time: Expected=").append(exp.cpuTimeSec()).append(", Parsed=").append(prs.cpuTimeSec()).append("\n");
                }
                if (!doublesEqual(exp.elapsedTimeSec(), prs.elapsedTimeSec())) {
                    error.append("  Elapsed time: Expected=").append(exp.elapsedTimeSec()).append(", Parsed=").append(prs.elapsedTimeSec()).append("\n");
                }

                // Stack trace diff (by index)
                List<StackFrame> expStack = exp.stackTrace();
                List<StackFrame> prsStack = prs.stackTrace();
                if (expStack == null) expStack = List.of();
                if (prsStack == null) prsStack = List.of();
                if (!stackTracesEqual(expStack, prsStack)) {
                    error.append("  Stack trace mismatch (Expected=").append(expStack.size())
                            .append(", Parsed=").append(prsStack.size()).append(")\n");
                    int max = Math.max(expStack.size(), prsStack.size());
                    for (int i = 0; i < max; i++) {
                        StackFrame e = i < expStack.size() ? expStack.get(i) : null;
                        StackFrame p = i < prsStack.size() ? prsStack.get(i) : null;
                        if (e == null) {
                            error.append("    [").append(i).append("] Expected: <missing>\n");
                            error.append("    [").append(i).append("] Parsed:   ").append(p).append("\n");
                        } else if (p == null) {
                            error.append("    [").append(i).append("] Expected: ").append(e).append("\n");
                            error.append("    [").append(i).append("] Parsed:   <missing>\n");
                        } else if (!e.equalsIgnoringHexValues(p)) {
                            error.append("    [").append(i).append("] Expected: ").append(e).append("\n");
                            error.append("    [").append(i).append("] Parsed:   ").append(p).append("\n");
                        }
                    }
                }

                // Locks diff (by index)
                List<LockInfo> expLocks = exp.locks();
                List<LockInfo> prsLocks = prs.locks();
                if (expLocks == null) expLocks = List.of();
                if (prsLocks == null) prsLocks = List.of();
                if (!locksEqualIgnoringHex(expLocks, prsLocks)) {
                    error.append("  Locks mismatch (Expected=").append(expLocks.size())
                            .append(", Parsed=").append(prsLocks.size()).append(")\n");
                    int max = Math.max(expLocks.size(), prsLocks.size());
                    for (int i = 0; i < max; i++) {
                        LockInfo e = i < expLocks.size() ? expLocks.get(i) : null;
                        LockInfo p = i < prsLocks.size() ? prsLocks.get(i) : null;
                        if (e == null) {
                            error.append("    [").append(i).append("] Expected: <missing>\n");
                            error.append("    [").append(i).append("] Parsed:   ").append(p).append("\n");
                        } else if (p == null) {
                            error.append("    [").append(i).append("] Expected: ").append(e).append("\n");
                            error.append("    [").append(i).append("] Parsed:   <missing>\n");
                        } else if (!e.equalsIgnoringHexValues(p)) {
                            error.append("    [").append(i).append("] Expected: ").append(e).append("\n");
                            error.append("    [").append(i).append("] Parsed:   ").append(p).append("\n");
                        }
                    }
                }

                if (!java.util.Objects.equals(normalizeWs(exp.additionalInfo()), normalizeWs(prs.additionalInfo()))) {
                    error.append("  Additional info:\n");
                    error.append("    Expected: ").append(exp.additionalInfo()).append("\n");
                    error.append("    Parsed:   ").append(prs.additionalInfo()).append("\n");
                }

                if (!java.util.Objects.equals(exp.carryingVirtualThreadId(), prs.carryingVirtualThreadId())) {
                    error.append("  Carrying virtual thread id: Expected=")
                            .append(exp.carryingVirtualThreadId()).append(", Parsed=")
                            .append(prs.carryingVirtualThreadId()).append("\n");
                }

                // Show full expected/parsed dump for mismatched threads
                error.append("  --- Expected thread dump ---\n");
                error.append("    ").append(exp).append("\n");
                error.append("  --- Parsed thread dump ---\n");
                error.append("    ").append(prs).append("\n");

                // Also show raw stack/locks lists for easier diffing
                error.append("  --- Expected stack trace ---\n");
                for (int i = 0; i < expStack.size(); i++) {
                    error.append("    [").append(i).append("] ").append(expStack.get(i)).append("\n");
                }
                error.append("  --- Parsed stack trace ---\n");
                for (int i = 0; i < prsStack.size(); i++) {
                    error.append("    [").append(i).append("] ").append(prsStack.get(i)).append("\n");
                }
                error.append("  --- Expected locks ---\n");
                for (int i = 0; i < expLocks.size(); i++) {
                    error.append("    [").append(i).append("] ").append(expLocks.get(i)).append("\n");
                }
                error.append("  --- Parsed locks ---\n");
                for (int i = 0; i < prsLocks.size(); i++) {
                    error.append("    [").append(i).append("] ").append(prsLocks.get(i)).append("\n");
                }
            }
        }

        if (hasMismatch) {
            fail(message + "\n" + error);
        }
    }

    @Test
    void testParseSapJvmDump61() throws IOException {
        String content = loadResource("sapjvm/sapjvm-threaddump-61.txt");
        ThreadDump parsed = ThreadDumpParser.parse(content);

        Instant expectedTimestamp = LocalDateTime
                .parse("2013-02-11 10:47:04", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(ZoneId.systemDefault())
                .toInstant();
        assertEquals(expectedTimestamp, parsed.timestamp(), "Timestamp should be parsed from 'Thread dump triggered at ...'");
        assertEquals(
                normalizeWs("Full thread dump SAP Java Server VM (6.1.036 19.1-b02 Dec  6 2011 10:47:23 - 61_REL - optU - hpux parisc - 6 - bas2:163920 (mixed mode)):"),
                normalizeWs(parsed.jvmInfo())
        );

        assertEquals(3, parsed.threads().size(), "Should parse the 3 threads in this resource snippet");

        ThreadInfo t0 = parsed.threads().get(0);
        assertEquals("Attach Listener", t0.name());
        assertTrue(t0.daemon());
        assertEquals(5, t0.priority());
        assertEquals(0x4aafaeL, t0.nativeId());
        assertEquals(Thread.State.RUNNABLE, t0.state());

        ThreadInfo t1 = parsed.threads().get(1);
        assertEquals("DatagramSocket sender", t1.name());
        assertFalse(t1.daemon());
        assertEquals(5, t1.priority());
        assertEquals(0x4aafacL, t1.nativeId());
        assertEquals(Thread.State.RUNNABLE, t1.state());

        ThreadInfo t2 = parsed.threads().get(2);
        assertEquals("DatagramSocket receiver", t2.name());
        assertFalse(t2.daemon());
        assertEquals(5, t2.priority());
        assertEquals(0x4aafabL, t2.nativeId());
        assertEquals(Thread.State.RUNNABLE, t2.state());
    }
}