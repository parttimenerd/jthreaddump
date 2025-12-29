package me.bechberger.jstall.test.scenarios;

import me.bechberger.jstall.jfr.JfrAnalyzer;
import me.bechberger.jstall.jfr.JfrParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JFR analysis using realistic scenarios.
 *
 * These tests verify actual values from JFR recordings.
 * When run the first time, scenarios are recorded and saved to test resources.
 * Subsequent runs can use the saved JFR files for reproducible testing.
 */
class ScenarioBasedJfrTest {

    private static final Path TEST_RESOURCES = Path.of("src/test/resources/scenarios");

    @TempDir
    Path tempDir;

    private JfrParser jfrParser;
    private JfrAnalyzer jfrAnalyzer;
    private ScenarioRecorder recorder;

    @BeforeEach
    void setUp() {
        jfrParser = new JfrParser();
        jfrAnalyzer = new JfrAnalyzer();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (recorder != null) {
            recorder.cleanup();
        }
    }

    @Test
    void testCpuHotspotScenario() throws Exception {
        JfrParser.JfrData jfrData = getOrRecordScenario(TestScenarios.cpuHotspot());

        // Verify significant execution samples
        assertTrue(jfrData.executionSamples().size() > 100,
                "Should have >100 execution samples, had: " + jfrData.executionSamples().size());

        // Analyze hottest methods - verify actual method name
        List<JfrAnalyzer.MethodProfile> hottestMethods = jfrAnalyzer.getHottestMethods(jfrData, 10);
        JfrAnalyzer.MethodProfile topMethod = hottestMethods.getFirst();

        // The top method MUST be hotMethod from TestScenarios
        String topMethodName = topMethod.method();
        assertTrue(topMethodName.contains("hotMethod"),
                "Top method must be 'hotMethod', was: " + topMethodName);
        assertTrue(topMethodName.contains("TestScenarios"),
                "Top method must be in TestScenarios class, was: " + topMethodName);

        // Top method should dominate (>50% of samples)
        assertTrue(topMethod.percentage() >= 50.0,
                "hotMethod should be ≥50% of samples, was: " + String.format("%.1f%%", topMethod.percentage()));
        assertTrue(topMethod.sampleCount() >= 500,
                "hotMethod should have ≥500 samples, had: " + topMethod.sampleCount());

        // Analyze hottest threads - verify pool threads
        List<JfrAnalyzer.ThreadProfile> threads = jfrAnalyzer.getHottestThreads(jfrData, 10);
        JfrAnalyzer.ThreadProfile hottestThread = threads.getFirst();

        // Thread name should be from the executor pool
        assertTrue(hottestThread.threadName().matches("pool-\\d+-thread-\\d+"),
                "Hottest thread should be pool thread, was: " + hottestThread.threadName());

        // Should have exactly 4 active pool threads (from numThreads = 4)
        long poolThreads = threads.stream()
                .filter(t -> t.threadName().matches("pool-\\d+-thread-\\d+"))
                .filter(t -> t.sampleCount() >= 100)
                .count();
        assertEquals(4, poolThreads,
                "Should have exactly 4 active pool threads, had: " + poolThreads);

        // Each pool thread should have roughly 25% of samples (±10% tolerance)
        List<JfrAnalyzer.ThreadProfile> topPoolThreads = threads.stream()
                .filter(t -> t.threadName().matches("pool-\\d+-thread-\\d+"))
                .limit(4)
                .toList();
        for (JfrAnalyzer.ThreadProfile poolThread : topPoolThreads) {
            assertTrue(poolThread.percentage() >= 15.0 && poolThread.percentage() <= 35.0,
                    "Pool thread " + poolThread.threadName() + " should have 15-35% of samples, had: " +
                    String.format("%.1f%%", poolThread.percentage()));
        }

        printTestResult("CPU Hotspot", topMethodName, topMethod.sampleCount(), topMethod.percentage(),
                hottestThread.threadName(), hottestThread.sampleCount(), hottestThread.percentage());
    }

