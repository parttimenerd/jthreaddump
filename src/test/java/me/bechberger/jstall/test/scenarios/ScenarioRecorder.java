package me.bechberger.jstall.test.scenarios;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import me.bechberger.jstall.test.ThreadDumpGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Framework for creating thread dumps together with JFR recordings for testing.
 * <p>
 * Usage:
 * <pre>
 * ScenarioRecorder recorder = ScenarioRecorder.builder()
 *     .name("cpu-hotspot")
 *     .duration(Duration.ofSeconds(10))
 *     .dumpInterval(Duration.ofSeconds(2))
 *     .outputDir(Paths.get("target/test-scenarios"))
 *     .build();
 *
 * recorder.record(() -> {
 *     // Run your scenario code here
 *     cpuIntensiveWork();
 * });
 *
 * // Access the results
 * Path jfrFile = recorder.getJfrFile();
 * List&lt;Path&gt; dumpFiles = recorder.getDumpFiles();
 * </pre>
 */
public class ScenarioRecorder {

    private final String name;
    private final Duration duration;
    private final Duration dumpInterval;
    private final Path outputDir;
    private final boolean saveToTestResources;

    private Path jfrFile;
    private final List<Path> dumpFiles = new ArrayList<>();
    private final List<String> dumps = new ArrayList<>();

    private ScenarioRecorder(Builder builder) {
        this.name = builder.name;
        this.duration = builder.duration;
        this.dumpInterval = builder.dumpInterval;
        this.outputDir = builder.outputDir;
        this.saveToTestResources = builder.saveToTestResources;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Record a scenario, capturing JFR and thread dumps
     */
    public void record(Runnable scenario) throws IOException, InterruptedException {
        record(scenario, null);
    }

    /**
     * Record a scenario with a setup phase
     */
    public void record(Runnable scenario, Runnable setup) throws IOException, InterruptedException {
        Files.createDirectories(outputDir);

        // Run setup if provided
        if (setup != null) {
            setup.run();
        }

        // Start JFR recording
        try (Recording recording = createRecording()) {
            recording.start();

            // Start scenario in background with timeout handling
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "scenario-" + name);
                t.setDaemon(true);  // Make daemon so it doesn't block shutdown
                return t;
            });

            Future<?> scenarioFuture = executor.submit(() -> {
                try {
                    scenario.run();
                } catch (Exception e) {
                    System.err.println("Scenario " + name + " threw exception: " + e.getMessage());
                }
            });

            // Capture thread dumps at intervals with proper timeout
            long startTime = System.currentTimeMillis();
            long endTime = startTime + duration.toMillis();
            int dumpIndex = 0;

