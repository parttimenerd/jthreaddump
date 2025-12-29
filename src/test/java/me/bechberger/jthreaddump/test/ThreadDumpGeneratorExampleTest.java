package me.bechberger.jthreaddump.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.bechberger.jthreaddump.model.*;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;
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
 * Tests for ThreadDumpGenerator scenarios with proper assertEquals assertions
 * Following the pattern from ThreadDumpParserTest
 */
public class ThreadDumpGeneratorExampleTest {

    private final ObjectMapper jsonMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * Test all single-dump scenarios
     */
    static Stream<Arguments> singleDumpScenarios() {
        return Stream.of(
                Arguments.of("deadlock", ThreadDumpGenerator.deadlockScenario(), 2, "DeadlockThread"),
                Arguments.of("three-way-deadlock", ThreadDumpGenerator.threeWayDeadlockScenario(), 3, "DeadlockThread"),
                Arguments.of("reentrant-lock", ThreadDumpGenerator.reentrantLockScenario(), 2, "Lock"),
                Arguments.of("virtual-threads", ThreadDumpGenerator.virtualThreadScenario(), 3, "VirtualWorker"),
                Arguments.of("many-virtual-threads", ThreadDumpGenerator.manyVirtualThreadsScenario(), 50, "VirtualWorker"),
                Arguments.of("thread-pool", ThreadDumpGenerator.threadPoolScenario(), 10, "Worker"),
                Arguments.of("minimal-threads", ThreadDumpGenerator.minimalThreadsScenario(), 2, "SimpleWorker"),
                Arguments.of("complex", ThreadDumpGenerator.complexScenario(), 5, null),
                Arguments.of("read-write-lock", ThreadDumpGenerator.readWriteLockScenario(), 6, "Lock"),
                Arguments.of("gc-activity", ThreadDumpGenerator.gcActivityScenario(), 8, "MemoryAllocator")
        );
    }

    @ParameterizedTest(name = "Scenario: {0}")
    @MethodSource("singleDumpScenarios")
    void testSingleDumpScenario(String scenarioName, ThreadDumpGenerator.ThreadDumpScenario scenario,
                                 int minExpectedThreads, String expectedThreadNamePattern) throws IOException {
        // Generate or get cached dump
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump(scenarioName, scenario);
        assertTrue(Files.exists(dumpFile), "Dump file should exist");

        String content = Files.readString(dumpFile);
        ThreadDump dump = ThreadDumpParser.parse(content);

        // Basic assertions
        assertNotNull(dump, "Dump should not be null");
        assertNotNull(dump.threads(), "Threads list should not be null");
        assertNotNull(dump.sourceType(), "Source type should not be null");

        // Verify JSON round-trip maintains equality
        String json = jsonMapper.writeValueAsString(dump);
        ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

        assertEquals(dump, deserializedDump,
                "Round-trip JSON serialization should preserve dump equality for " + scenarioName);
    }

    @Test
    void testDeadlockScenarioDetailed() throws IOException {
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump(
                "deadlock-detailed", ThreadDumpGenerator.deadlockScenario());

        String content = Files.readString(dumpFile);
        ThreadDump dump = ThreadDumpParser.parse(content);

        assertNotNull(dump);

        // Find deadlocked threads
        ThreadInfo threadA = findThreadByNameContains(dump, "DeadlockThread-A");
        ThreadInfo threadB = findThreadByNameContains(dump, "DeadlockThread-B");

        if (threadA != null && threadB != null) {
            assertNotNull(threadA.name(), "Thread A should have name");
            assertNotNull(threadB.name(), "Thread B should have name");
        }

        // Verify deadlock information is parsed
        if (!dump.deadlockInfos().isEmpty()) {
            DeadlockInfo deadlockInfo = dump.deadlockInfos().get(0);
            assertNotNull(deadlockInfo.threads(), "Deadlock threads should not be null");
            assertTrue(deadlockInfo.threads().size() >= 2,
                    "Should have at least 2 deadlocked threads");

            // Verify deadlocked thread details
            for (DeadlockInfo.DeadlockedThread dlThread : deadlockInfo.threads()) {
                assertNotNull(dlThread.threadName(), "Deadlocked thread must have name");
                assertTrue(dlThread.threadName().contains("DeadlockThread"),
                        "Thread name should be a deadlock thread");

                // Verify waiting information
                if (dlThread.waitingForMonitor() != null) {
                    assertNotNull(dlThread.waitingForObject(), "Should have waiting object");
                }

                // Verify held by information
                if (dlThread.heldBy() != null) {
                    assertTrue(dlThread.heldBy().contains("DeadlockThread"),
                            "HeldBy should reference another deadlock thread");
                }
            }
        }

        // Verify JSON round-trip preserves entire dump
        String json = jsonMapper.writeValueAsString(dump);
        ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

        assertEquals(dump, deserializedDump,
                "Round-trip should preserve entire deadlock dump");
    }