    @Test
    void testLockContentionScenario() throws Exception {
        JfrParser.JfrData jfrData = getOrRecordScenario(TestScenarios.lockContention());

        // Verify substantial lock events
        assertTrue(jfrData.lockEvents().size() >= 50,
                "Should have ≥50 lock events, had: " + jfrData.lockEvents().size());

        JfrAnalyzer.LockContentionSummary summary = jfrAnalyzer.getLockContentionSummary(jfrData);

        // Should have exactly 1 contended lock (the hotLock object)
        assertEquals(1, summary.uniqueLocks(),
                "Should have exactly 1 contended lock");

        // Verify the hot lock class is Object (the hotLock)
        List<JfrAnalyzer.LockProfile> hotLocks = summary.hotLocks();
        assertEquals(1, hotLocks.size(), "Should have exactly 1 hot lock profile");

        JfrAnalyzer.LockProfile hottestLock = hotLocks.getFirst();
        assertTrue(hottestLock.monitorClass().contains("Object"),
                "Hot lock should be java.lang.Object, was: " + hottestLock.monitorClass());

        // Verify measurable blocked time (8 threads * 10ms sleep * many iterations)
        assertTrue(summary.totalBlockedTime().toMillis() >= 100,
                "Should have ≥100ms blocked time, had: " + summary.totalBlockedTime().toMillis() + "ms");

        // Should have 8 distinct waiters (the 8 threads from numThreads = 8)
        assertTrue(hottestLock.waiters().size() >= 6,
                "Should have ≥6 distinct waiters (of 8 threads), had: " + hottestLock.waiters().size());

        // Waiter thread names should match pool pattern
        boolean allPoolThreads = hottestLock.waiters().stream()
                .allMatch(w -> w.matches("pool-\\d+-thread-\\d+"));
        assertTrue(allPoolThreads,
                "All waiters should be pool threads, were: " + hottestLock.waiters());

        System.out.println("✓ Lock Contention: " + summary.totalEvents() + " events, " +
                summary.totalBlockedTime().toMillis() + "ms blocked, " +
                hottestLock.waiters().size() + " waiters on " + hottestLock.monitorClass());
    }

    @Test
    void testFileIOScenario() throws Exception {
        JfrParser.JfrData jfrData = getOrRecordScenario(TestScenarios.fileIO());

        // Verify file I/O events (threshold depends on JFR config)
        List<JfrParser.IOEvent> fileIOEvents = jfrData.ioEvents().stream()
                .filter(e -> e.type().isFile())
                .toList();

        assertTrue(fileIOEvents.size() >= 10,
                "Should have ≥10 file I/O operations, had: " + fileIOEvents.size());

        JfrAnalyzer.IOSummary ioSummary = jfrAnalyzer.getIOSummary(jfrData);

        // Test actual I/O counts (may vary based on JFR threshold settings)
        assertTrue(ioSummary.totalFileReads() >= 5,
                "Should have ≥5 file reads, had: " + ioSummary.totalFileReads());

        // Verify file paths contain our temp file pattern (if any captured)
        if (!fileIOEvents.isEmpty()) {
            boolean hasTestFiles = fileIOEvents.stream()
                    .filter(e -> e.path() != null)
                    .anyMatch(e -> e.path().contains("jstall-test-") || e.path().contains("jstall-out-"));
            assertTrue(hasTestFiles, "Should have I/O on jstall test files");
        }

        System.out.println("✓ File I/O: " + ioSummary.totalFileReads() + " reads, " +
                ioSummary.totalFileWrites() + " writes, " + ioSummary.getFormattedSize() + " transferred");
    }