            while (System.currentTimeMillis() < endTime) {
                try {
                    String dump = captureThreadDump();
                    dumps.add(dump);

                    Path dumpFile = outputDir.resolve(name + "-dump-" + dumpIndex + ".txt");
                    Files.writeString(dumpFile, dump);
                    dumpFiles.add(dumpFile);
                    dumpIndex++;
                } catch (Exception e) {
                    System.err.println("Failed to capture thread dump: " + e.getMessage());
                }

                // Check if scenario completed early
                if (scenarioFuture.isDone()) {
                    break;
                }

                // Sleep for interval, but wake up early if scenario completes
                long remainingTime = endTime - System.currentTimeMillis();
                long sleepTime = Math.min(dumpInterval.toMillis(), remainingTime);
                if (sleepTime > 0) {
                    try {
                        scenarioFuture.get(sleepTime, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        // Expected - scenario still running
                    } catch (ExecutionException e) {
                        // Scenario threw exception
                        break;
                    }
                }
            }

            // Force stop scenario with aggressive shutdown
            scenarioFuture.cancel(true);
            executor.shutdownNow();

            // Don't wait too long for termination
            if (!executor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                System.err.println("Warning: Scenario " + name + " did not terminate cleanly");
            }

            // Stop recording and save
            recording.stop();
            jfrFile = outputDir.resolve(name + ".jfr");
            recording.dump(jfrFile);

            // Save to test resources if requested
            if (saveToTestResources) {
                saveToTestResources();
            }
        } catch (ParseException e) {
            throw new IOException("Failed to create JFR recording", e);
        }
    }

    /**
     * Record using a ScenarioDefinition
     */
    public void record(ScenarioDefinition scenario) throws IOException, InterruptedException {
        try {
            record(scenario::run, scenario::setup);
        } finally {
            // Always call cleanup on the scenario
            try {
                scenario.cleanup();
            } catch (Exception e) {
                System.err.println("Scenario cleanup failed: " + e.getMessage());
            }
        }
    }

    private Recording createRecording() throws IOException, ParseException {
        Configuration config = Configuration.getConfiguration("profile");
        Recording recording = new Recording(config);
        recording.setName(name);
        recording.setMaxAge(duration.plusSeconds(10));
        return recording;
    }

    private String captureThreadDump() throws IOException {
        return ThreadDumpGenerator.captureThreadDump();
    }

    /**
     * Save scenario data to test resources for reproducible tests
     */
    private void saveToTestResources() throws IOException {
        Path testResourcesDir = Path.of("src/test/resources/scenarios");
        Files.createDirectories(testResourcesDir);

        Path scenarioDir = testResourcesDir.resolve(name);
        Files.createDirectories(scenarioDir);

        // Copy JFR file
        if (jfrFile != null && Files.exists(jfrFile)) {
            Path targetJfr = scenarioDir.resolve(name + ".jfr");
            Files.copy(jfrFile, targetJfr, StandardCopyOption.REPLACE_EXISTING);
        }

        // Copy thread dumps
        for (int i = 0; i < dumpFiles.size(); i++) {
            Path dumpFile = dumpFiles.get(i);
            if (Files.exists(dumpFile)) {
                Path targetDump = scenarioDir.resolve("dump-" + i + ".txt");
                Files.copy(dumpFile, targetDump, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Save metadata
        Path metadataFile = scenarioDir.resolve("metadata.txt");
        try (var writer = Files.newBufferedWriter(metadataFile)) {
            writer.write("Scenario: " + name + "\n");
            writer.write("Duration: " + duration + "\n");
            writer.write("Dump Interval: " + dumpInterval + "\n");
            writer.write("Dumps Captured: " + dumpFiles.size() + "\n");
            writer.write("Timestamp: " + Instant.now() + "\n");
        }

        System.out.println("Saved scenario to: " + scenarioDir);
    }

    // Getters

    public Path getJfrFile() {
        return jfrFile;
    }

    public List<Path> getDumpFiles() {
        return dumpFiles;
    }

    public List<String> getDumps() {
        return dumps;
    }

    public String getName() {
        return name;
    }

    /**
     * Clean up generated files
     */
    public void cleanup() throws IOException {
        if (jfrFile != null && Files.exists(jfrFile)) {
            Files.delete(jfrFile);
        }
        for (Path dumpFile : dumpFiles) {
            if (Files.exists(dumpFile)) {
                Files.delete(dumpFile);
            }
        }
    }

    /**
     * Builder for ScenarioRecorder
     */
    public static class Builder {
        private String name = "scenario";
        private Duration duration = Duration.ofSeconds(10);
        private Duration dumpInterval = Duration.ofSeconds(2);
        private Path outputDir = Path.of("target/test-scenarios");
        private boolean saveToTestResources = false;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Builder dumpInterval(Duration dumpInterval) {
            this.dumpInterval = dumpInterval;
            return this;
        }

        public Builder outputDir(Path outputDir) {
            this.outputDir = outputDir;
            return this;
        }

        public Builder cleanup(boolean cleanup) {
            // Deprecated - cleanup is handled automatically
            return this;
        }

        public Builder saveToTestResources(boolean saveToTestResources) {
            this.saveToTestResources = saveToTestResources;
            return this;
        }

        public ScenarioRecorder build() {
            return new ScenarioRecorder(this);
        }
    }
}