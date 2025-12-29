package me.bechberger.jstall.cli;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.analysis.analyzers.*;
import me.bechberger.jstall.model.ThreadDump;
import me.bechberger.jstall.parser.ThreadDumpParser;
import me.bechberger.jstall.view.*;
import picocli.CommandLine.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Stall command - captures thread dumps from a live process and detects stalls.
 *
 * Usage: jstall stall <pid> -i <interval> -t <timeout>
 */
@Command(
        name = "stall",
        description = "Capture thread dumps from a live process and detect stalls",
        mixinStandardHelpOptions = true
)
public class StallCommand implements Callable<Integer> {

    @Mixin
    private SharedOptions sharedOptions;

    @Parameters(index = "0", description = "Process ID (PID) to analyze")
    private long pid;

    @Option(names = {"-i", "--interval"}, description = "Interval between dumps (default: ${DEFAULT-VALUE})")
    private String interval = "3s";

    @Option(names = {"-t", "--timeout"}, description = "Total analysis time (default: ${DEFAULT-VALUE})")
    private String timeout = "10s";

    @Option(names = {"--min-dumps"}, description = "Minimum number of dumps to capture (default: ${DEFAULT-VALUE})")
    private int minDumps = 2;

    @Option(names = {"--max-dumps"}, description = "Maximum number of dumps to capture (default: ${DEFAULT-VALUE})")
    private int maxDumps = 10;

    @Option(names = {"--jfr"}, description = "Additionally capture JFR data")
    private boolean captureJfr = false;

    @Option(names = {"--jfr-duration"}, description = "JFR recording duration (default: same as timeout)")
    private String jfrDuration = null;

    @Option(names = {"--fail-on-stall"}, description = "Exit with code 1 if stall detected")
    private boolean failOnStall = false;

    @Option(names = {"--fail-on-deadlock"}, description = "Exit with code 2 if deadlock detected")
    private boolean failOnDeadlock = false;

    @Option(names = {"--save"}, description = "Save analysis result to file")
    private Path saveFile;

    @Option(names = {"--save-dumps"}, description = "Save thread dumps to directory")
    private Path saveDumpsDir;

    @Option(names = {"--html"}, description = "Export HTML report to file")
    private Path htmlFile;

    @Option(names = {"--jcmd"}, description = "Use jcmd instead of jstack (includes more info)")
    private boolean useJcmd = false;

    /** Exit codes */
    public static final int EXIT_OK = 0;
    public static final int EXIT_STALL = 1;
    public static final int EXIT_DEADLOCK = 2;
    public static final int EXIT_ERROR = 3;

    @Override
    public Integer call() throws Exception {
        // Validate PID
        if (pid <= 0) {
            sharedOptions.errorLog("Invalid PID: " + pid);
            return EXIT_ERROR;
        }

        // Parse durations
        Duration intervalDuration = parseDuration(interval);
        Duration timeoutDuration = parseDuration(timeout);
        Duration jfrRecordDuration = jfrDuration != null ? parseDuration(jfrDuration) : timeoutDuration;

        if (intervalDuration == null) {
            sharedOptions.errorLog("Invalid interval format: " + interval);
            return EXIT_ERROR;
        }
        if (timeoutDuration == null) {
            sharedOptions.errorLog("Invalid timeout format: " + timeout);
            return EXIT_ERROR;
        }

        // Check if process exists
        if (!processExists(pid)) {
            sharedOptions.errorLog("Process not found: " + pid);
            return EXIT_ERROR;
        }

        try {
            // Start JFR recording if requested
            Path jfrFile = null;
            if (captureJfr) {
                jfrFile = startJfrRecording(pid, jfrRecordDuration);
                if (jfrFile != null) {
                    sharedOptions.verboseLog("Started JFR recording: " + jfrFile);
                }
            }

            // Capture thread dumps
            List<ThreadDump> dumps = captureThreadDumps(intervalDuration, timeoutDuration);

            if (dumps.size() < minDumps) {
                sharedOptions.errorLog("Only captured " + dumps.size() + " dumps (minimum: " + minDumps + ")");
                return EXIT_ERROR;
            }

            // Save dumps if requested
            if (saveDumpsDir != null) {
                saveDumps(dumps, saveDumpsDir);
            }

            // Stop JFR recording
            if (captureJfr && jfrFile != null) {
                stopJfrRecording(pid);
                sharedOptions.verboseLog("Stopped JFR recording");
            }

            // Build analysis context using shared options
            AnalysisOptions options = sharedOptions.buildAnalysisOptions();
            AnalysisContext context;
            if (jfrFile != null && Files.exists(jfrFile)) {
                context = AnalysisContext.of(dumps, jfrFile, options);
            } else {
                context = AnalysisContext.of(dumps, options);
            }

            // Run analysis
            AnalysisEngine engine = AnalysisEngine.createDefault();
            AnalysisResult.CompositeResult result = engine.analyzeAll(context);

            // Get verdict
            VerdictAnalyzer verdictAnalyzer = new VerdictAnalyzer();
            VerdictAnalyzer.VerdictResult verdictResult = verdictAnalyzer.analyze(context);

            // Check for issues
            boolean hasDeadlock = hasDeadlockDetected(result);
            boolean hasStall = hasStallDetected(verdictResult);

            // Output results using shared options
            OutputOptions outputOptions = sharedOptions.buildOutputOptions();
            outputResults(result, verdictResult, outputOptions);

            // Save if requested
            if (saveFile != null) {
                saveResult(result, saveFile);
            }

            // Export HTML if requested
            if (htmlFile != null) {
                exportHtml(result, htmlFile);
            }

            // Cleanup temp JFR file if we created it
            if (jfrFile != null && captureJfr) {
                try {
                    Files.deleteIfExists(jfrFile);
                } catch (IOException e) {
                    // Ignore cleanup errors
                }
            }

            // Determine exit code
            if (failOnDeadlock && hasDeadlock) {
                return EXIT_DEADLOCK;
            }
            if (failOnStall && hasStall) {
                return EXIT_STALL;
            }
            return EXIT_OK;

        } catch (IOException e) {
            sharedOptions.errorLog(e.getMessage());
            if (sharedOptions.isVerbose()) {
                e.printStackTrace();
            }
            return EXIT_ERROR;
        } catch (InterruptedException e) {
            sharedOptions.errorLog("Interrupted");
            Thread.currentThread().interrupt();
            return EXIT_ERROR;
        }
    }