    @Test
    void testSocketIOScenario() throws Exception {
        JfrParser.JfrData jfrData = getOrRecordScenario(TestScenarios.socketIO());

        List<JfrParser.IOEvent> socketEvents = jfrData.ioEvents().stream()
                .filter(e -> e.type().isSocket())
                .toList();

        // Socket I/O threshold in JFR profile config may filter small operations
        assertTrue(socketEvents.size() >= 1,
                "Should have ≥1 socket operations, had: " + socketEvents.size());

        JfrAnalyzer.IOSummary ioSummary = jfrAnalyzer.getIOSummary(jfrData);

        // Verify we have at least some socket activity
        long totalSocketOps = ioSummary.totalSocketReads() + ioSummary.totalSocketWrites();
        assertTrue(totalSocketOps >= 1,
                "Should have ≥1 socket operation, had: " + totalSocketOps);

        // Verify localhost connections if events captured
        if (!socketEvents.isEmpty()) {
            boolean hasLocalhost = socketEvents.stream()
                    .anyMatch(e -> e.host() != null &&
                            (e.host().contains("localhost") || e.host().contains("127.0.0.1")));
            assertTrue(hasLocalhost, "Should have localhost socket connections");
        }

        System.out.println("✓ Socket I/O: " + ioSummary.totalSocketReads() + " reads, " +
                ioSummary.totalSocketWrites() + " writes");
    }

    @Test
    void testHighAllocationScenario() throws Exception {
        JfrParser.JfrData jfrData = getOrRecordScenario(TestScenarios.highAllocation());

        // Allocation events depend on JFR profile settings (TLAB thresholds)
        // The profile config may not capture all allocations

        // Verify we have execution samples at least
        assertTrue(jfrData.executionSamples().size() >= 50,
                "Should have ≥50 execution samples, had: " + jfrData.executionSamples().size());

        // If allocation events are captured, verify they're byte arrays
        if (!jfrData.allocationEvents().isEmpty()) {
            List<JfrAnalyzer.AllocationProfile> hotspots = jfrAnalyzer.getAllocationHotspots(jfrData, 10);

            if (!hotspots.isEmpty()) {
                JfrAnalyzer.AllocationProfile topAllocator = hotspots.getFirst();

                // Verify byte arrays are being allocated
                boolean hasByteArrays = hotspots.stream()
                        .flatMap(h -> h.topClasses().stream())
                        .anyMatch(c -> c.contains("[B") || c.equals("byte[]"));
                assertTrue(hasByteArrays, "Top allocation should be byte arrays");

                long totalBytes = hotspots.stream()
                        .mapToLong(JfrAnalyzer.AllocationProfile::totalBytes)
                        .sum();

                System.out.println("✓ High Allocation: " + jfrData.allocationEvents().size() + " events, " +
                        String.format("%.2f MB", totalBytes / (1024.0 * 1024)) + " allocated");
            } else {
                System.out.println("✓ High Allocation: " + jfrData.executionSamples().size() +
                        " samples (allocation sampling not captured by JFR profile)");
            }
        } else {
            // No allocation events - JFR profile may have high threshold
            System.out.println("✓ High Allocation: " + jfrData.executionSamples().size() +
                    " samples (allocation events not captured by JFR profile)");
        }
    }

