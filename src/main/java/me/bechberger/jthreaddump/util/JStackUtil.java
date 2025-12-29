package me.bechberger.jthreaddump.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Utility class for capturing thread dumps from live Java processes using jstack or jcmd.
 */
public class JStackUtil {

    private static final int TIMEOUT_SECONDS = 30;

    /**
     * Capture a thread dump from the specified process using jstack.
     *
     * @param pid Process ID
     * @return Thread dump as a string
     * @throws IOException if the process cannot be found or jstack fails
     */
    public static String captureThreadDump(long pid) throws IOException {
        return captureThreadDump(pid, false);
    }

    /**
     * Capture a thread dump from the specified process.
     *
     * @param pid     Process ID
     * @param useJcmd If true, use jcmd instead of jstack (provides more detailed information)
     * @return Thread dump as a string
     * @throws IOException if the process cannot be found or the command fails
     */
    public static String captureThreadDump(long pid, boolean useJcmd) throws IOException {
        if (pid <= 0) {
            throw new IllegalArgumentException("Invalid PID: " + pid);
        }

        if (!processExists(pid)) {
            throw new IOException("Process not found: " + pid);
        }

        ProcessBuilder pb;
        if (useJcmd) {
            pb = new ProcessBuilder("jcmd", String.valueOf(pid), "Thread.print", "-l");
        } else {
            pb = new ProcessBuilder("jstack", "-l", String.valueOf(pid));
        }

        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Command timed out after " + TIMEOUT_SECONDS + " seconds");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("Command failed with exit code " + exitCode + ": " + output);
            }

            return output.toString();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while capturing thread dump", e);
        }
    }

    /**
     * Check if a process with the given PID exists.
     * This is a best-effort check and may not be 100% reliable on all platforms.
     *
     * @param pid Process ID
     * @return true if the process likely exists, false otherwise
     */
    public static boolean processExists(long pid) {
        if (pid <= 0) {
            return false;
        }

        // Try /proc filesystem (Linux)
        Path procPath = Path.of("/proc", String.valueOf(pid));
        if (Files.exists(procPath)) {
            return true;
        }

        // Try ps command (Unix/macOS)
        try {
            ProcessBuilder pb = new ProcessBuilder("ps", "-p", String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;

        } catch (IOException | InterruptedException e) {
            // If we can't check, assume it exists and let jstack fail if not
            return true;
        }
    }

    /**
     * Capture a thread dump and save it to a file.
     *
     * @param pid      Process ID
     * @param useJcmd  If true, use jcmd instead of jstack
     * @param outputPath Path to save the thread dump
     * @throws IOException if the capture or write fails
     */
    public static void captureThreadDumpToFile(long pid, boolean useJcmd, Path outputPath) throws IOException {
        String threadDump = captureThreadDump(pid, useJcmd);
        Files.writeString(outputPath, threadDump);
    }
}