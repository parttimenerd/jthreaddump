package me.bechberger.jstall.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.bechberger.jstall.cli.ParseCommand.OutputFormat;
import me.bechberger.jstall.test.ThreadDumpGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static me.bechberger.jstall.test.JsonAssertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ParseCommand
 */
public class ParseCommandTest {

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final ObjectMapper jsonMapper = new ObjectMapper().findAndRegisterModules();

    private JsonNode parseJson(String json) throws IOException {
        return jsonMapper.readTree(json);
    }

    @BeforeEach
    void setUp() {
        outputStream.reset();
        errorStream.reset();
        System.setOut(new PrintStream(outputStream));
        System.setErr(new PrintStream(errorStream));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void testParseNonExistentFile() throws Exception {
        ParseCommand cmd = new ParseCommand();
        Path nonExistent = tempDir.resolve("does-not-exist.txt");

        // Use reflection to set the field since it's private
        setField(cmd, "dumpFile", nonExistent);

        int exitCode = cmd.call();
        assertEquals(1, exitCode);
        assertTrue(errorStream.toString().contains("not found"));
    }

    @ParameterizedTest
    @EnumSource(OutputFormat.class)
    void testParseWithDifferentFormats(OutputFormat format) throws Exception {
        // Create a simple thread dump file
        Path dumpFile = tempDir.resolve("test-dump.txt");
        Files.writeString(dumpFile, """
                "main" #1 prio=5 tid=0x1000 nid=0x2000 runnable
                   java.lang.Thread.State: RUNNABLE
                   at com.example.Main.main(Main.java:10)
                """);

        ParseCommand cmd = new ParseCommand();
        setField(cmd, "dumpFile", dumpFile);
        setField(cmd, "outputFormat", format);

        int exitCode = cmd.call();
        assertEquals(0, exitCode);

        String output = outputStream.toString();
        assertFalse(output.isEmpty());

        switch (format) {
            case JSON -> {
                assertTrue(output.contains("{"));
                assertTrue(output.contains("main"));
                assertTrue(output.contains("RUNNABLE"));
            }
            case YAML -> {
                assertTrue(output.contains("main"));
                assertTrue(output.contains("RUNNABLE"));
            }
            case TEXT -> {
                assertTrue(output.contains("Thread Dump Analysis"));
                assertTrue(output.contains("main"));
            }
        }
    }

    @Test
    void testParseComplexDump() throws Exception {
        Path dumpFile = tempDir.resolve("complex-dump.txt");
        Files.writeString(dumpFile, """
                Full thread dump Java HotSpot(TM) 64-Bit Server VM:
                
                "main" #1 prio=5 cpu=100ms elapsed=5s tid=0x1000 nid=0x2000 runnable
                   java.lang.Thread.State: RUNNABLE
                   at com.example.Main.main(Main.java:10)
                
                "Worker" #2 daemon prio=5 cpu=50ms elapsed=3s tid=0x2000 nid=0x3000 waiting on condition
                   java.lang.Thread.State: WAITING
                   at java.lang.Object.wait(Native Method)
                   - waiting on <0x12345> (a java.lang.Object)
                
                JNI global refs: 100, weak refs: 200
                """);

        ParseCommand cmd = new ParseCommand();
        setField(cmd, "dumpFile", dumpFile);
        setField(cmd, "outputFormat", OutputFormat.TEXT);

        int exitCode = cmd.call();
        assertEquals(0, exitCode);

        String output = outputStream.toString();
        assertTrue(output.contains("main"));
        assertTrue(output.contains("Worker"));
        assertTrue(output.contains("JNI Resources"));
        assertTrue(output.contains("Total Threads: 2"));
    }

    @Test
    void testParseJsonPrettyPrint() throws Exception {
        Path dumpFile = tempDir.resolve("dump.txt");
        Files.writeString(dumpFile, """
                "test" #1 runnable
                   java.lang.Thread.State: RUNNABLE
                """);

        ParseCommand cmd = new ParseCommand();
        setField(cmd, "dumpFile", dumpFile);
        setField(cmd, "outputFormat", OutputFormat.JSON);
        setField(cmd, "prettyPrint", true);

        int exitCode = cmd.call();
        assertEquals(0, exitCode);

        String output = outputStream.toString();
        // Pretty printed JSON should have newlines and indentation
        assertTrue(output.contains("\n"));
        assertTrue(output.split("\n").length > 1);
    }

    @Test
    void testParseEmptyFile() throws Exception {
        Path dumpFile = tempDir.resolve("empty.txt");
        Files.writeString(dumpFile, "");

        ParseCommand cmd = new ParseCommand();
        setField(cmd, "dumpFile", dumpFile);

        int exitCode = cmd.call();
        assertEquals(0, exitCode); // Should still succeed with empty dump
    }

    static Stream<Arguments> realWorldDumpScenarios() {
        return Stream.of(
                Arguments.of("deadlock", ThreadDumpGenerator.deadlockScenario()),
                Arguments.of("reentrant-lock", ThreadDumpGenerator.reentrantLockScenario()),
                Arguments.of("virtual-threads", ThreadDumpGenerator.virtualThreadScenario()),
                Arguments.of("complex", ThreadDumpGenerator.complexScenario())
        );
    }

    @ParameterizedTest(name = "Parse real-world dump: {0}")
    @MethodSource("realWorldDumpScenarios")
    void testParseRealWorldDumps(String scenarioName, ThreadDumpGenerator.ThreadDumpScenario scenario)
            throws Exception {
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump(scenarioName, scenario);

        ParseCommand cmd = new ParseCommand();
        setField(cmd, "dumpFile", dumpFile);
        setField(cmd, "outputFormat", OutputFormat.JSON);

        int exitCode = cmd.call();
        assertEquals(0, exitCode);

        String jsonOutput = outputStream.toString();
        JsonNode json = parseJson(jsonOutput);

        // Verify JSON structure
        assertJsonFieldExists(json, "threads");
        assertJsonFieldExists(json, "sourceType");

        JsonNode threads = getJsonNode(json, "threads");
        assertTrue(threads.isArray());
        assertTrue(threads.size() > 0, "Should have at least one thread");
    }

    @Test
    void testParseDeadlockScenarioWithLocks() throws Exception {
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump("deadlock",
                ThreadDumpGenerator.deadlockScenario());

        ParseCommand cmd = new ParseCommand();
        setField(cmd, "dumpFile", dumpFile);
        setField(cmd, "outputFormat", OutputFormat.JSON);

        int exitCode = cmd.call();
        assertEquals(0, exitCode);

        String jsonOutput = outputStream.toString();
        JsonNode json = parseJson(jsonOutput);

        // Verify threads with deadlock
        JsonNode threads = getJsonNode(json, "threads");
        boolean foundThreadWithLocks = false;

        for (JsonNode thread : threads) {
            if (thread.has("locks") && thread.get("locks").size() > 0) {
                foundThreadWithLocks = true;

                // Verify lock structure
                JsonNode locks = thread.get("locks");
                for (JsonNode lock : locks) {
                    assertNotNull(lock.get("lockId"), "Lock should have lockId");
                    assertNotNull(lock.get("lockType"), "Lock should have lockType");
                }
            }
        }

        assertTrue(foundThreadWithLocks, "Deadlock scenario should have threads with locks");

        // Verify deadlock information is present
        if (json.has("deadlockInfos")) {
            JsonNode deadlockInfos = json.get("deadlockInfos");
            assertNotNull(deadlockInfos, "Deadlock infos should be present");
            assertTrue(deadlockInfos.isArray(), "Deadlock infos should be an array");

            if (deadlockInfos.size() > 0) {
                JsonNode deadlockInfo = deadlockInfos.get(0);

                if (deadlockInfo.has("threads")) {
                    JsonNode deadlockedThreads = deadlockInfo.get("threads");
                    assertTrue(deadlockedThreads.isArray(), "Deadlocked threads should be an array");
                    assertTrue(deadlockedThreads.size() >= 2, "Should have at least 2 deadlocked threads");

                    // Verify deadlocked thread structure
                    for (JsonNode dlThread : deadlockedThreads) {
                        assertNotNull(dlThread.get("threadName"), "Deadlocked thread should have name");
                        if (dlThread.has("waitingForMonitor")) {
                            assertNotNull(dlThread.get("waitingForMonitor"), "Should have waiting monitor");
                        }
                        if (dlThread.has("heldBy")) {
                            assertNotNull(dlThread.get("heldBy"), "Should have heldBy information");
                        }
                    }
                }

                if (deadlockInfo.has("summary")) {
                    String summary = deadlockInfo.get("summary").asText();
                    assertTrue(summary.contains("deadlock"), "Summary should mention deadlock");
                }
            }
        }
    }

    @Test
    void testParseVirtualThreadsScenario() throws Exception {
        Path dumpFile = ThreadDumpGenerator.getOrGenerateThreadDump("virtual-threads",
                ThreadDumpGenerator.virtualThreadScenario());

        ParseCommand cmd = new ParseCommand();
        setField(cmd, "dumpFile", dumpFile);
        setField(cmd, "outputFormat", OutputFormat.JSON);

        int exitCode = cmd.call();
        assertEquals(0, exitCode);

        String jsonOutput = outputStream.toString();
        JsonNode json = parseJson(jsonOutput);

        // Count virtual threads
        JsonNode threads = getJsonNode(json, "threads");
        int virtualCount = 0;
        for (JsonNode thread : threads) {
            if (thread.has("name")) {
                String name = thread.get("name").asText();
                if (name.contains("Virtual")) {
                    virtualCount++;
                }
            }
        }

        assertTrue(virtualCount > 0, "Should detect virtual threads");
    }

    @Test
    void testJsonOutputStructure() throws Exception {
        Path dumpFile = tempDir.resolve("structured-dump.txt");
        Files.writeString(dumpFile, """
                "StructuredThread" #100 daemon prio=7 cpu=50ms elapsed=1s tid=0xaaa nid=0xbbb waiting on condition
                   java.lang.Thread.State: WAITING
                   at java.lang.Object.wait(Native Method)
                   - waiting on <0x12345678> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
                   at com.example.Task.await(Task.java:42)
                   - locked <0x87654321> (a java.lang.Object)
                
                JNI global refs: 150, weak refs: 250
                """);

        ParseCommand cmd = new ParseCommand();
        setField(cmd, "dumpFile", dumpFile);
        setField(cmd, "outputFormat", OutputFormat.JSON);
        setField(cmd, "prettyPrint", true);

        int exitCode = cmd.call();
        assertEquals(0, exitCode);

        String jsonOutput = outputStream.toString();
        JsonNode json = parseJson(jsonOutput);

        // Detailed JSON structure validation
        assertJsonField(json, "threads.0.name", "StructuredThread");
        assertJsonField(json, "threads.0.threadId", 100);
        assertJsonField(json, "threads.0.daemon", true);
        assertJsonField(json, "threads.0.priority", 7);
        assertJsonField(json, "threads.0.state", "WAITING");
        assertJsonField(json, "threads.0.cpuTimeMs", 50L);
        assertJsonField(json, "threads.0.elapsedTimeMs", 1000L);

        // Verify stack trace
        assertJsonArraySize(json, "threads.0.stackTrace", 2);

        // Verify locks
        assertJsonArraySize(json, "threads.0.locks", 2);

        // Verify JNI info
        assertJsonField(json, "jniInfo.globalRefs", 150);
        assertJsonField(json, "jniInfo.weakRefs", 250);
    }

    @Test
    void testYamlOutputFormat() throws Exception {
        Path dumpFile = tempDir.resolve("yaml-test.txt");
        Files.writeString(dumpFile, """
                "YamlThread" #1 prio=5 tid=0x1 nid=0x2 runnable
                   java.lang.Thread.State: RUNNABLE
                """);

        ParseCommand cmd = new ParseCommand();
        setField(cmd, "dumpFile", dumpFile);
        setField(cmd, "outputFormat", OutputFormat.YAML);

        int exitCode = cmd.call();
        assertEquals(0, exitCode);

        String yamlOutput = outputStream.toString();
        assertTrue(yamlOutput.contains("YamlThread"));
        assertTrue(yamlOutput.contains("RUNNABLE"));
        // YAML typically uses different formatting
        assertTrue(yamlOutput.contains("threads:") || yamlOutput.contains("- name:"));
    }

    @Test
    void testTextOutputWithLocks() throws Exception {
        Path dumpFile = tempDir.resolve("text-locks.txt");
        Files.writeString(dumpFile, """
                "LockedThread" #5 prio=5 tid=0x100 nid=0x200 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED
                   at com.example.Worker.doWork(Worker.java:30)
                   - waiting to lock <0xdeadbeef> (a java.lang.Object)
                   - locked <0xcafebabe> (a java.lang.String)
                """);

        ParseCommand cmd = new ParseCommand();
        setField(cmd, "dumpFile", dumpFile);
        setField(cmd, "outputFormat", OutputFormat.TEXT);

        int exitCode = cmd.call();
        assertEquals(0, exitCode);

        String textOutput = outputStream.toString();
        assertTrue(textOutput.contains("LockedThread"));
        assertTrue(textOutput.contains("BLOCKED"));
        assertTrue(textOutput.contains("Locks:"));
        assertTrue(textOutput.contains("0xdeadbeef"));
        assertTrue(textOutput.contains("0xcafebabe"));
    }

    // Helper method to set private fields via reflection
    private void setField(Object obj, String fieldName, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}