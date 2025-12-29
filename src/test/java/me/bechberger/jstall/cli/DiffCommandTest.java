package me.bechberger.jstall.cli;

import me.bechberger.jstall.test.ThreadDumpGenerator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DiffCommand
 */
public class DiffCommandTest {

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @BeforeEach
    void setUp() {
        outputStream.reset();
        errorStream.reset();
        System.setOut(new PrintStream(outputStream));
        System.setErr(new PrintStream(errorStream));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    // Helper to create a simple thread dump
    private String createSimpleThreadDump(String... threadDefs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Full thread dump Java HotSpot(TM) 64-Bit Server VM:\n\n");
        int tid = 1;
        for (String def : threadDefs) {
            String[] parts = def.split(":");
            String name = parts[0];
            String state = parts.length > 1 ? parts[1] : "RUNNABLE";
            sb.append(String.format("\"%s\" #%d prio=5 tid=0x%04x nid=0x%04x %s%n",
                    name, tid, tid * 0x1000, tid * 0x100, state.toLowerCase()));
            sb.append("   java.lang.Thread.State: ").append(state).append("\n");
            sb.append("   at com.example.Worker.doWork(Worker.java:10)\n\n");
            tid++;
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("Basic Functionality")
    class BasicFunctionality {

        @Test
        @DisplayName("Should fail with no input files")
        void testNoInputFiles() throws Exception {
            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", null);
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(1, exitCode);
            assertTrue(errorStream.toString().contains("At least one"));
        }

        @Test
        @DisplayName("Should fail with non-existent file")
        void testNonExistentFile() throws Exception {
            DiffCommand cmd = new DiffCommand();
            Path nonExistent = tempDir.resolve("does-not-exist.txt");
            setField(cmd, "dumpFiles", List.of(nonExistent));
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(1, exitCode);
            assertTrue(errorStream.toString().contains("not found"));
        }

        @Test
        @DisplayName("Should analyze single dump file")
        void testSingleDumpFile() throws Exception {
            Path dumpFile = createDumpFile("dump1.txt", createSimpleThreadDump(
                    "main:RUNNABLE", "Worker-1:BLOCKED"));

            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", List.of(dumpFile));
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(0, exitCode);

            String output = outputStream.toString();
            assertFalse(output.isEmpty());
        }

        @Test
        @DisplayName("Should analyze multiple dump files")
        void testMultipleDumpFiles() throws Exception {
            Path dump1 = createDumpFile("dump1.txt", createSimpleThreadDump(
                    "main:RUNNABLE", "Worker-1:BLOCKED"));

            Path dump2 = createDumpFile("dump2.txt", createSimpleThreadDump(
                    "main:RUNNABLE", "Worker-1:RUNNABLE"));

            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", List.of(dump1, dump2));
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(0, exitCode);

            String output = outputStream.toString();
            assertFalse(output.isEmpty());
        }
    }

    @Nested
    @DisplayName("Deadlock Detection")
    class DeadlockDetection {

        @Test
        @DisplayName("Should detect deadlock with --fail-on-deadlock flag")
        void testFailOnDeadlock() throws Exception {
            String deadlockDump = """
                    "Thread-1" #1 prio=5 tid=0x1000 nid=0x2000 waiting for monitor entry
                       java.lang.Thread.State: BLOCKED (on object monitor)
                       at com.example.DeadlockDemo.method1(DeadlockDemo.java:10)
                       - waiting to lock <0x00000007401a8000> (a java.lang.Object)
                       - locked <0x00000007401a8010> (a java.lang.Object)
                    
                    "Thread-2" #2 prio=5 tid=0x2000 nid=0x3000 waiting for monitor entry
                       java.lang.Thread.State: BLOCKED (on object monitor)
                       at com.example.DeadlockDemo.method2(DeadlockDemo.java:20)
                       - waiting to lock <0x00000007401a8010> (a java.lang.Object)
                       - locked <0x00000007401a8000> (a java.lang.Object)
                    
                    Found one Java-level deadlock:
                    =============================
                    "Thread-1":
                      waiting to lock monitor 0x00000007401a8000 (object 0x00000007401a8000, a java.lang.Object),
                      which is held by "Thread-2"
                    "Thread-2":
                      waiting to lock monitor 0x00000007401a8010 (object 0x00000007401a8010, a java.lang.Object),
                      which is held by "Thread-1"
                    """;

            Path dumpFile = createDumpFile("deadlock.txt", deadlockDump);

            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", List.of(dumpFile));
            setField(cmd, "failOnDeadlock", true);
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(2, exitCode, "Should return exit code 2 for deadlock");
        }

        @Test
        @DisplayName("Should return 0 when no deadlock and --fail-on-deadlock set")
        void testNoDeadlockWithFlag() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump("main:RUNNABLE"));

            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", List.of(dumpFile));
            setField(cmd, "failOnDeadlock", true);
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(0, exitCode);
        }
    }

    @Nested
    @DisplayName("Output Modes")
    class OutputModes {

        @Test
        @DisplayName("Should output in quiet mode (verdict only)")
        void testQuietMode() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump(
                    "main:RUNNABLE", "Worker-1:WAITING"));

            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", List.of(dumpFile));
            SharedOptions sharedOptions = new SharedOptions();
            setField(sharedOptions, "quiet", true);
            setField(cmd, "sharedOptions", sharedOptions);

            int exitCode = cmd.call();
            assertEquals(0, exitCode);

            String output = outputStream.toString();
            // Quiet mode should produce minimal output
            assertTrue(output.length() > 0);
        }

