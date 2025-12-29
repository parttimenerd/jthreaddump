package me.bechberger.jthreaddump.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;
import me.bechberger.jthreaddump.test.ThreadDumpGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enhanced tests with real-world thread dumps, locks, virtual threads, and model equality validation
 */
class EnhancedThreadDumpParserTest {

    private final ThreadDumpParser parser = new ThreadDumpParser();
    private final ObjectMapper jsonMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * Test scenarios with generated real-world thread dumps
     */
    static Stream<Arguments> realWorldScenarios() {
        return Stream.of(
                Arguments.of("deadlock", ThreadDumpGenerator.deadlockScenario(), 2, true),
                Arguments.of("reentrant-lock", ThreadDumpGenerator.reentrantLockScenario(), 2, true),
                Arguments.of("virtual-threads", ThreadDumpGenerator.virtualThreadScenario(), 3, false),
                Arguments.of("complex", ThreadDumpGenerator.complexScenario(), 5, true)
        );
    }

    @ParameterizedTest(name = "Real-world scenario: {0}")
    @MethodSource("realWorldScenarios")
    void testRealWorldScenarios(String scenarioName, ThreadDumpGenerator.ThreadDumpScenario scenario,
                                 int minExpectedThreads, boolean expectLocks) throws IOException {
        // Get or generate cached dump
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump(scenarioName, scenario);
        assertTrue(Files.exists(dumpFile), "Dump file should exist");

        String content = Files.readString(dumpFile);
        ThreadDump dump = parser.parse(content);

        assertNotNull(dump);
        assertTrue(dump.threads().size() >= minExpectedThreads,
                String.format("Expected at least %d threads, got %d", minExpectedThreads, dump.threads().size()));
        assertNotNull(dump.threads());
        assertNotNull(dump.sourceType());

        if (expectLocks) {
            // At least one thread should have locks
            boolean hasLocks = dump.threads().stream()
                    .anyMatch(t -> !t.locks().isEmpty() || t.waitingOnLock() != null);
            assertTrue(hasLocks, "Expected at least one thread with locks");
        }

        // Verify JSON round-trip maintains data integrity
        String json = jsonMapper.writeValueAsString(dump);
        ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

        assertEquals(dump, deserializedDump, "Round-trip serialization should preserve dump equality");
    }

    @Test
    void testDeadlockScenarioDetailed() throws IOException {
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump("deadlock",
                ThreadDumpGenerator.deadlockScenario());
        String content = Files.readString(dumpFile);
        ThreadDump dump = parser.parse(content);

        // Find deadlocked threads
        ThreadInfo threadA = findThreadByNameContains(dump, "DeadlockThread-A");
        ThreadInfo threadB = findThreadByNameContains(dump, "DeadlockThread-B");

        if (threadA != null && threadB != null) {
            // Both should be in BLOCKED or WAITING state
            assertTrue(threadA.state() == Thread.State.BLOCKED || threadA.state() == Thread.State.WAITING,
                    "Thread A should be blocked or waiting");
            assertTrue(threadB.state() == Thread.State.BLOCKED || threadB.state() == Thread.State.WAITING,
                    "Thread B should be blocked or waiting");

            // Verify JSON round-trip preserves state
            String json = jsonMapper.writeValueAsString(dump);
            ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

            assertEquals(dump, deserializedDump, "Round-trip should preserve entire dump");
        }
    }

    @Test
    void testVirtualThreadsScenarioDetailed() throws IOException {
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump("virtual-threads",
                ThreadDumpGenerator.virtualThreadScenario());
        String content = Files.readString(dumpFile);
        ThreadDump dump = parser.parse(content);

        // Note: jstack doesn't show virtual threads by name, only carrier threads
        // Count carrier threads (ForkJoinPool workers) which is what carries virtual threads
        long carrierThreadCount = dump.threads().stream()
                .filter(t -> t.name() != null && t.name().startsWith("ForkJoinPool"))
                .count();

        // We should have at least some carrier threads when virtual threads are running
        // Changed from looking for VirtualWorker threads (which jstack doesn't show)
        // to looking for ForkJoinPool carrier threads
        assertTrue(carrierThreadCount >= 3,
                "Expected at least 3 carrier threads for virtual threads, found: " + carrierThreadCount);

        // Verify we have the VirtualThread-unblocker thread which is specific to virtual threads
        boolean hasVirtualThreadSupport = dump.threads().stream()
                .anyMatch(t -> t.name() != null && t.name().contains("VirtualThread"));

        assertTrue(hasVirtualThreadSupport, "Expected to find virtual thread support infrastructure");

        // Verify carrier threads have required fields
        dump.threads().stream()
                .filter(t -> t.name() != null && t.name().startsWith("ForkJoinPool"))
                .forEach(t -> {
                    assertNotNull(t.name());
                    assertNotNull(t.state());
                });

        // Verify JSON round-trip preserves virtual threads
        String json = jsonMapper.writeValueAsString(dump);
        ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

        assertEquals(dump, deserializedDump, "Round-trip should preserve entire dump including virtual threads");
    }

