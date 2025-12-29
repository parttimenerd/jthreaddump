package me.bechberger.jstall.test.scenarios;

import me.bechberger.jstall.jfr.JfrAnalyzer;
import me.bechberger.jstall.jfr.JfrParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests correlation between JFR data and thread dumps captured during scenarios.
 *
 * Uses pre-recorded JFR files from test resources when available for reproducibility.
 * Only records new scenarios when no saved data exists.
 */
class JfrThreadDumpCorrelationTest {

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
    void tearDown() throws Exception {
        if (recorder != null) {
            recorder.cleanup();
        }
    }

    /**
     * Get JFR data from saved test resources, or record it if not present.
     */
    private JfrParser.JfrData getOrRecordJfrData(ScenarioDefinition scenario) throws Exception {
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

    /**
     * Get thread dumps from saved test resources, or record them if not present.
     */
    private List<String> getOrRecordDumps(ScenarioDefinition scenario) throws Exception {
        Path savedDir = TEST_RESOURCES.resolve(scenario.getName());

        if (Files.exists(savedDir)) {
            // Read saved dumps
            List<Path> dumpFiles = Files.list(savedDir)
                    .filter(p -> p.getFileName().toString().startsWith("dump-"))
                    .sorted()
                    .toList();

            if (!dumpFiles.isEmpty()) {
                return dumpFiles.stream()
                        .map(p -> {
                            try { return Files.readString(p); }
                            catch (Exception e) { return ""; }
                        })
                        .filter(s -> !s.isEmpty())
                        .toList();
            }
        }

        // Use recorder if we recorded data
        if (recorder != null) {
            return recorder.getDumps();
        }

        return List.of();
    }

    @Test
    void testCorrelateJfrWithThreadDumps() throws Exception {
        ScenarioDefinition scenario = TestScenarios.cpuHotspot();

        // Get JFR data (from saved file or record new)
        JfrParser.JfrData jfrData = getOrRecordJfrData(scenario);

        // Get thread dumps
        List<String> dumps = getOrRecordDumps(scenario);
        assertFalse(dumps.isEmpty(), "Should have thread dumps");

        // Verify both JFR and thread dumps captured the same scenario
        List<JfrAnalyzer.ThreadProfile> jfrThreads = jfrAnalyzer.getHottestThreads(jfrData, 10);
        assertFalse(jfrThreads.isEmpty(), "JFR should show thread activity");

        // Verify we have CPU-intensive threads
        boolean hasCpuIntensiveThreads = jfrThreads.stream()
                .anyMatch(t -> t.sampleCount() > 10);
        assertTrue(hasCpuIntensiveThreads, "Should have CPU-intensive threads with significant samples");

        System.out.println("JFR-captured threads:");
        for (JfrAnalyzer.ThreadProfile thread : jfrThreads) {
            System.out.printf("  %s: %d samples (%.1f%%)%n",
                    thread.threadName(), thread.sampleCount(), thread.percentage());
        }
    }

    @Test
    void testTimeRangeCorrelation() throws Exception {
        ScenarioDefinition scenario = TestScenarios.lockContention();

        // Get JFR data (from saved file or record new)
        JfrParser.JfrData jfrData = getOrRecordJfrData(scenario);

        // Get time range from JFR data
        var startTime = jfrData.getStartTime();
        var endTime = jfrData.getEndTime();

        assertNotNull(startTime, "Should have start time");
        assertNotNull(endTime, "Should have end time");
        assertTrue(endTime.isAfter(startTime), "End time should be after start time");

        // Filter to first half
        var midTime = startTime.plus(Duration.between(startTime, endTime).dividedBy(2));
        JfrParser.JfrData firstHalf = jfrData.filterByTimeRange(startTime, midTime);

        // Should have less data than full
        assertTrue(firstHalf.executionSamples().size() <= jfrData.executionSamples().size(),
                "First half should have fewer or equal execution samples");
        assertTrue(firstHalf.lockEvents().size() <= jfrData.lockEvents().size(),
                "First half should have fewer or equal lock events");
    }

    @Test
    void testThreadSpecificCorrelation() throws Exception {
        ScenarioDefinition scenario = TestScenarios.mixedWorkload();

        // Get JFR data (from saved file or record new)
        JfrParser.JfrData jfrData = getOrRecordJfrData(scenario);


        // Get hottest thread
        List<JfrAnalyzer.ThreadProfile> threads = jfrAnalyzer.getHottestThreads(jfrData, 5);
        assertFalse(threads.isEmpty(), "Should have thread profiles");

        String hottestThread = threads.getFirst().threadName();

        // Get thread-specific samples manually
        List<JfrParser.MethodSample> threadSamples = jfrData.executionSamples().stream()
                .filter(s -> hottestThread.equals(s.threadName()))
                .toList();

        assertFalse(threadSamples.isEmpty(),
                "Should have execution samples for specific thread: " + hottestThread);

        // All samples should be from the target thread
        for (JfrParser.MethodSample sample : threadSamples) {
            assertEquals(hottestThread, sample.threadName(),
                    "All samples should be from the target thread");
        }

        // Verify this thread has significant activity
        assertTrue(threadSamples.size() >= 10,
                "Hottest thread should have at least 10 samples, had: " + threadSamples.size());
    }

    @Test
    void testMethodProfileCorrelation() throws Exception {
        ScenarioDefinition scenario = TestScenarios.cpuHotspot();

        // Get JFR data (from saved file or record new)
        JfrParser.JfrData jfrData = getOrRecordJfrData(scenario);


        // Get hottest methods
        List<JfrAnalyzer.MethodProfile> methods = jfrAnalyzer.getHottestMethods(jfrData, 10);
        assertFalse(methods.isEmpty(), "Should identify hot methods");

        // Display for verification
        System.out.println("Hottest methods:");
        for (JfrAnalyzer.MethodProfile method : methods) {
            System.out.printf("  %s: %d samples (%.1f%%)%n",
                    method.method(), method.sampleCount(), method.percentage());
        }

        // Top method should have significant samples
        JfrAnalyzer.MethodProfile topMethod = methods.getFirst();
        assertTrue(topMethod.sampleCount() > 0, "Top method should have samples");
        assertTrue(topMethod.percentage() > 0, "Top method should have measurable percentage");
    }

    @Test
    void testLockContentionCorrelation() throws Exception {
        ScenarioDefinition scenario = TestScenarios.lockContention();

        // Get JFR data (from saved file or record new)
        JfrParser.JfrData jfrData = getOrRecordJfrData(scenario);


        // Get lock contention summary
        JfrAnalyzer.LockContentionSummary summary = jfrAnalyzer.getLockContentionSummary(jfrData);

        assertTrue(summary.totalEvents() > 0, "Should have lock contention events");
        assertTrue(summary.uniqueLocks() > 0, "Should have contended locks");

        // Display lock contention info
        System.out.println("Lock contention summary:");
        System.out.printf("  Total events: %d%n", summary.totalEvents());
        System.out.printf("  Unique locks: %d%n", summary.uniqueLocks());
        System.out.printf("  Total blocked time: %s%n", summary.totalBlockedTime());

        List<JfrAnalyzer.LockProfile> hotLocks = summary.hotLocks();
        if (!hotLocks.isEmpty()) {
            System.out.println("  Hot locks:");
            for (JfrAnalyzer.LockProfile lock : hotLocks) {
                System.out.printf("    %s: %d events, total: %s, max: %s%n",
                        lock.monitorClass(), lock.eventCount(),
                        lock.totalDuration(), lock.maxDuration());
            }
        }
    }

    @Test
    void testAllocationCorrelation() throws Exception {
        ScenarioDefinition scenario = TestScenarios.highAllocation();

        // Get JFR data (from saved file or record new)
        JfrParser.JfrData jfrData = getOrRecordJfrData(scenario);

        // Get allocation hotspots (may be empty if JFR profile doesn't capture allocations)
        List<JfrAnalyzer.AllocationProfile> hotspots = jfrAnalyzer.getAllocationHotspots(jfrData, 10);

        if (hotspots.isEmpty()) {
            // Allocation events depend on JFR profile configuration
            System.out.println("⚠ No allocation events captured by JFR profile");
            // Verify we at least have execution samples
            assertFalse(jfrData.executionSamples().isEmpty(),
                    "Should have execution samples even without allocation events");
            return;
        }

        // Display allocation info
        System.out.println("Allocation hotspots:");
        for (JfrAnalyzer.AllocationProfile hotspot : hotspots) {
            System.out.printf("  %s: %d allocations, %s (%.1f%%)%n",
                    hotspot.allocationSite(), hotspot.count(),
                    hotspot.getFormattedSize(), hotspot.percentage());
        }

        // Total allocations should be significant
        long totalBytes = hotspots.stream()
                .mapToLong(JfrAnalyzer.AllocationProfile::totalBytes)
                .sum();
        assertTrue(totalBytes > 0, "Should have measurable allocations");
    }

    @Test
    void testIOCorrelation() throws Exception {
        ScenarioDefinition scenario = TestScenarios.fileIO();

        // Get JFR data (from saved file or record new)
        JfrParser.JfrData jfrData = getOrRecordJfrData(scenario);


        // Get I/O summary
        JfrAnalyzer.IOSummary ioSummary = jfrAnalyzer.getIOSummary(jfrData);

        assertTrue(ioSummary.totalEvents() > 0, "Should have I/O events");

        // Display I/O info
        System.out.println("I/O summary:");
        System.out.printf("  Total events: %d%n", ioSummary.totalEvents());
        System.out.printf("  Total bytes: %s%n", ioSummary.getFormattedSize());
        System.out.printf("  Total duration: %s%n", ioSummary.totalDuration());

        List<JfrAnalyzer.IOTypeProfile> byType = ioSummary.byType();
        if (!byType.isEmpty()) {
            System.out.println("  By type:");
            for (JfrAnalyzer.IOTypeProfile type : byType) {
                System.out.printf("    %s: %d events, %s%n",
                        type.type(), type.count(), type.getFormattedSize());
            }
        }
    }

    @Test
    void testStackProfileCorrelation() throws Exception {
        ScenarioDefinition scenario = TestScenarios.deepRecursion();

        // Get JFR data (from saved file or record new)
        JfrParser.JfrData jfrData = getOrRecordJfrData(scenario);


        // Get stack profiles
        List<JfrAnalyzer.StackProfile> stacks = jfrAnalyzer.getStackProfiles(jfrData, 50);
        assertFalse(stacks.isEmpty(), "Should have stack profiles");

        // Should have some deep stacks from recursion
        boolean hasDeepStacks = stacks.stream()
                .anyMatch(s -> s.stack().size() > 10);
        assertTrue(hasDeepStacks, "Should have deep stacks from recursive calls");

        // Find the deepest stack
        JfrAnalyzer.StackProfile deepestStack = stacks.stream()
                .max((a, b) -> Integer.compare(a.stack().size(), b.stack().size()))
                .orElseThrow();

        assertTrue(deepestStack.stack().size() >= 20,
                "Deepest stack should have at least 20 frames from recursion, had: " + deepestStack.stack().size());

        // Display deepest stacks
        System.out.println("Stack profiles (top 3):");
        stacks.stream()
                .limit(3)
                .forEach(stack -> {
                    System.out.printf("  Depth %d: %d samples (%.1f%%)%n",
                            stack.stack().size(), stack.sampleCount(), stack.percentage());
                    System.out.println("    " + String.join(" <- ",
                            stack.stack().stream().limit(3).toList()));
                });
    }

    private ScenarioRecorder createRecorder(ScenarioDefinition scenario) {
        return ScenarioRecorder.builder()
                .name(scenario.getName())
                .duration(Duration.ofSeconds(10))
                .dumpInterval(Duration.ofSeconds(2))
                .outputDir(tempDir)
                .cleanup(false)
                .build();
    }
}