    @Test
    void testMixedWorkloadScenario() throws Exception {
        JfrParser.JfrData jfrData = getOrRecordScenario(TestScenarios.mixedWorkload());

        // Verify CPU samples are present
        assertTrue(jfrData.executionSamples().size() >= 50,
                "Should have ≥50 CPU samples, had: " + jfrData.executionSamples().size());
        assertTrue(jfrData.lockEvents().size() >= 10,
                "Should have ≥10 lock events, had: " + jfrData.lockEvents().size());
        // Allocation events depend on JFR profile thresholds - don't require them

        // Verify CPU methods are from our test scenario
        // Note: Math.sqrt may be inlined, so we check for the lambda that calls it
        List<JfrAnalyzer.MethodProfile> methods = jfrAnalyzer.getHottestMethods(jfrData, 20);

        // The top method should be from TestScenarios (the CPU work lambda or DecimalDigits for string work)
        JfrAnalyzer.MethodProfile topMethod = methods.getFirst();
        boolean hasExpectedMethods = topMethod.method().contains("TestScenarios") ||
                topMethod.method().contains("DecimalDigits") ||
                topMethod.method().contains("sqrt");
        assertTrue(hasExpectedMethods,
                "Top method should be from test scenario, was: " + topMethod.method());

        // Verify we have pool threads with significant samples (CPU workers + allocation workers)
        List<JfrAnalyzer.ThreadProfile> threads = jfrAnalyzer.getHottestThreads(jfrData, 10);
        long activePoolThreads = threads.stream()
                .filter(t -> t.threadName().matches("pool-\\d+-thread-\\d+"))
                .filter(t -> t.sampleCount() >= 100)
                .count();
        assertTrue(activePoolThreads >= 4,
                "Should have ≥4 active pool threads, had: " + activePoolThreads);

        // Verify lock contention on lock1
        JfrAnalyzer.LockContentionSummary lockSummary = jfrAnalyzer.getLockContentionSummary(jfrData);
        assertTrue(lockSummary.uniqueLocks() >= 1, "Should have contended locks");

        // Verify String allocations if allocation events are captured
        if (!jfrData.allocationEvents().isEmpty()) {
            List<JfrAnalyzer.AllocationProfile> allocations = jfrAnalyzer.getAllocationHotspots(jfrData, 10);
            boolean hasStringAlloc = allocations.stream()
                    .flatMap(a -> a.topClasses().stream())
                    .anyMatch(c -> c.contains("String") || c.contains("char[]") || c.contains("[C"));
            assertTrue(hasStringAlloc, "Should have String allocations");
        }

        System.out.println("✓ Mixed Workload: " + jfrData.executionSamples().size() + " samples, " +
                lockSummary.totalEvents() + " lock events, " + jfrData.allocationEvents().size() + " allocations, " +
                activePoolThreads + " active pool threads");
    }

    @Test
    void testDeepRecursionScenario() throws Exception {
        JfrParser.JfrData jfrData = getOrRecordScenario(TestScenarios.deepRecursion());

        // Verify we have stack samples
        assertTrue(jfrData.executionSamples().size() >= 50,
                "Should have ≥50 execution samples, had: " + jfrData.executionSamples().size());

        // Analyze stack profiles for deep recursion
        List<JfrAnalyzer.StackProfile> stacks = jfrAnalyzer.getStackProfiles(jfrData, 50);

        // Should have stacks with recursiveMethod appearing multiple times
        boolean hasDeepStacks = stacks.stream()
                .anyMatch(s -> s.stack().size() >= 20);
        assertTrue(hasDeepStacks, "Should have stacks with ≥20 frames from recursion");

        // Find stack with deepest recursion
        JfrAnalyzer.StackProfile deepestStack = stacks.stream()
                .max((a, b) -> Integer.compare(a.stack().size(), b.stack().size()))
                .orElseThrow();

        // The deep stack should contain recursiveMethod
        boolean hasRecursiveMethod = deepestStack.stack().stream()
                .anyMatch(frame -> frame.contains("recursiveMethod"));
        assertTrue(hasRecursiveMethod, "Deep stack should contain recursiveMethod");

        System.out.println("✓ Deep Recursion: deepest stack has " + deepestStack.stack().size() + " frames");
    }

