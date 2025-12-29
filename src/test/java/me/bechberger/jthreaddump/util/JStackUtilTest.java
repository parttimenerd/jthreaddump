package me.bechberger.jthreaddump.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JStackUtil
 */
class JStackUtilTest {

    @Test
    void testProcessExists_currentProcess() {
        // Get current JVM's PID
        long currentPid = ProcessHandle.current().pid();
        assertTrue(JStackUtil.processExists(currentPid), "Current process should exist");
    }

    @Test
    void testProcessExists_invalidPid() {
        assertFalse(JStackUtil.processExists(-1), "Negative PID should not exist");
        assertFalse(JStackUtil.processExists(0), "PID 0 should not exist");

        // Very high PID unlikely to exist
        assertFalse(JStackUtil.processExists(999999999), "Very high PID should not exist");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCaptureThreadDump_currentProcess() throws IOException {
        long currentPid = ProcessHandle.current().pid();

        // Capture thread dump of current process
        String threadDump = JStackUtil.captureThreadDump(currentPid);

        assertNotNull(threadDump, "Thread dump should not be null");
        assertFalse(threadDump.isEmpty(), "Thread dump should not be empty");

        // Should contain thread information
        assertTrue(threadDump.contains("java.lang.Thread.State"),
                "Thread dump should contain thread state information");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCaptureThreadDump_withJcmd() throws IOException {
        long currentPid = ProcessHandle.current().pid();

        // Capture thread dump using jcmd
        String threadDump = JStackUtil.captureThreadDump(currentPid, true);

        assertNotNull(threadDump, "Thread dump should not be null");
        assertFalse(threadDump.isEmpty(), "Thread dump should not be empty");

        // jcmd output should contain thread information
        assertTrue(threadDump.contains("java.lang.Thread.State") || threadDump.contains("Thread.print"),
                "Thread dump should contain thread information");
    }

    @Test
    void testCaptureThreadDump_invalidPid() {
        assertThrows(IllegalArgumentException.class, () -> {
            JStackUtil.captureThreadDump(-1);
        }, "Should throw exception for invalid PID");

        assertThrows(IOException.class, () -> {
            JStackUtil.captureThreadDump(999999999);
        }, "Should throw IOException for non-existent process");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void testCaptureThreadDumpToFile() throws IOException {
        long currentPid = ProcessHandle.current().pid();
        Path tempFile = Files.createTempFile("threaddump-test-", ".txt");

        try {
            JStackUtil.captureThreadDumpToFile(currentPid, false, tempFile);

            assertTrue(Files.exists(tempFile), "Output file should exist");
            assertTrue(Files.size(tempFile) > 0, "Output file should not be empty");

            String content = Files.readString(tempFile);
            assertTrue(content.contains("java.lang.Thread.State"),
                    "File should contain thread dump content");

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}