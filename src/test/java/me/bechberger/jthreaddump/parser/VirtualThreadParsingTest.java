package me.bechberger.jthreaddump.parser;

import me.bechberger.jthreaddump.model.StackFrame;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for parsing virtual threads in thread dumps.
 * Virtual threads (Project Loom) are supported in Java 21+.
 */
class VirtualThreadParsingTest {

    @Test
    void testVirtualThreadWithCarryingLine() throws IOException {
        String threadDump = """
                Full thread dump OpenJDK 64-Bit Server VM (21.0.9+11-LTS mixed mode):
                
                "ForkJoinPool-1-worker-1" #180 [197123] daemon prio=6 os_prio=31 cpu=0.46ms elapsed=40545.13s tid=0x0000000111f7a000  [0x000000047e229000]
                   Carrying virtual thread #179
                \tat jdk.internal.vm.Continuation.run(java.base@21.0.9/Continuation.java:248)
                \tat java.lang.VirtualThread.runContinuation(java.base@21.0.9/VirtualThread.java:245)
                \tat java.lang.VirtualThread$$Lambda/0x00000078038c15e0.run(java.base@21.0.9/Unknown Source)
                \tat java.util.concurrent.ForkJoinTask$RunnableExecuteAction.compute(java.base@21.0.9/ForkJoinTask.java:1726)
                \tat java.util.concurrent.ForkJoinTask$RunnableExecuteAction.compute(java.base@21.0.9/ForkJoinTask.java:1717)
                \tat java.util.concurrent.ForkJoinTask$InterruptibleTask.exec(java.base@21.0.9/ForkJoinTask.java:1641)
                \tat java.util.concurrent.ForkJoinTask.doExec(java.base@21.0.9/ForkJoinTask.java:507)
                \tat java.util.concurrent.ForkJoinPool$WorkQueue.topLevelExec(java.base@21.0.9/ForkJoinPool.java:1491)
                \tat java.util.concurrent.ForkJoinPool.scan(java.base@21.0.9/ForkJoinPool.java:2073)
                \tat java.util.concurrent.ForkJoinPool.runWorker(java.base@21.0.9/ForkJoinPool.java:2035)
                \tat java.util.concurrent.ForkJoinWorkerThread.run(java.base@21.0.9/ForkJoinWorkerThread.java:187)
                """;

        ThreadDump parsed = ThreadDumpParser.parse(threadDump);

        // Verify basic structure
        assertNotNull(parsed);
        assertEquals(1, parsed.threads().size());

        ThreadInfo thread = parsed.threads().getFirst();

        // Verify thread basic properties
        assertEquals("ForkJoinPool-1-worker-1", thread.name());
        assertEquals(180L, thread.threadId());
        assertEquals(6, thread.priority());
        assertTrue(thread.daemon());

        // Verify CPU and elapsed times are parsed correctly (in seconds)
        assertEquals(0.00046, thread.cpuTimeSec(), 0.000001);
        assertEquals(40545.13, thread.elapsedTimeSec(), 0.01);

        // MOST IMPORTANT: Verify virtual thread ID is captured
        assertNotNull(thread.carryingVirtualThreadId(), "Should have carryingVirtualThreadId");
        assertEquals(179L, thread.carryingVirtualThreadId());

        // Verify stack trace
        assertEquals(11, thread.stackTrace().size());

        // Check a few key stack frames
        StackFrame firstFrame = thread.stackTrace().getFirst();
        assertEquals("jdk.internal.vm.Continuation", firstFrame.className());
        assertEquals("run", firstFrame.methodName());
        assertEquals("Continuation.java", firstFrame.fileName());
        assertEquals(248, firstFrame.lineNumber());

        StackFrame secondFrame = thread.stackTrace().get(1);
        assertEquals("java.lang.VirtualThread", secondFrame.className());
        assertEquals("runContinuation", secondFrame.methodName());
        assertEquals("VirtualThread.java", secondFrame.fileName());
        assertEquals(245, secondFrame.lineNumber());

        // Lambda frame
        StackFrame lambdaFrame = thread.stackTrace().get(2);
        assertEquals("java.lang.VirtualThread$$Lambda/0x00000078038c15e0", lambdaFrame.className());
        assertEquals("run", lambdaFrame.methodName());
        assertNull(lambdaFrame.fileName()); // Unknown Source
        assertNull(lambdaFrame.lineNumber());

        // ForkJoin frame
        StackFrame lastFrame = thread.stackTrace().get(10);
        assertEquals("java.util.concurrent.ForkJoinWorkerThread", lastFrame.className());
        assertEquals("run", lastFrame.methodName());
        assertEquals("ForkJoinWorkerThread.java", lastFrame.fileName());
        assertEquals(187, lastFrame.lineNumber());
    }