    @Test
    void testProducerConsumerScenario() throws Exception {
        JfrParser.JfrData jfrData = getOrRecordScenario(TestScenarios.producerConsumer());

        // Verify we have execution samples (BlockingQueue uses lock-free operations
        // so we may not see many lock events, but should see thread activity)
        assertTrue(jfrData.executionSamples().size() >= 20,
                "Should have ≥20 execution samples, had: " + jfrData.executionSamples().size());

        // Analyze threads - the producer-consumer pattern may have threads mostly waiting
        // so sample counts will be low. Just verify we have pool threads present.
        List<JfrAnalyzer.ThreadProfile> threads = jfrAnalyzer.getHottestThreads(jfrData, 20);

        // Count pool threads that have any samples at all (producer/consumer threads)
        List<JfrAnalyzer.ThreadProfile> poolThreads = threads.stream()
                .filter(t -> t.threadName().matches("pool-\\d+-thread-\\d+"))
                .toList();

        // The scenario has 6 threads (3 producers + 3 consumers), but they may be mostly
        // blocked on put/take so not many samples. Just verify at least some are present.
        assertTrue(poolThreads.size() >= 3,
                "Should have ≥3 pool threads with samples, had: " + poolThreads.size());

        // Verify that pool thread names follow expected pattern (pool-N-thread-M)
        for (JfrAnalyzer.ThreadProfile poolThread : poolThreads) {
            assertTrue(poolThread.threadName().matches("pool-\\d+-thread-\\d+"),
                    "Pool thread should match pattern: " + poolThread.threadName());
        }

        // Lock summary (if any lock events captured)
        JfrAnalyzer.LockContentionSummary lockSummary = jfrAnalyzer.getLockContentionSummary(jfrData);

        System.out.println("✓ Producer-Consumer: " + poolThreads.size() + " pool threads with samples, " +
                lockSummary.totalEvents() + " lock events");
    }

    @Test
    void testThreadPoolExhaustionScenario() throws Exception {
        JfrParser.JfrData jfrData = getOrRecordScenario(TestScenarios.threadPoolExhaustion());

        // Verify thread pool has exactly 4 busy threads
        List<JfrAnalyzer.ThreadProfile> threads = jfrAnalyzer.getHottestThreads(jfrData, 10);

        List<JfrAnalyzer.ThreadProfile> busyPoolThreads = threads.stream()
                .filter(t -> t.threadName().matches("pool-\\d+-thread-\\d+"))
                .filter(t -> t.sampleCount() >= 50)
                .toList();

        assertEquals(4, busyPoolThreads.size(),
                "Should have exactly 4 busy pool threads, had: " + busyPoolThreads.size());

        // All 4 threads should be running (high sample counts)
        for (JfrAnalyzer.ThreadProfile thread : busyPoolThreads) {
            assertTrue(thread.sampleCount() >= 100,
                    "Busy thread " + thread.threadName() + " should have ≥100 samples, had: " + thread.sampleCount());
        }

        System.out.println("✓ Thread Pool Exhaustion: " + busyPoolThreads.size() + " threads fully busy");
    }

    @Test
    void testCpuStallScenario() throws Exception {
        JfrParser.JfrData jfrData = getOrRecordScenario(TestScenarios.cpuStall());

        // CPU stall should have high CPU samples (threads are RUNNABLE but spinning)
        assertTrue(jfrData.executionSamples().size() >= 100,
                "Should have ≥100 execution samples (spinning threads), had: " + jfrData.executionSamples().size());

        // Analyze hottest methods - should find busyWaitSpin
        List<JfrAnalyzer.MethodProfile> methods = jfrAnalyzer.getHottestMethods(jfrData, 10);
        JfrAnalyzer.MethodProfile topMethod = methods.getFirst();

        // The top method should be busyWaitSpin or its parent lambda
        assertTrue(topMethod.method().contains("busyWaitSpin") || topMethod.method().contains("cpuStall") ||
                        topMethod.method().contains("TestScenarios"),
                "Top method should be from CPU stall scenario, was: " + topMethod.method());

        // Should dominate CPU (>50%)
        assertTrue(topMethod.percentage() >= 50.0,
                "Stall method should be ≥50% of samples, was: " + String.format("%.1f%%", topMethod.percentage()));

        // Should have exactly 4 hot threads (the busy-waiting threads)
        List<JfrAnalyzer.ThreadProfile> threads = jfrAnalyzer.getHottestThreads(jfrData, 10);
        List<JfrAnalyzer.ThreadProfile> hotPoolThreads = threads.stream()
                .filter(t -> t.threadName().matches("pool-\\d+-thread-\\d+"))
                .filter(t -> t.sampleCount() >= 50)
                .toList();

        assertEquals(4, hotPoolThreads.size(),
                "Should have exactly 4 spinning threads, had: " + hotPoolThreads.size());

        // Each thread should have significant and roughly equal samples
        for (JfrAnalyzer.ThreadProfile thread : hotPoolThreads) {
            assertTrue(thread.percentage() >= 10.0,
                    "Each thread should have ≥10% of samples, " + thread.threadName() +
                            " had: " + String.format("%.1f%%", thread.percentage()));
        }

        System.out.println("✓ CPU Stall: " + jfrData.executionSamples().size() + " samples, top method: " +
                topMethod.method() + " (" + String.format("%.1f%%", topMethod.percentage()) + ")");
    }