    /**
     * Parse duration string like "3s", "500ms", "1m"
     */
    Duration parseDuration(String durationStr) {
        if (durationStr == null || durationStr.isEmpty()) {
            return null;
        }

        try {
            durationStr = durationStr.toLowerCase().trim();

            if (durationStr.endsWith("ms")) {
                long value = Long.parseLong(durationStr.substring(0, durationStr.length() - 2));
                return Duration.ofMillis(value);
            } else if (durationStr.endsWith("s")) {
                long value = Long.parseLong(durationStr.substring(0, durationStr.length() - 1));
                return Duration.ofSeconds(value);
            } else if (durationStr.endsWith("m")) {
                long value = Long.parseLong(durationStr.substring(0, durationStr.length() - 1));
                return Duration.ofMinutes(value);
            } else {
                // Assume seconds
                long value = Long.parseLong(durationStr);
                return Duration.ofSeconds(value);
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean processExists(long pid) {
        try {
            return ProcessHandle.of(pid).isPresent();
        } catch (Exception e) {
            // Fallback: try jps or similar
            return true; // Assume it exists, let jstack fail if not
        }
    }

    private List<ThreadDump> captureThreadDumps(Duration intervalDuration, Duration timeoutDuration)
            throws IOException, InterruptedException {
        List<ThreadDump> dumps = new ArrayList<>();
        ThreadDumpParser parser = new ThreadDumpParser();

        Instant startTime = Instant.now();
        Instant endTime = startTime.plus(timeoutDuration);
        int dumpCount = 0;

        if (!sharedOptions.isQuiet()) {
            System.err.println("Capturing thread dumps from PID " + pid + "...");
        }

        while (Instant.now().isBefore(endTime) && dumpCount < maxDumps) {
            String dumpContent = captureThreadDump(pid);
            if (dumpContent != null && !dumpContent.isEmpty()) {
                ThreadDump dump = parser.parse(dumpContent);
                if (dump != null && !dump.threads().isEmpty()) {
                    dumps.add(dump);
                    dumpCount++;
                    sharedOptions.verboseLog("Captured dump " + dumpCount + " with " +
                            dump.threads().size() + " threads");
                }
            }

            // Wait for next interval (unless we've reached the end)
            if (Instant.now().plus(intervalDuration).isBefore(endTime)) {
                Thread.sleep(intervalDuration.toMillis());
            } else {
                break;
            }
        }

        if (!sharedOptions.isQuiet()) {
            System.err.println("Captured " + dumps.size() + " thread dumps");
        }

        return dumps;
    }

    private String captureThreadDump(long pid) throws IOException, InterruptedException {
        ProcessBuilder pb;
        if (useJcmd) {
            pb = new ProcessBuilder("jcmd", String.valueOf(pid), "Thread.print", "-l");
        } else {
            pb = new ProcessBuilder("jstack", "-l", String.valueOf(pid));
        }

        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            sharedOptions.verboseLog("Warning: jstack/jcmd exited with code " + exitCode);
        }

        return output.toString();
    }

    private Path startJfrRecording(long pid, Duration duration) throws IOException, InterruptedException {
        Path jfrFile = Files.createTempFile("jstall-jfr-", ".jfr");

        String durationStr = duration.toSeconds() + "s";

        ProcessBuilder pb = new ProcessBuilder(
                "jcmd", String.valueOf(pid),
                "JFR.start",
                "name=jstall",
                "settings=profile",
                "duration=" + durationStr,
                "filename=" + jfrFile.toAbsolutePath()
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Read and discard output
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) {
                // discard
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            sharedOptions.verboseLog("Warning: Failed to start JFR recording (exit code: " + exitCode + ")");
            return null;
        }

        return jfrFile;
    }

    private void stopJfrRecording(long pid) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "jcmd", String.valueOf(pid),
                "JFR.stop",
                "name=jstall"
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Read and discard output
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) {
                // discard
            }
        }