    @Test
    void testVirtualThreadIdInBrackets() throws IOException {
        // Test parsing of virtual thread ID from square brackets in header
        String threadDump = """
                "ForkJoinPool-1-worker-2" #65 [62471] daemon prio=5 os_prio=31 cpu=305.89ms elapsed=15.79s tid=0x000000013e056a00
                   Carrying virtual thread #206
                \tat java.lang.Thread.sleep(java.base@21/Native Method)
                """;

        ThreadDump parsed = ThreadDumpParser.parse(threadDump);

        assertEquals(1, parsed.threads().size());
        ThreadInfo thread = parsed.threads().getFirst();

        // Thread ID from #65
        assertEquals(65L, thread.threadId());

        // Also from "Carrying virtual thread #206"
        // Note: This should override the bracket value
        assertEquals(206L, thread.carryingVirtualThreadId());
    }

    @Test
    void testMultipleVirtualThreads() throws IOException {
        String threadDump = """
                Full thread dump:
                
                "ForkJoinPool-1-worker-1" #65 daemon prio=5 cpu=1.5ms elapsed=100.0s
                   Carrying virtual thread #100
                \tat java.lang.Thread.sleep(Native Method)
                
                "ForkJoinPool-1-worker-2" #66 daemon prio=5 cpu=2.0ms elapsed=100.0s
                   Carrying virtual thread #101
                \tat java.lang.Thread.sleep(Native Method)
                
                "main" #1 prio=5 cpu=50.0ms elapsed=200.0s
                \tat java.lang.Object.wait(Native Method)
                """;

        ThreadDump parsed = ThreadDumpParser.parse(threadDump);

        assertEquals(3, parsed.threads().size());

        // First worker - carrying virtual thread
        ThreadInfo worker1 = parsed.threads().getFirst();
        assertEquals("ForkJoinPool-1-worker-1", worker1.name());
        assertEquals(100L, worker1.carryingVirtualThreadId());

        // Second worker - carrying virtual thread
        ThreadInfo worker2 = parsed.threads().get(1);
        assertEquals("ForkJoinPool-1-worker-2", worker2.name());
        assertEquals(101L, worker2.carryingVirtualThreadId());

        // Main thread - NOT carrying virtual thread
        ThreadInfo main = parsed.threads().get(2);
        assertEquals("main", main.name());
        assertNull(main.carryingVirtualThreadId(), "Main thread should not be carrying a virtual thread");
    }

    @Test
    void testVirtualThreadWithComplexStackTrace() throws IOException {
        String threadDump = """
                "ForkJoinPool-1-worker-3" #180 daemon prio=6 cpu=10.5ms elapsed=5000.0s
                   Carrying virtual thread #250
                \tat java.lang.Thread.sleep(java.base@21/Native Method)
                \tat com.example.Service.processRequest(Service.java:42)
                \tat com.example.Service$$Lambda.run(Unknown Source)
                \tat jdk.internal.vm.Continuation.run(java.base@21/Continuation.java:248)
                \tat java.lang.VirtualThread.runContinuation(java.base@21/VirtualThread.java:245)
                """;

        ThreadDump parsed = ThreadDumpParser.parse(threadDump);

        ThreadInfo thread = parsed.threads().getFirst();

        assertEquals(250L, thread.carryingVirtualThreadId());
        assertEquals(5, thread.stackTrace().size());

        // Verify time parsing
        assertEquals(0.0105, thread.cpuTimeSec(), 0.0001);
        assertEquals(5000.0, thread.elapsedTimeSec(), 0.1);

        // Check native method
        assertTrue(thread.stackTrace().get(0).nativeMethod() != null && thread.stackTrace().get(0).nativeMethod());

        // Check user code
        StackFrame userFrame = thread.stackTrace().get(1);
        assertEquals("com.example.Service", userFrame.className());
        assertEquals("processRequest", userFrame.methodName());
        assertEquals(42, userFrame.lineNumber());
    }

    @Test
    void testVirtualThreadWithState() throws IOException {
        String threadDump = """
                "ForkJoinPool-1-worker-1" #100 daemon
                   Carrying virtual thread #99
                   java.lang.Thread.State: RUNNABLE
                \tat java.lang.Thread.yield(Native Method)
                """;

        ThreadDump parsed = ThreadDumpParser.parse(threadDump);

        ThreadInfo thread = parsed.threads().getFirst();

        assertEquals(99L, thread.carryingVirtualThreadId());
        assertEquals(Thread.State.RUNNABLE, thread.state());
    }

    @Test
    void testCarryingLineWithoutVirtualThreadId() throws IOException {
        // Edge case: malformed carrying line
        String threadDump = """
                "ForkJoinPool-1-worker-1" #100 daemon
                   Carrying virtual thread
                \tat java.lang.Thread.sleep(Native Method)
                """;

        ThreadDump parsed = ThreadDumpParser.parse(threadDump);

        ThreadInfo thread = parsed.threads().getFirst();

        // Should gracefully handle malformed line
        assertNull(thread.carryingVirtualThreadId());
    }

    @Test
    void testVirtualThreadBracketIdOnly() throws IOException {
        // Test when virtual thread ID is only in brackets, no "Carrying" line
        String threadDump = """
                "ForkJoinPool-1-worker-1" #100 [12345] daemon
                \tat java.lang.Thread.sleep(Native Method)
                """;

        ThreadDump parsed = ThreadDumpParser.parse(threadDump);

        ThreadInfo thread = parsed.threads().getFirst();

        // Should capture from brackets
        assertEquals(12345L, thread.carryingVirtualThreadId());
    }
}