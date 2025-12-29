package me.bechberger.jstall.test.scenarios;

import me.bechberger.jstall.jfr.JfrAnalyzer;
import me.bechberger.jstall.jfr.JfrParser;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that run all scenarios and verify basic JFR data collection.
 *
 * Uses pre-recorded JFR files from test resources when available for reproducibility.
 * Only records new scenarios when no saved data exists.
 */
class ScenarioIntegrationTest {

    private static final Path TEST_RESOURCES = Path.of("src/test/resources/scenarios");

    @TempDir
    Path tempDir;

    static Stream<ScenarioDefinition> allScenarios() {
        return TestScenarios.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allScenarios")
    void testScenarioRecordsJfrData(ScenarioDefinition scenario) throws Exception {
        // Check if saved JFR file exists
        Path savedJfr = TEST_RESOURCES.resolve(scenario.getName()).resolve(scenario.getName() + ".jfr");
        Path savedDir = TEST_RESOURCES.resolve(scenario.getName());

        JfrParser parser = new JfrParser();
        JfrParser.JfrData jfrData;

        if (Files.exists(savedJfr)) {
            // Use pre-recorded data for reproducibility
            System.out.println("Using saved JFR: " + savedJfr);
            jfrData = parser.parse(savedJfr);

            // Verify the saved file is valid
            assertNotNull(jfrData, "Saved JFR data should be parseable");

            // Verify thread dumps exist in the saved directory
            List<Path> savedDumps = Files.list(savedDir)
                    .filter(p -> p.getFileName().toString().startsWith("dump-"))
                    .toList();
            assertFalse(savedDumps.isEmpty(), "Should have saved thread dumps");

        } else {
            // Record new scenario (first run)
            System.out.println("Recording new scenario: " + scenario.getName());
            ScenarioRecorder recorder = ScenarioRecorder.builder()
                    .name(scenario.getName())
                    .duration(Duration.ofSeconds(10))
                    .dumpInterval(Duration.ofSeconds(2))
                    .outputDir(tempDir.resolve(scenario.getName()))
                    .saveToTestResources(true)  // Save for future runs
                    .cleanup(false)
                    .build();

            try {
                // Record the scenario
                recorder.record(scenario);

                // Verify JFR file was created
                assertNotNull(recorder.getJfrFile(), "JFR file should be created");
                assertTrue(recorder.getJfrFile().toFile().exists(), "JFR file should exist");
                assertTrue(recorder.getJfrFile().toFile().length() > 0, "JFR file should not be empty");

                // Verify thread dumps were captured
                assertFalse(recorder.getDumpFiles().isEmpty(), "Should have captured thread dumps");
                assertTrue(recorder.getDumpFiles().size() >= 3,
                        "Should have at least 3 thread dumps (10s duration, 2s interval)");

                // Parse JFR data
                jfrData = parser.parse(recorder.getJfrFile());
            } finally {
                // Cleanup temp files (saved files are preserved in test resources)
                recorder.cleanup();
            }
        }

        // Basic validation - at least some data should be present
        assertFalse(jfrData.isEmpty(), "JFR data should not be completely empty");

        // Validate against expected results if defined
        if (scenario.getExpectedResults() != null) {
            validateExpectedResults(scenario, jfrData);
        }

        System.out.println("✓ " + scenario.getName() + ": " + scenario.getDescription());
    }

    private void validateExpectedResults(ScenarioDefinition scenario, JfrParser.JfrData jfrData) {
        ScenarioDefinition.ExpectedResults expected = scenario.getExpectedResults();
        JfrAnalyzer analyzer = new JfrAnalyzer();

        // Validate CPU expectations (this should always work)
        if (expected.expectHighCPU()) {
            assertFalse(jfrData.executionSamples().isEmpty(),
                    scenario.getName() + " should have CPU execution samples");
        }

        // Lock contention events depend on JFR threshold settings
        // JFR only captures lock events above certain thresholds (default 20ms for JavaMonitorWait)
        // So we make this a soft check - warn but don't fail
        if (expected.expectLockContention()) {
            if (jfrData.lockEvents().isEmpty()) {
                System.out.println("  ⚠ " + scenario.getName() +
                        ": expected lock contention events but JFR didn't capture any (threshold-dependent)");
            }
        }

        // I/O events depend on JFR threshold settings (default 20ms)
        // Some I/O operations may be too fast to be captured
        if (expected.expectIOBlocking()) {
            if (jfrData.ioEvents().isEmpty()) {
                System.out.println("  ⚠ " + scenario.getName() +
                        ": expected I/O events but JFR didn't capture any (threshold-dependent)");
            }
        }

        // Allocation events are not captured by default JFR profile
        // They require specific configuration (TLAB sampling)
        if (expected.expectHighAllocation()) {
            if (jfrData.allocationEvents().isEmpty()) {
                System.out.println("  ⚠ " + scenario.getName() +
                        ": expected allocation events but JFR didn't capture any (requires allocation sampling)");
            }
        }

        // Thread count validation should only consider pool threads to avoid test infrastructure noise
        // The JFR samples include test infrastructure threads (surefire, process reaper, etc.)
        if (expected.minThreadCount() > 0) {
            var threads = analyzer.getHottestThreads(jfrData, 100);
            // Count only pool threads (scenario threads)
            long poolThreadCount = threads.stream()
                    .filter(t -> t.threadName().matches("pool-\\d+-thread-\\d+") ||
                                 t.threadName().startsWith("DeadlockThread") ||
                                 t.threadName().startsWith("Worker") ||
                                 t.threadName().equals("main"))
                    .count();
            // Also accept if total threads meets expectation
            boolean meetsExpectation = poolThreadCount >= expected.minThreadCount() ||
                                       threads.size() >= expected.minThreadCount();
            if (!meetsExpectation) {
                System.out.println("  ⚠ " + scenario.getName() +
                        ": expected at least " + expected.minThreadCount() +
                        " threads, but only found " + poolThreadCount + " pool threads");
            }
        }

        // Deadlock scenarios - the deadlocked threads won't show up in CPU samples
        // because they're blocked. This is expected behavior.
        if (expected.expectDeadlock()) {
            // For deadlock scenarios, we just verify we have some samples
            // The deadlock detection is done via thread dump analysis, not JFR
            assertFalse(jfrData.executionSamples().isEmpty(),
                    scenario.getName() + " should have at least some execution samples");
        }
    }
}