        process.waitFor();
    }

    private void saveDumps(List<ThreadDump> dumps, Path directory) throws IOException {
        Files.createDirectories(directory);

        for (int i = 0; i < dumps.size(); i++) {
            ThreadDump dump = dumps.get(i);
            String filename = String.format("dump-%03d.txt", i);
            Path file = directory.resolve(filename);

            // Save in a simple format (we don't have the original content, so summarize)
            StringBuilder sb = new StringBuilder();
            sb.append("# Thread Dump ").append(i).append("\n");
            if (dump.timestamp() != null) {
                sb.append("# Timestamp: ").append(dump.timestamp()).append("\n");
            }
            sb.append("# Threads: ").append(dump.threads().size()).append("\n\n");

            for (var thread : dump.threads()) {
                sb.append("\"").append(thread.name()).append("\"");
                if (thread.threadId() != null) {
                    sb.append(" tid=").append(thread.threadId());
                }
                if (thread.nativeId() != null) {
                    sb.append(" nid=0x").append(Long.toHexString(thread.nativeId()));
                }
                if (thread.state() != null) {
                    sb.append(" ").append(thread.state().toString().toLowerCase());
                }
                sb.append("\n");
                if (thread.state() != null) {
                    sb.append("   java.lang.Thread.State: ").append(thread.state()).append("\n");
                }
                for (var frame : thread.stackTrace()) {
                    sb.append("        at ").append(frame).append("\n");
                }
                sb.append("\n");
            }

            Files.writeString(file, sb.toString());
        }

        sharedOptions.verboseLog("Saved " + dumps.size() + " dumps to: " + directory);
    }

    private boolean hasDeadlockDetected(AnalysisResult.CompositeResult result) {
        for (AnalysisResult r : result.getResults()) {
            if (r instanceof DeadlockAnalyzer.DeadlockResult deadlockResult) {
                return deadlockResult.hasDeadlocks();
            }
        }
        return false;
    }

    private boolean hasStallDetected(VerdictAnalyzer.VerdictResult verdict) {
        return verdict.getStatus() == VerdictAnalyzer.VerdictStatus.SUSPECTED_STALL ||
               verdict.getStatus() == VerdictAnalyzer.VerdictStatus.DEADLOCK;
    }

    private void outputResults(AnalysisResult.CompositeResult result,
                               VerdictAnalyzer.VerdictResult verdict,
                               OutputOptions options) {
        if (sharedOptions.isQuiet()) {
            // Only show verdict
            System.out.println("VERDICT: " + verdict.getStatus());
            if (verdict.getSeverity().isWorseThan(AnalysisResult.Severity.OK)) {
                System.out.println("Severity: " + verdict.getSeverity());
            }
            return;
        }

        ViewRendererFactory.initialize();

        // Create composite result with verdict
        List<AnalysisResult> allResults = new ArrayList<>(result.getResults());
        allResults.add(verdict);
        AnalysisResult.CompositeResult fullResult = new AnalysisResult.CompositeResult(allResults);

        String output = ViewRendererFactory.render(fullResult, options);
        System.out.println(output);
    }

    private void saveResult(AnalysisResult result, Path file) throws IOException {
        OutputFormat format = OutputFormat.JSON;

        String extension = file.toString().toLowerCase();
        if (extension.endsWith(".yaml") || extension.endsWith(".yml")) {
            format = OutputFormat.YAML;
        }

        OutputOptions saveOptions = OutputOptions.builder().format(format).build();
        String output = ViewRendererFactory.render(result, saveOptions);
        Files.writeString(file, output);

        sharedOptions.verboseLog("Saved to: " + file);
    }

    private void exportHtml(AnalysisResult result, Path file) throws IOException {
        OutputOptions htmlOptions = OutputOptions.builder()
                .format(OutputFormat.HTML)
                .build();
        String output = ViewRendererFactory.render(result, htmlOptions);
        Files.writeString(file, output);

        sharedOptions.verboseLog("HTML report exported to: " + file);
    }
}