    @Test
    void testReentrantLockScenarioDetailed() throws IOException {
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump("reentrant-lock",
                ThreadDumpGenerator.reentrantLockScenario());
        String content = Files.readString(dumpFile);
        ThreadDump dump = parser.parse(content);

        ThreadInfo lockHolder = findThreadByNameContains(dump, "LockHolder");
        ThreadInfo lockWaiter = findThreadByNameContains(dump, "LockWaiter");

        if (lockHolder != null && lockWaiter != null) {
            // Verify states
            assertNotNull(lockHolder.state());
            assertNotNull(lockWaiter.state());

            // Verify JSON round-trip preserves entire dump
            String json = jsonMapper.writeValueAsString(dump);
            ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

            assertEquals(dump, deserializedDump, "Round-trip should preserve entire dump including lock info");
        }
    }

    @Test
    void testComplexScenarioJsonStructure() throws IOException {
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump("complex",
                ThreadDumpGenerator.complexScenario());
        String content = Files.readString(dumpFile);
        ThreadDump dump = parser.parse(content);

        // Verify basic structure
        assertNotNull(dump.timestamp());
        assertNotNull(dump.threads());
        assertNotNull(dump.sourceType());
        assertTrue(dump.threads().size() >= 5, "Complex scenario should have at least 5 threads");

        // Verify each thread has valid structure
        for (ThreadInfo thread : dump.threads()) {
            assertNotNull(thread.name(), "Each thread must have a name");

            // Stack trace and locks should be non-null (can be empty)
            assertNotNull(thread.stackTrace());
            assertNotNull(thread.locks());
        }

        // Verify JSON round-trip maintains structure
        String json = jsonMapper.writeValueAsString(dump);
        ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

        assertEquals(dump, deserializedDump, "Round-trip should preserve entire complex dump");
    }

    @Test
    void testExpectedModelForSimpleThread() throws IOException {
        String threadDump = """
                "SimpleThread" #42 prio=5 cpu=100ms elapsed=5s tid=0x1000 nid=0x2000 runnable
                   java.lang.Thread.State: RUNNABLE
                   at com.example.Main.main(Main.java:10)
                   at com.example.Main.run(Main.java:5)
                """;

        ThreadDump dump = parser.parse(threadDump);

        // Build expected structure
        ThreadInfo expectedThread = new ThreadInfo(
                "SimpleThread",
                42L,
                0x2000L,
                5,
                null,  // daemon should be null when not explicitly set
                Thread.State.RUNNABLE,
                100L,
                5000L,
                List.of(
                        new me.bechberger.jthreaddump.model.StackFrame("com.example.Main", "main", "Main.java", 10),
                        new me.bechberger.jthreaddump.model.StackFrame("com.example.Main", "run", "Main.java", 5)
                ),
                List.of(),
                null,
                null
        );

        assertEquals(1, dump.threads().size());
        ThreadInfo actualThread = dump.threads().get(0);

        // Deep equality check
        assertEquals(expectedThread, actualThread, "Parsed thread should match expected structure");

        // Verify JSON round-trip
        String json = jsonMapper.writeValueAsString(dump);
        ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

        assertEquals(dump, deserializedDump, "Round-trip should preserve dump");
    }

