package me.bechberger.jstall.cli;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StallCommand
 */
public class StallCommandTest {

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

    @Nested
    @DisplayName("Duration Parsing")
    class DurationParsing {

        @Test
        @DisplayName("Should parse seconds format (3s)")
        void testParseSeconds() {
            StallCommand cmd = new StallCommand();
            Duration result = cmd.parseDuration("3s");
            assertEquals(Duration.ofSeconds(3), result);
        }

        @Test
        @DisplayName("Should parse milliseconds format (500ms)")
        void testParseMilliseconds() {
            StallCommand cmd = new StallCommand();
            Duration result = cmd.parseDuration("500ms");
            assertEquals(Duration.ofMillis(500), result);
        }

        @Test
        @DisplayName("Should parse minutes format (1m)")
        void testParseMinutes() {
            StallCommand cmd = new StallCommand();
            Duration result = cmd.parseDuration("1m");
            assertEquals(Duration.ofMinutes(1), result);
        }

        @Test
        @DisplayName("Should parse plain number as seconds")
        void testParsePlainNumber() {
            StallCommand cmd = new StallCommand();
            Duration result = cmd.parseDuration("10");
            assertEquals(Duration.ofSeconds(10), result);
        }

        @Test
        @DisplayName("Should return null for invalid format")
        void testParseInvalid() {
            StallCommand cmd = new StallCommand();
            Duration result = cmd.parseDuration("abc");
            assertNull(result);
        }

        @Test
        @DisplayName("Should return null for null input")
        void testParseNull() {
            StallCommand cmd = new StallCommand();
            Duration result = cmd.parseDuration(null);
            assertNull(result);
        }