        @Test
        @DisplayName("Should output in minimal mode")
        void testMinimalMode() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump("main:RUNNABLE"));

            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", List.of(dumpFile));
            setField(cmd, "mode", DiffCommand.OutputMode.MINIMAL);
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(0, exitCode);

            String output = outputStream.toString();
            assertTrue(output.contains("Summary") || output.contains("Status"));
        }

        @Test
        @DisplayName("Should output in verbose mode")
        void testVerboseMode() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump(
                    "main:RUNNABLE", "Worker-1:BLOCKED", "Worker-2:WAITING"));

            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", List.of(dumpFile));
            SharedOptions sharedOptions = new SharedOptions();
            setField(sharedOptions, "verbose", true);
            setField(cmd, "sharedOptions", sharedOptions);

            int exitCode = cmd.call();
            assertEquals(0, exitCode);

            // Verbose mode should produce more output
            String output = outputStream.toString();
            assertFalse(output.isEmpty());
        }
    }

    @Nested
    @DisplayName("File Output")
    class FileOutput {

        @Test
        @DisplayName("Should save analysis to JSON file")
        void testSaveToJson() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump("main:RUNNABLE"));

            Path saveFile = tempDir.resolve("analysis.json");

            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", List.of(dumpFile));
            setField(cmd, "saveFile", saveFile);
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(0, exitCode);

            assertTrue(Files.exists(saveFile));
            String content = Files.readString(saveFile);
            assertTrue(content.contains("{") || content.contains("["));
        }

        @Test
        @DisplayName("Should save analysis to YAML file")
        void testSaveToYaml() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump("main:RUNNABLE"));

            Path saveFile = tempDir.resolve("analysis.yaml");

            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", List.of(dumpFile));
            setField(cmd, "saveFile", saveFile);
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(0, exitCode);

            assertTrue(Files.exists(saveFile));
        }

        @Test
        @DisplayName("Should export HTML report")
        void testExportHtml() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump(
                    "main:RUNNABLE", "Worker-1:BLOCKED"));

            Path htmlFile = tempDir.resolve("report.html");

            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", List.of(dumpFile));
            setField(cmd, "htmlFile", htmlFile);
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(0, exitCode);

            assertTrue(Files.exists(htmlFile));
            String content = Files.readString(htmlFile);
            assertTrue(content.contains("<html") || content.contains("<!DOCTYPE") || content.contains("<"));
        }
    }

    @Nested
    @DisplayName("Thread Filtering")
    class ThreadFiltering {

        @Test
        @DisplayName("Should respect --include-gc flag")
        void testIncludeGc() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump(
                    "main:RUNNABLE", "GC Thread#1:RUNNABLE"));

            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", List.of(dumpFile));
            SharedOptions sharedOptions = new SharedOptions();
            setField(sharedOptions, "includeGc", true);
            setField(cmd, "sharedOptions", sharedOptions);

            int exitCode = cmd.call();
            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("Should respect --include-vm flag")
        void testIncludeVm() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump(
                    "main:RUNNABLE", "VM Thread:RUNNABLE"));

            DiffCommand cmd = new DiffCommand();
            setField(cmd, "dumpFiles", List.of(dumpFile));
            SharedOptions sharedOptions = new SharedOptions();
            setField(sharedOptions, "includeVm", true);
            setField(cmd, "sharedOptions", sharedOptions);

            int exitCode = cmd.call();
            assertEquals(0, exitCode);
        }
    }

    @Nested
    @DisplayName("Integration with picocli")
    class PicocliIntegration {

        @Test
        @DisplayName("Should work via CommandLine interface")
        void testViaPicocli() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump("main:RUNNABLE"));

            int exitCode = new CommandLine(new DiffCommand())
                    .execute(dumpFile.toString());

            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("Should support multiple file arguments")
        void testMultipleFilesViaPicocli() throws Exception {
            Path dump1 = createDumpFile("dump1.txt", createSimpleThreadDump("main:RUNNABLE"));
            Path dump2 = createDumpFile("dump2.txt", createSimpleThreadDump("main:RUNNABLE"));

            int exitCode = new CommandLine(new DiffCommand())
                    .execute(dump1.toString(), dump2.toString());

            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("Should support --quiet option")
        void testQuietOptionViaPicocli() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump("main:RUNNABLE"));

            int exitCode = new CommandLine(new DiffCommand())
                    .execute("--quiet", dumpFile.toString());

            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("Should support --verbose option")
        void testVerboseOptionViaPicocli() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump("main:RUNNABLE"));

            int exitCode = new CommandLine(new DiffCommand())
                    .execute("--verbose", dumpFile.toString());

            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("Should support --mode option")
        void testModeOptionViaPicocli() throws Exception {
            Path dumpFile = createDumpFile("dump.txt", createSimpleThreadDump("main:RUNNABLE"));

            int exitCode = new CommandLine(new DiffCommand())
                    .execute("--mode", "MINIMAL", dumpFile.toString());

            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("Should show help")
        void testHelpOption() {
            int exitCode = new CommandLine(new DiffCommand())
                    .execute("--help");

            assertEquals(0, exitCode);
            String output = outputStream.toString();
            assertTrue(output.contains("diff") || output.contains("Usage"));
        }
    }

    @Nested
    @DisplayName("Real-world Scenarios")
    class RealWorldScenarios {

        @Test
        @DisplayName("Should handle deadlock scenario from generator")
        void testDeadlockScenario() throws Exception {
            Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump("deadlock",
                    ThreadDumpGenerator.deadlockScenario());

            int exitCode = new CommandLine(new DiffCommand())
                    .execute(dumpFile.toString());

            // Should succeed (might find deadlock but won't fail without --fail-on-deadlock)
            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("Should handle complex scenario from generator")
        void testComplexScenario() throws Exception {
            Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump("complex",
                    ThreadDumpGenerator.complexScenario());

            int exitCode = new CommandLine(new DiffCommand())
                    .execute(dumpFile.toString());

            assertEquals(0, exitCode);
        }

        @Test
        @DisplayName("Should analyze all thread dumps from resources folder")
        void testResourceThreadDumps() throws Exception {
            // Get all thread dump files from resources
            Path resourcesDir = Path.of("src/test/resources");
            if (!Files.exists(resourcesDir)) {
                System.err.println("Resources directory not found, skipping test");
                return;
            }

            // Test single dumps
            String[] singleDumps = {
                "deadlock.txt",
                "complex.txt",
                "thread-pool.txt",
                "gc-activity.txt",
                "thread-dump-jstack.txt",
                "thread-dump-jcmd.txt"
            };

            for (String dumpName : singleDumps) {
                Path dumpFile = resourcesDir.resolve(dumpName);
                if (!Files.exists(dumpFile)) {
                    continue; // Skip if file doesn't exist
                }

                System.err.println("\n=== Testing: " + dumpName + " ===");

                int exitCode = new CommandLine(new DiffCommand())
                        .execute(dumpFile.toString());

                assertEquals(0, exitCode, "Analysis of " + dumpName + " should succeed");

                String output = outputStream.toString();
                assertFalse(output.isEmpty(), "Output should not be empty for " + dumpName);

                // Filter out handlebars debug logs
                String filteredOutput = output.lines()
                        .filter(line -> !line.contains("[main] DEBUG com.github.jknack.handlebars"))
                        .filter(line -> !line.trim().isEmpty())
                        .findFirst()
                        .orElse("");

                assertFalse(filteredOutput.isEmpty(),
                        "Should have non-debug output for " + dumpName);

                // Reset streams for next test
                outputStream.reset();
                errorStream.reset();
            }
        }

        @Test
        @DisplayName("Should analyze deadlock progression dumps")
        void testDeadlockProgression() throws Exception {
            Path resourcesDir = Path.of("src/test/resources");
            if (!Files.exists(resourcesDir)) {
                return;
            }

            // Collect deadlock progression dumps
            List<Path> progressionDumps = new ArrayList<>();
            for (int i = 0; i <= 5; i++) {
                Path dumpFile = resourcesDir.resolve("deadlock-progression-" + i + ".txt");
                if (Files.exists(dumpFile)) {
                    progressionDumps.add(dumpFile);
                }
            }

            if (progressionDumps.size() < 2) {
                System.err.println("Not enough progression dumps, skipping");
                return;
            }

            System.err.println("\n=== Testing deadlock progression with " +
                    progressionDumps.size() + " dumps ===");

            // Convert to array for picocli
            String[] args = progressionDumps.stream()
                    .map(Path::toString)
                    .toArray(String[]::new);

            int exitCode = new CommandLine(new DiffCommand()).execute(args);

            assertEquals(0, exitCode, "Multi-dump analysis should succeed");

            String output = outputStream.toString();
            String filteredOutput = output.lines()
                    .filter(line -> !line.contains("[main] DEBUG com.github.jknack.handlebars"))
                    .collect(java.util.stream.Collectors.joining("\n"));

            assertFalse(filteredOutput.trim().isEmpty(),
                    "Should have analysis output for progression dumps");

            System.err.println("Output preview (first 500 chars):");
            System.err.println(filteredOutput.substring(0,
                    Math.min(500, filteredOutput.length())));
        }

        @Test
        @DisplayName("Should analyze GC progression dumps")
        void testGCProgression() throws Exception {
            Path resourcesDir = Path.of("src/test/resources");
            if (!Files.exists(resourcesDir)) {
                return;
            }

            List<Path> gcDumps = new ArrayList<>();
            for (int i = 0; i <= 4; i++) {
                Path dumpFile = resourcesDir.resolve("gc-progression-" + i + ".txt");
                if (Files.exists(dumpFile)) {
                    gcDumps.add(dumpFile);
                }
            }

            if (gcDumps.isEmpty()) {
                return;
            }

            System.err.println("\n=== Testing GC progression with " +
                    gcDumps.size() + " dumps ===");

            String[] args = gcDumps.stream()
                    .map(Path::toString)
                    .toArray(String[]::new);

            int exitCode = new CommandLine(new DiffCommand()).execute(args);

            assertEquals(0, exitCode, "GC progression analysis should succeed");

            String output = outputStream.toString();
            String filteredOutput = output.lines()
                    .filter(line -> !line.contains("[main] DEBUG com.github.jknack.handlebars"))
                    .collect(java.util.stream.Collectors.joining("\n"));

            assertFalse(filteredOutput.trim().isEmpty(),
                    "Should have analysis output for GC dumps");
        }

        @Test
        @DisplayName("Should analyze thread pool progression dumps")
        void testThreadPoolProgression() throws Exception {
            Path resourcesDir = Path.of("src/test/resources");
            if (!Files.exists(resourcesDir)) {
                return;
            }

            List<Path> poolDumps = new ArrayList<>();
            for (int i = 0; i <= 4; i++) {
                Path dumpFile = resourcesDir.resolve("thread-pool-progression-" + i + ".txt");
                if (Files.exists(dumpFile)) {
                    poolDumps.add(dumpFile);
                }
            }

            if (poolDumps.isEmpty()) {
                return;
            }

            System.err.println("\n=== Testing thread pool progression with " +
                    poolDumps.size() + " dumps ===");

            String[] args = poolDumps.stream()
                    .map(Path::toString)
                    .toArray(String[]::new);

            int exitCode = new CommandLine(new DiffCommand()).execute(args);

            assertEquals(0, exitCode, "Thread pool progression analysis should succeed");

            String output = outputStream.toString();
            String filteredOutput = output.lines()
                    .filter(line -> !line.contains("[main] DEBUG com.github.jknack.handlebars"))
                    .collect(java.util.stream.Collectors.joining("\n"));

            assertFalse(filteredOutput.trim().isEmpty(),
                    "Should have analysis output for thread pool dumps");
        }

        @Test
        @DisplayName("Should handle verbose output without debug logs")
        void testVerboseWithoutDebugLogs() throws Exception {
            Path resourcesDir = Path.of("src/test/resources");
            Path dumpFile = resourcesDir.resolve("complex.txt");

            if (!Files.exists(dumpFile)) {
                return;
            }

            System.err.println("\n=== Testing verbose mode output filtering ===");

            int exitCode = new CommandLine(new DiffCommand())
                    .execute("--verbose", dumpFile.toString());

            assertEquals(0, exitCode);

            String output = outputStream.toString();
            String errorOutput = errorStream.toString();

            // Count lines before and after filtering
            long totalLines = output.lines().count();
            long debugLines = output.lines()
                    .filter(line -> line.contains("[main] DEBUG com.github.jknack.handlebars"))
                    .count();
            long contentLines = totalLines - debugLines;

            System.err.println("Total output lines: " + totalLines);
            System.err.println("Debug log lines: " + debugLines);
            System.err.println("Content lines: " + contentLines);

            assertTrue(contentLines > 0, "Should have content output beyond debug logs");

            // Show sample of filtered output
            String filteredOutput = output.lines()
                    .filter(line -> !line.contains("[main] DEBUG com.github.jknack.handlebars"))
                    .limit(20)
                    .collect(java.util.stream.Collectors.joining("\n"));

            System.err.println("\nFiltered output preview:");
            System.err.println(filteredOutput);
        }
    }

    private Path createDumpFile(String filename, String content) throws Exception {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, content);
        return file;
    }
}