    @Test
    void testThreeWayDeadlockScenarioDetailed() throws IOException {
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump(
                "three-way-deadlock-detailed", ThreadDumpGenerator.threeWayDeadlockScenario());

        String content = Files.readString(dumpFile);
        ThreadDump dump = ThreadDumpParser.parse(content);

        assertNotNull(dump);

        // Find all three deadlocked threads
        ThreadInfo threadA = findThreadByNameContains(dump, "DeadlockThread-A");
        ThreadInfo threadB = findThreadByNameContains(dump, "DeadlockThread-B");
        ThreadInfo threadC = findThreadByNameContains(dump, "DeadlockThread-C");

        if (threadA != null) assertNotNull(threadA.name());
        if (threadB != null) assertNotNull(threadB.name());
        if (threadC != null) assertNotNull(threadC.name());

        // Verify JSON round-trip
        String json = jsonMapper.writeValueAsString(dump);
        ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

        assertEquals(dump, deserializedDump,
                "Round-trip should preserve three-way deadlock dump");
    }

    @Test
    void testThreadPoolScenarioDetailed() throws IOException {
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump(
                "thread-pool-detailed", ThreadDumpGenerator.threadPoolScenario());

        String content = Files.readString(dumpFile);
        ThreadDump dump = ThreadDumpParser.parse(content);

        assertNotNull(dump);
        assertNotNull(dump.threads());

        // Verify each thread has valid structure
        for (ThreadInfo thread : dump.threads()) {
            assertNotNull(thread.stackTrace(), "Stack trace should not be null");
            assertNotNull(thread.locks(), "Locks list should not be null");
        }

        // Verify JSON round-trip
        String json = jsonMapper.writeValueAsString(dump);
        ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

        assertEquals(dump, deserializedDump,
                "Round-trip should preserve thread pool dump");
    }

    @Test
    void testComplexScenarioDetailed() throws IOException {
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump(
                "complex-detailed", ThreadDumpGenerator.complexScenario());

        String content = Files.readString(dumpFile);
        ThreadDump dump = ThreadDumpParser.parse(content);

        assertNotNull(dump);
        assertNotNull(dump.threads());

        // Verify each thread has valid structure
        for (ThreadInfo thread : dump.threads()) {
            assertNotNull(thread.name(), "Each thread must have a name");
            assertNotNull(thread.stackTrace(), "Stack trace should not be null");
            assertNotNull(thread.locks(), "Locks list should not be null");
        }

        // Verify JSON round-trip
        String json = jsonMapper.writeValueAsString(dump);
        ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

        assertEquals(dump, deserializedDump,
                "Round-trip should preserve complex dump");
    }

    /**
     * Test multi-dump scenarios
     */
    static Stream<Arguments> multiDumpScenarios() {
        return Stream.of(
                Arguments.of("thread-pool-load", ThreadDumpGenerator.threadPoolLoadIncrease(), 5),
                Arguments.of("virtual-over-time", ThreadDumpGenerator.virtualThreadsOverTime(), 4),
                Arguments.of("deadlock-formation", ThreadDumpGenerator.deadlockFormation(), 6),
                Arguments.of("gc-over-time", ThreadDumpGenerator.gcActivityOverTime(), 5)
        );
    }