    @Test
    void testExpectedModelWithLocks() throws IOException {
        String threadDump = """
                "LockedThread" #10 prio=5 tid=0x1000 nid=0x2000 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED
                   at com.example.Test.method(Test.java:20)
                   - waiting to lock <0xdeadbeef> (a java.lang.Object)
                   - locked <0xcafebabe> (a java.lang.String)
                """;

        ThreadDump dump = parser.parse(threadDump);

        // Build expected structure
        ThreadInfo expectedThread = new ThreadInfo(
                "LockedThread",
                10L,
                0x2000L,
                5,
                null,  // daemon should be null when not explicitly set
                Thread.State.BLOCKED,
                null,
                null,
                List.of(
                        new me.bechberger.jthreaddump.model.StackFrame("com.example.Test", "method", "Test.java", 20)
                ),
                List.of(
                        new me.bechberger.jthreaddump.model.LockInfo("0xdeadbeef", "java.lang.Object", "waiting on"),
                        new me.bechberger.jthreaddump.model.LockInfo("0xcafebabe", "java.lang.String", "locked")
                ),
                "0xdeadbeef",
                null
        );

        assertEquals(1, dump.threads().size());
        ThreadInfo actualThread = dump.threads().get(0);

        // Deep equality check
        assertEquals(expectedThread, actualThread, "Parsed thread with locks should match expected structure");

        // Verify JSON round-trip
        String json = jsonMapper.writeValueAsString(dump);
        ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

        assertEquals(dump, deserializedDump, "Round-trip should preserve dump with locks");
    }

    @Test
    void testJsonNullFieldsExcluded() throws IOException {
        String threadDump = """
                "MinimalThread" runnable
                """;

        ThreadDump dump = parser.parse(threadDump);
        String json = jsonMapper.writeValueAsString(dump);

        // Verify null fields are not present
        assertFalse(json.contains("\"threadId\""), "Null threadId should not be serialized");
        assertFalse(json.contains("\"nativeId\""), "Null nativeId should not be serialized");
        assertFalse(json.contains("\"priority\""), "Null priority should not be serialized");
        assertFalse(json.contains("\"cpuTimeMs\""), "Null cpuTimeMs should not be serialized");
    }

    @Test
    void testRoundTripJsonSerialization() throws IOException {
        String threadDump = """
                "TestThread" #1 daemon prio=10 cpu=250ms elapsed=2.5s tid=0xabc nid=0xdef runnable
                   java.lang.Thread.State: RUNNABLE
                   at java.lang.Thread.run(Thread.java:100)
                   - locked <0x123> (a java.lang.Object)
                """;

        ThreadDump originalDump = parser.parse(threadDump);

        // Serialize to JSON
        String json = jsonMapper.writeValueAsString(originalDump);

        // Deserialize back
        ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

        // Verify entire dump matches
        assertEquals(originalDump, deserializedDump, "Round-trip JSON serialization should preserve entire dump");
    }

    @Test
    void testVirtualThreadDeadlockScenario() throws IOException {
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump("virtual-thread-deadlock",
                ThreadDumpGenerator.virtualThreadDeadlockScenario());
        String content = Files.readString(dumpFile);
        ThreadDump dump = parser.parse(content);

        assertNotNull(dump);
        assertTrue(dump.threads().size() > 0, "Should have parsed some threads");

        // Look for deadlock information
        if (dump.deadlockInfos() != null && !dump.deadlockInfos().isEmpty()) {
            assertTrue(dump.deadlockInfos().size() >= 1, "Should detect deadlock");

            // Verify deadlock involves threads
            var deadlock = dump.deadlockInfos().get(0);
            assertTrue(deadlock.threads().size() >= 2, "Deadlock should involve at least 2 threads");

            // Check that deadlocked threads have stack traces and lock info
            for (var deadlockedThread : deadlock.threads()) {
                assertNotNull(deadlockedThread.threadName());
                assertNotNull(deadlockedThread.stackTrace());
                assertNotNull(deadlockedThread.locks());
            }
        }

        // Verify we can find virtual threads (either by name or as carriers)
        long virtualOrCarrierCount = dump.threads().stream()
                .filter(t -> t.name() != null &&
                        (t.name().contains("Virtual") || t.name().contains("ForkJoinPool")))
                .count();

        assertTrue(virtualOrCarrierCount > 0,
                "Should find virtual threads or their carrier threads");

        // Verify JSON serialization works
        String json = jsonMapper.writeValueAsString(dump);
        ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);
        assertNotNull(deserializedDump);
    }

    // Helper methods

    private ThreadInfo findThreadByNameContains(ThreadDump dump, String namePattern) {
        return dump.threads().stream()
                .filter(t -> t.name() != null && t.name().contains(namePattern))
                .findFirst()
                .orElse(null);
    }
}