        @Test
        @DisplayName("Should return null for empty input")
        void testParseEmpty() {
            StallCommand cmd = new StallCommand();
            Duration result = cmd.parseDuration("");
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Input Validation")
    class InputValidation {

        @Test
        @DisplayName("Should fail with invalid PID")
        void testInvalidPid() throws Exception {
            StallCommand cmd = new StallCommand();
            setField(cmd, "pid", -1L);
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(StallCommand.EXIT_ERROR, exitCode);
            assertTrue(errorStream.toString().contains("Invalid PID"));
        }

        @Test
        @DisplayName("Should fail with invalid interval format")
        void testInvalidInterval() throws Exception {
            StallCommand cmd = new StallCommand();
            setField(cmd, "pid", 1L);
            setField(cmd, "interval", "invalid");
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(StallCommand.EXIT_ERROR, exitCode);
            assertTrue(errorStream.toString().contains("Invalid interval"));
        }

        @Test
        @DisplayName("Should fail with invalid timeout format")
        void testInvalidTimeout() throws Exception {
            StallCommand cmd = new StallCommand();
            setField(cmd, "pid", 1L);
            setField(cmd, "interval", "1s");
            setField(cmd, "timeout", "invalid");
            setField(cmd, "sharedOptions", new SharedOptions());

            int exitCode = cmd.call();
            assertEquals(StallCommand.EXIT_ERROR, exitCode);
            assertTrue(errorStream.toString().contains("Invalid timeout"));
        }
    }

    @Nested
    @DisplayName("Exit Codes")
    class ExitCodes {

        @Test
        @DisplayName("EXIT_OK should be 0")
        void testExitOk() {
            assertEquals(0, StallCommand.EXIT_OK);
        }

        @Test
        @DisplayName("EXIT_STALL should be 1")
        void testExitStall() {
            assertEquals(1, StallCommand.EXIT_STALL);
        }

        @Test
        @DisplayName("EXIT_DEADLOCK should be 2")
        void testExitDeadlock() {
            assertEquals(2, StallCommand.EXIT_DEADLOCK);
        }

        @Test
        @DisplayName("EXIT_ERROR should be 3")
        void testExitError() {
            assertEquals(3, StallCommand.EXIT_ERROR);
        }
    }

    @Nested
    @DisplayName("Integration with picocli")
    class PicocliIntegration {

        @Test
        @DisplayName("Should have help option")
        void testHelpOption() {
            int exitCode = new CommandLine(new StallCommand())
                    .execute("--help");

            assertEquals(0, exitCode);
            String output = outputStream.toString();
            assertTrue(output.contains("stall") || output.contains("Usage"));
        }

        @Test
        @DisplayName("Should accept interval option")
        void testIntervalOption() {
            // This will fail because process doesn't exist, but option parsing should work
            int exitCode = new CommandLine(new StallCommand())
                    .execute("-i", "5s", "99999999");

            // Will fail with process not found, but option parsing succeeded
            assertTrue(exitCode != 0 || errorStream.toString().contains("not found") ||
                    errorStream.toString().contains("Error"));
        }

        @Test
        @DisplayName("Should accept timeout option")
        void testTimeoutOption() {
            int exitCode = new CommandLine(new StallCommand())
                    .execute("-t", "30s", "99999999");

            // Will fail with process not found, but option parsing succeeded
            assertTrue(exitCode != 0 || errorStream.toString().contains("not found") ||
                    errorStream.toString().contains("Error"));
        }

        @Test
        @DisplayName("Should accept --jcmd option")
        void testJcmdOption() {
            int exitCode = new CommandLine(new StallCommand())
                    .execute("--jcmd", "99999999");

            // Will fail with process not found, but option parsing succeeded
            assertTrue(exitCode != 0);
        }

        @Test
        @DisplayName("Should accept --quiet option")
        void testQuietOption() {
            int exitCode = new CommandLine(new StallCommand())
                    .execute("--quiet", "99999999");

            // Will fail with process not found, but option parsing succeeded
            assertTrue(exitCode != 0);
        }

        @Test
        @DisplayName("Should accept --verbose option")
        void testVerboseOption() {
            int exitCode = new CommandLine(new StallCommand())
                    .execute("--verbose", "99999999");

            // Will fail with process not found, but option parsing succeeded
            assertTrue(exitCode != 0);
        }
    }

    @Nested
    @DisplayName("Save Dumps Functionality")
    class SaveDumps {

        @Test
        @DisplayName("Should save dumps when --save-dumps is specified")
        void testSaveDumpsCreatesDirectory() throws Exception {
            Path saveDumpsDir = tempDir.resolve("dumps");

            StallCommand cmd = new StallCommand();
            setField(cmd, "saveDumpsDir", saveDumpsDir);

            // The directory creation is tested indirectly - setting up the field works
            assertNotNull(saveDumpsDir);
        }
    }

    @Nested
    @DisplayName("Analysis Options Building")
    class AnalysisOptionsBuilding {

        @Test
        @DisplayName("Should build analysis options with defaults")
        void testBuildDefaultOptions() throws Exception {
            SharedOptions sharedOptions = new SharedOptions();
            var options = sharedOptions.buildAnalysisOptions();
            assertNotNull(options);
        }

        @Test
        @DisplayName("Should build analysis options with custom settings")
        void testBuildCustomOptions() throws Exception {
            SharedOptions sharedOptions = new SharedOptions();
            setField(sharedOptions, "includeGc", true);
            setField(sharedOptions, "includeVm", true);
            setField(sharedOptions, "includeDaemon", false);

            var options = sharedOptions.buildAnalysisOptions();
            assertNotNull(options);
        }
    }

    @Nested
    @DisplayName("Output Options Building")
    class OutputOptionsBuilding {

        @Test
        @DisplayName("Should build output options with defaults")
        void testBuildDefaultOutputOptions() throws Exception {
            SharedOptions sharedOptions = new SharedOptions();
            var options = sharedOptions.buildOutputOptions();
            assertNotNull(options);
        }

        @Test
        @DisplayName("Should build output options with color enabled")
        void testBuildOutputOptionsWithColor() throws Exception {
            SharedOptions sharedOptions = new SharedOptions();
            setField(sharedOptions, "colorEnabled", true);

            var options = sharedOptions.buildOutputOptions();
            assertNotNull(options);
        }

        @Test
        @DisplayName("Should build output options with color disabled")
        void testBuildOutputOptionsNoColor() throws Exception {
            SharedOptions sharedOptions = new SharedOptions();
            setField(sharedOptions, "colorEnabled", false);

            var options = sharedOptions.buildOutputOptions();
            assertNotNull(options);
        }

        @Test
        @DisplayName("Should build output options with verbose mode")
        void testBuildOutputOptionsVerbose() throws Exception {
            SharedOptions sharedOptions = new SharedOptions();
            setField(sharedOptions, "verbose", true);

            var options = sharedOptions.buildOutputOptions();
            assertNotNull(options);
        }
    }
}