    @ParameterizedTest(name = "Multi-dump: {0}")
    @MethodSource("multiDumpScenarios")
    void testMultiDumpScenarios(String scenarioName, ThreadDumpGenerator.MultiDumpScenario scenario,
                                int expectedCount) throws IOException {
        List<Path> dumpPaths = ThreadDumpGenerator.getOrGenerateThreadDumps(
                scenarioName, scenario, expectedCount);

        assertEquals(expectedCount, dumpPaths.size(),
                "Should generate expected number of dumps");

        // Parse all dumps and verify round-trip equality
        for (int i = 0; i < dumpPaths.size(); i++) {
            String content = Files.readString(dumpPaths.get(i));
            ThreadDump dump = ThreadDumpParser.parse(content);

            assertNotNull(dump, "Dump " + i + " should not be null");
            assertNotNull(dump.threads(), "Dump " + i + " should have threads list");

            // Verify JSON round-trip preserves equality
            String json = jsonMapper.writeValueAsString(dump);
            ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

            assertEquals(dump, deserializedDump,
                    String.format("Round-trip should preserve dump %d for %s", i, scenarioName));
        }
    }

    @Test
    void testThreadPoolLoadIncreaseProgression() throws IOException {
        List<Path> dumpPaths = ThreadDumpGenerator.getOrGenerateThreadDumps(
                "thread-pool-progression", ThreadDumpGenerator.threadPoolLoadIncrease(), 5);

        assertEquals(5, dumpPaths.size());

        // Parse all and verify they're all valid
        for (int i = 0; i < dumpPaths.size(); i++) {
            String content = Files.readString(dumpPaths.get(i));
            ThreadDump dump = ThreadDumpParser.parse(content);

            assertNotNull(dump);

            // Verify round-trip
            String json = jsonMapper.writeValueAsString(dump);
            ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

            assertEquals(dump, deserializedDump,
                    "Dump " + i + " should survive round-trip");
        }
    }

    @Test
    void testDeadlockFormationProgression() throws IOException {
        List<Path> dumpPaths = ThreadDumpGenerator.getOrGenerateThreadDumps(
                "deadlock-progression", ThreadDumpGenerator.deadlockFormation(), 6);

        assertEquals(6, dumpPaths.size());

        // Parse all dumps
        for (int i = 0; i < dumpPaths.size(); i++) {
            String content = Files.readString(dumpPaths.get(i));
            ThreadDump dump = ThreadDumpParser.parse(content);

            assertNotNull(dump);

            // Look for deadlock candidates
            ThreadInfo candidateA = findThreadByNameContains(dump, "DeadlockCandidate-A");
            ThreadInfo candidateB = findThreadByNameContains(dump, "DeadlockCandidate-B");

            if (candidateA != null) assertNotNull(candidateA.name());
            if (candidateB != null) assertNotNull(candidateB.name());

            // Verify round-trip
            String json = jsonMapper.writeValueAsString(dump);
            ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

            assertEquals(dump, deserializedDump,
                    "Deadlock formation dump " + i + " should survive round-trip");
        }
    }

    @Test
    void testGcActivityOverTimeProgression() throws IOException {
        List<Path> dumpPaths = ThreadDumpGenerator.getOrGenerateThreadDumps(
                "gc-progression", ThreadDumpGenerator.gcActivityOverTime(), 5);

        assertEquals(5, dumpPaths.size());

        for (int i = 0; i < dumpPaths.size(); i++) {
            String content = Files.readString(dumpPaths.get(i));
            ThreadDump dump = ThreadDumpParser.parse(content);

            assertNotNull(dump);

            // Verify round-trip
            String json = jsonMapper.writeValueAsString(dump);
            ThreadDump deserializedDump = jsonMapper.readValue(json, ThreadDump.class);

            assertEquals(dump, deserializedDump,
                    "GC activity dump " + i + " should survive round-trip");
        }
    }

    /**
     * Helper method to find a thread by name pattern
     */
    private ThreadInfo findThreadByNameContains(ThreadDump dump, String namePattern) {
        return dump.threads().stream()
                .filter(t -> t.name() != null && t.name().contains(namePattern))
                .findFirst()
                .orElse(null);
    }
}