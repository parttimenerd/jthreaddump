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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parametrized tests for ThreadDumpParser
 */
class ThreadDumpParserTest {

    private final ThreadDumpParser parser = new ThreadDumpParser();

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

                if (!java.util.Objects.equals(exp.cpuTimeMs(), prs.cpuTimeMs())) {
                    error.append("  CPU time: Expected=").append(exp.cpuTimeMs())
                         .append(", Parsed=").append(prs.cpuTimeMs()).append("\n");
                }

                if (!java.util.Objects.equals(exp.elapsedTimeMs(), prs.elapsedTimeMs())) {
                    error.append("  Elapsed time: Expected=").append(exp.elapsedTimeMs())
                         .append(", Parsed=").append(prs.elapsedTimeMs()).append("\n");
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
        ThreadDump dump = parser.parse(content);

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
        ThreadDump parsed = parser.parse(content);

        ThreadDump expected = new ThreadDump(
                parsed.timestamp(),
                "Full thread dump Java HotSpot(TM) 64-Bit Server VM (21.0.1+12-LTS-29 mixed mode, sharing):",
                List.of(
                        new ThreadInfo(
                                "main",
                                1L,
                                0x2803L,
                                5,
                                null,
                                Thread.State.RUNNABLE,
                                125L,
                                10250L,
                                List.of(
                                        new StackFrame("java.io.FileInputStream", "readBytes", null, null, true),
                                        new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 276),
                                        new StackFrame("java.io.BufferedInputStream", "fill", "BufferedInputStream.java", 244),
                                        new StackFrame("com.example.Main", "readFile", "Main.java", 42)
                                ),
                                List.of(),
                                null,
                                null
                        ),
                        new ThreadInfo(
                                "Reference Handler",
                                2L,
                                0x3003L,
                                10,
                                true,
                                Thread.State.WAITING,
                                0L,
                                10200L,
                                List.of(
                                        new StackFrame("java.lang.ref.Reference", "waitForReferencePendingList", null, null, true),
                                        new StackFrame("java.lang.ref.Reference", "processPendingReferences", "Reference.java", 246),
                                        new StackFrame("java.lang.ref.Reference$ReferenceHandler", "run", "Reference.java", 208)
                                ),
                                List.of(),
                                null,
                                null
                        ),
                        new ThreadInfo(
                                "Finalizer",
                                3L,
                                0x4803L,
                                8,
                                true,
                                Thread.State.WAITING,
                                0L,
                                10190L,
                                List.of(
                                        new StackFrame("java.lang.Object", "wait", null, null, true),
                                        new StackFrame("java.lang.ref.ReferenceQueue", "remove", "ReferenceQueue.java", 155),
                                        new StackFrame("java.lang.ref.ReferenceQueue", "remove", "ReferenceQueue.java", 176),
                                        new StackFrame("java.lang.ref.Finalizer$FinalizerThread", "run", "Finalizer.java", 172)
                                ),
                                List.of(
                                        new LockInfo("0x00000007ffc00000", "java.lang.ref.ReferenceQueue$Lock", "waiting on"),
                                        new LockInfo("0x00000007ffc00000", "java.lang.ref.ReferenceQueue$Lock", "locked")
                                ),
                                "0x00000007ffc00000",
                                null
                        ),
                        new ThreadInfo(
                                "Worker-1",
                                10L,
                                0x5803L,
                                5,
                                null,
                                Thread.State.RUNNABLE,
                                2500L,
                                8500L,
                                List.of(
                                        new StackFrame("java.net.SocketInputStream", "socketRead0", null, null, true),
                                        new StackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 186),
                                        new StackFrame("com.example.Worker", "processRequest", "Worker.java", 67)
                                ),
                                List.of(),
                                null,
                                null
                        ),
                        new ThreadInfo(
                                "Worker-2",
                                11L,
                                0x6003L,
                                5,
                                null,
                                Thread.State.BLOCKED,
                                0L,
                                8450L,
                                List.of(
                                        new StackFrame("com.example.Worker", "processRequest", "Worker.java", 45),
                                        new StackFrame("com.example.Worker", "run", "Worker.java", 30)
                                ),
                                List.of(
                                        new LockInfo("0x00000007ffc12345", "java.lang.Object", "waiting on")
                                ),
                                "0x00000007ffc12345",
                                null
                        )
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
        ThreadDump parsed = parser.parse(content);

        ThreadDump expected = new ThreadDump(
                parsed.timestamp(), // Use parsed timestamp
                null,
                List.of(
                        new ThreadInfo(
                                "Thread-A",
                                0x1000L,
                                0x1000L,
                                5,
                                null,
                                Thread.State.BLOCKED,
                                null,
                                null,
                                List.of(
                                        new StackFrame("com.example.DeadlockExample", "methodA", "DeadlockExample.java", 20),
                                        new StackFrame("com.example.DeadlockExample", "run", "DeadlockExample.java", 10)
                                ),
                                List.of(
                                        new LockInfo("0x00000007ffc11111", "java.lang.Object", "waiting on"),
                                        new LockInfo("0x00000007ffc22222", "java.lang.Object", "locked")
                                ),
                                "0x00000007ffc11111",
                                null
                        ),
                        new ThreadInfo(
                                "Thread-B",
                                0x2000L,
                                0x2000L,
                                5,
                                null,
                                Thread.State.BLOCKED,
                                null,
                                null,
                                List.of(
                                        new StackFrame("com.example.DeadlockExample", "methodB", "DeadlockExample.java", 30),
                                        new StackFrame("com.example.DeadlockExample", "run", "DeadlockExample.java", 15)
                                ),
                                List.of(
                                        new LockInfo("0x00000007ffc22222", "java.lang.Object", "waiting on"),
                                        new LockInfo("0x00000007ffc11111", "java.lang.Object", "locked")
                                ),
                                "0x00000007ffc22222",
                                null
                        )
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
        ThreadDump parsed = parser.parse(content);

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
                        new ThreadInfo(
                                "DeadlockThread-A",
                                null, // Ignored by custom equals
                                null, // Ignored by custom equals
                                5,    // priority from dump
                                null,
                                Thread.State.BLOCKED,
                                null, // no cpu time in dump
                                null, // no elapsed time in dump
                                List.of(
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator", "lambda$multiDeadlockScenario$10", "ThreadDumpGenerator.java", 632),
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator$$Lambda", "run", null, null)
                                ),
                                List.of(
                                        new LockInfo(null, "java.lang.Object", "waiting on"),
                                        new LockInfo(null, "java.lang.Object", "locked")
                                ),
                                null,
                                null
                        ),
                        new ThreadInfo(
                                "DeadlockThread-B",
                                null,
                                null,
                                5,
                                null,
                                Thread.State.BLOCKED,
                                null,
                                null,
                                List.of(
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator", "lambda$multiDeadlockScenario$11", "ThreadDumpGenerator.java", 648),
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator$$Lambda", "run", null, null)
                                ),
                                List.of(
                                        new LockInfo(null, "java.lang.Object", "waiting on"),
                                        new LockInfo(null, "java.lang.Object", "locked")
                                ),
                                null,
                                null
                        ),
                        new ThreadInfo(
                                "DeadlockThread-C",
                                null,
                                null,
                                5,
                                null,
                                Thread.State.BLOCKED,
                                null,
                                null,
                                List.of(
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator", "lambda$multiDeadlockScenario$12", "ThreadDumpGenerator.java", 664),
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator$$Lambda", "run", null, null)
                                ),
                                List.of(
                                        new LockInfo(null, "java.lang.Object", "waiting on"),
                                        new LockInfo(null, "java.lang.Object", "locked")
                                ),
                                null,
                                null
                        ),
                        new ThreadInfo(
                                "DeadlockThread-D",
                                null,
                                null,
                                5,
                                null,
                                Thread.State.BLOCKED,
                                null,
                                null,
                                List.of(
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator", "lambda$multiDeadlockScenario$13", "ThreadDumpGenerator.java", 680),
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator$$Lambda", "run", null, null)
                                ),
                                List.of(
                                        new LockInfo(null, "java.lang.Object", "waiting on"),
                                        new LockInfo(null, "java.lang.Object", "locked")
                                ),
                                null,
                                null
                        ),
                        new ThreadInfo(
                                "WorkerThread-1",
                                null,
                                null,
                                5,
                                null,
                                Thread.State.RUNNABLE,  // RUNNABLE in the actual dump
                                null,
                                null,
                                List.of(
                                        new StackFrame("java.lang.Math", "sqrt", null, null, true),
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator", "lambda$multiDeadlockScenario$14", "ThreadDumpGenerator.java", 695),
                                        new StackFrame("me.bechberger.jthreaddump.test.ThreadDumpGenerator$$Lambda", "run", null, null)
                                ),
                                List.of(),
                                null,
                                null
                        )
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
        ThreadDump parsed = parser.parse(content);

        ThreadDump expected = new ThreadDump(
                parsed.timestamp(),
                null,
                List.of(
                        new ThreadInfo(
                                "Minimal-Thread",
                                0x1L,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                null,
                                null
                        )
                ),
                null,
                "unknown"
        );

        assertThreadDumpsEqual(expected, parsed, "ThreadDump should match expected values (ignoring hex addresses)");
    }

    @Test
    void testParseJcmd() throws IOException {
        String content = loadResource("thread-dump-jcmd.txt");
        ThreadDump parsed = parser.parse(content);

        ThreadDump expected = new ThreadDump(
                parsed.timestamp(),
                "Thread dump from jcmd 12345 Thread.print:",
                List.of(
                        new ThreadInfo(
                                "main",
                                1L,
                                0x2803L,
                                5,
                                null,
                                Thread.State.RUNNABLE,
                                null,
                                null,
                                List.of(
                                        new StackFrame("java.io.FileInputStream", "readBytes", null, null, true),
                                        new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 276)
                                ),
                                List.of(),
                                null,
                                null
                        ),
                        new ThreadInfo(
                                "GC Thread#0",
                                2L,
                                0x3003L,
                                10,
                                true,
                                null,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                null,
                                null
                        ),
                        new ThreadInfo(
                                "VM Thread",
                                3L,
                                0x4803L,
                                10,
                                true,
                                null,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                null,
                                null
                        ),
                        new ThreadInfo(
                                "Service Thread",
                                4L,
                                0x5003L,
                                9,
                                true,
                                null,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                null,
                                null
                        )
                ),
                new JniInfo(150, null, null, null),
                "jcmd"
        );

        assertThreadDumpsEqual(expected, parsed, "ThreadDump should match expected values (ignoring hex addresses)");
    }

    @Test
    void testEmptyInput() throws IOException {
        ThreadDump dump = parser.parse("");
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

        ThreadDump parsed = parser.parse(malformed);

        ThreadDump expected = new ThreadDump(
                parsed.timestamp(),
                null,
                List.of(
                        new ThreadInfo(
                                "Weird-Thread",
                                null,
                                null,
                                null,
                                null,
                                Thread.State.RUNNABLE,
                                null,
                                null,
                                List.of(
                                        new StackFrame("some.Class", "method", null, null)
                                ),
                                List.of(),
                                null,
                                null
                        )
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
                Arguments.of("cpu=1.5s", 1500L),
                Arguments.of("cpu=250ms", 250L),
                Arguments.of("cpu=500000us", 500L),
                Arguments.of("cpu=1000000ns", 1L),
                Arguments.of("elapsed=10.25s", 10250L)
        );
    }

    @ParameterizedTest(name = "Time parsing: {0} -> {1}ms")
    @MethodSource("timeParsingProvider")
    void testTimeParsing(String timeLine, long expectedMs) throws IOException {
        String threadDump = String.format("""
                "Test" #1 prio=5 %s tid=0x1000 nid=0x1000 runnable
                   java.lang.Thread.State: RUNNABLE
                """, timeLine);

        ThreadDump dump = parser.parse(threadDump);
        assertEquals(1, dump.threads().size());
        ThreadInfo thread = dump.threads().get(0);

        if (timeLine.startsWith("cpu=")) {
            assertEquals(expectedMs, thread.cpuTimeMs());
        } else if (timeLine.startsWith("elapsed=")) {
            assertEquals(expectedMs, thread.elapsedTimeMs());
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

        ThreadDump dump = parser.parse(threadDump);
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

        ThreadDump parsed = parser.parse(threadDump);

        ThreadDump expected = new ThreadDump(
                parsed.timestamp(),
                null,
                List.of(
                        new ThreadInfo(
                                "Test",
                                1L,
                                null,
                                null,
                                null,
                                Thread.State.RUNNABLE,
                                null,
                                null,
                                List.of(
                                        new StackFrame("com.example.Test", "method1", "Test.java", 10),
                                        new StackFrame("com.example.Test", "method2", null, null, true),
                                        new StackFrame("com.example.Test", "method3", null, null),
                                        new StackFrame("com.example.Test", "method4", "Test.java", null)
                                ),
                                List.of(),
                                null,
                                null
                        )
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
}