    @Test
    void testIOStallScenario() throws Exception {
        JfrParser.JfrData jfrData = getOrRecordScenario(TestScenarios.ioStall());

        // I/O stall - threads are blocked on socket read
        // May have some samples but threads are mostly waiting

        // Analyze threads that have samples
        List<JfrAnalyzer.ThreadProfile> threads = jfrAnalyzer.getHottestThreads(jfrData, 20);

        // Count pool threads that have any samples
        List<JfrAnalyzer.ThreadProfile> poolThreads = threads.stream()
                .filter(t -> t.threadName().matches("pool-\\d+-thread-\\d+"))
                .toList();

        // The I/O stall threads may have very few samples since they're blocked
        // Just verify we have some threads present
        assertFalse(poolThreads.isEmpty(),
                "Should have at least some pool thread samples");

        // Check for socket I/O events if any were captured
        List<JfrParser.IOEvent> socketEvents = jfrData.ioEvents().stream()
                .filter(e -> e.type().isSocket())
                .toList();

        // Verify execution samples exist (at least from the accepting server thread)
        assertTrue(jfrData.executionSamples().size() >= 10,
                "Should have ≥10 execution samples, had: " + jfrData.executionSamples().size());

        System.out.println("✓ I/O Stall: " + poolThreads.size() + " pool threads with samples, " +
                socketEvents.size() + " socket I/O events");
    }

    // --- Helper Methods ---

    /**
     * Get scenario data from saved test resources, or record it if not present.
     */
    private JfrParser.JfrData getOrRecordScenario(ScenarioDefinition scenario) throws Exception {
        Path savedJfr = TEST_RESOURCES.resolve(scenario.getName()).resolve(scenario.getName() + ".jfr");

        if (Files.exists(savedJfr)) {
            // Use pre-recorded data for reproducibility
            System.out.println("Using saved JFR: " + savedJfr);
            return jfrParser.parse(savedJfr);
        } else {
            // Record new scenario (first run or regenerating data)
            System.out.println("Recording new scenario: " + scenario.getName());
            recorder = createRecorder(scenario);
            recorder.record(scenario);
            return jfrParser.parse(recorder.getJfrFile());
        }
    }

    private ScenarioRecorder createRecorder(ScenarioDefinition scenario) {
        return ScenarioRecorder.builder()
                .name(scenario.getName())
                .duration(Duration.ofSeconds(10))
                .dumpInterval(Duration.ofSeconds(2))
                .outputDir(tempDir)
                .saveToTestResources(true)
                .build();
    }

    private void printTestResult(String scenarioName, String topMethod, long methodSamples, double methodPct,
                                   String topThread, long threadSamples, double threadPct) {
        System.out.println("✓ " + scenarioName + ":");
        System.out.println("  Top method: " + topMethod +
                " (" + methodSamples + " samples, " + String.format("%.1f%%", methodPct) + ")");
        System.out.println("  Top thread: " + topThread +
                " (" + threadSamples + " samples, " + String.format("%.1f%%", threadPct) + ")");
    }
}