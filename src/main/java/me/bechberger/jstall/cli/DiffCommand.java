package me.bechberger.jstall.cli;

import me.bechberger.jstall.analysis.*;
import me.bechberger.jstall.analysis.analyzers.*;
import me.bechberger.jstall.model.ThreadDump;
import me.bechberger.jstall.parser.ThreadDumpParser;
import me.bechberger.jstall.view.*;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Diff command - compares multiple thread dumps and detects stalls, deadlocks, etc.
 *
 * Usage: jstall diff dump1.txt dump2.txt [dump3.txt ...]
 */
@Command(
        name = "diff",
        description = "Compare multiple thread dumps to detect stalls, deadlocks, and thread evolution",
        mixinStandardHelpOptions = true
)
public class DiffCommand implements Callable<Integer> {

    @Mixin
    private SharedOptions sharedOptions;

    @Parameters(index = "0..*", arity = "1..*", description = "Thread dump files to compare (at least 1 required)")
    private List<Path> dumpFiles;

    @Option(names = {"--only-moving"}, description = "Show only threads that made progress")
    private boolean onlyMoving = false;

    @Option(names = {"--only-blocked"}, description = "Show only blocked/waiting threads")
    private boolean onlyBlocked = false;

    @Option(names = {"-m", "--mode"}, description = "Output mode: minimal, large, quiet, short-stall (default: ${DEFAULT-VALUE})")
    private OutputMode mode = OutputMode.LARGE;

    @Option(names = {"--fail-on-stall"}, description = "Exit with code 1 if stall detected")
    private boolean failOnStall = false;

    @Option(names = {"--fail-on-deadlock"}, description = "Exit with code 2 if deadlock detected")
    private boolean failOnDeadlock = false;

    @Option(names = {"--jfr"}, description = "JFR recording file for additional profiling data")
    private Path jfrFile;

    @Option(names = {"--save"}, description = "Save analysis result to file (json/yaml)")
    private Path saveFile;

    @Option(names = {"--html"}, description = "Export HTML report to file")
    private Path htmlFile;

    public enum OutputMode {
        /** Minimal output - just the verdict */
        MINIMAL,
        /** Full output with all details */
        LARGE,
        /** Only output on problems */
        QUIET,
        /** Short stall-focused output */
        SHORT_STALL
    }

    @Override
    public Integer call() throws Exception {
        // Validate inputs
        if (dumpFiles == null || dumpFiles.isEmpty()) {
            sharedOptions.errorLog("At least one thread dump file is required");
            return 1;
        }

        // Validate all files exist
        for (Path file : dumpFiles) {
            if (!Files.exists(file)) {
                sharedOptions.errorLog("File not found: " + file);
                return 1;
            }
            if (!Files.isRegularFile(file)) {
                sharedOptions.errorLog("Not a regular file: " + file);
                return 1;
            }
        }

        // Validate JFR file if provided
        if (jfrFile != null) {
            if (!Files.exists(jfrFile)) {
                sharedOptions.errorLog("JFR file not found: " + jfrFile);
                return 1;
            }
        }

        try {
            // Parse all dumps
            List<ThreadDump> dumps = parseDumps();
            if (dumps.isEmpty()) {
                sharedOptions.errorLog("No valid thread dumps found");
                return 1;
            }

            // Build analysis options from mixin
            AnalysisOptions options = sharedOptions.buildAnalysisOptions();

            // Create analysis context
            AnalysisContext context;
            if (jfrFile != null) {
                context = AnalysisContext.of(dumps, jfrFile, options);
            } else {
                context = AnalysisContext.of(dumps, options);
            }

            // Run analysis
            AnalysisEngine engine = createAnalysisEngine();
            AnalysisResult.CompositeResult result = engine.analyzeAll(context);

            // Add VerdictAnalyzer result if not already included
            VerdictAnalyzer verdictAnalyzer = new VerdictAnalyzer();
            VerdictAnalyzer.VerdictResult verdictResult = verdictAnalyzer.analyze(context);

            // Check for stalls and deadlocks
            boolean hasDeadlock = hasDeadlockDetected(result);
            boolean hasStall = hasStallDetected(verdictResult);

            // Output results using options from mixin
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

            // Determine exit code
            if (failOnDeadlock && hasDeadlock) {
                return 2;
            }
            if (failOnStall && hasStall) {
                return 1;
            }
            return 0;

        } catch (IOException e) {
            sharedOptions.errorLog("Reading files: " + e.getMessage());
            if (sharedOptions.isVerbose()) {
                e.printStackTrace();
            }
            return 1;
        } catch (Exception e) {
            sharedOptions.errorLog("During analysis: " + e.getMessage());
            if (sharedOptions.isVerbose()) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    private List<ThreadDump> parseDumps() throws IOException {
        List<ThreadDump> dumps = new ArrayList<>();
        ThreadDumpParser parser = new ThreadDumpParser();

        for (Path file : dumpFiles) {
            sharedOptions.verboseLog("Parsing: " + file);
            String content = Files.readString(file);
            ThreadDump dump = parser.parse(content);
            if (dump != null && !dump.threads().isEmpty()) {
                dumps.add(dump);
            } else {
                sharedOptions.verboseLog("Warning: No threads found in " + file);
            }
        }

        // Sort by timestamp if available
        dumps.sort(Comparator.comparing(
                d -> d.timestamp() != null ? d.timestamp() : java.time.Instant.EPOCH
        ));

        return dumps;
    }

    private AnalysisEngine createAnalysisEngine() {
        AnalysisEngine engine = AnalysisEngine.createDefault();

        // For quiet mode, only run essential analyzers
        if (sharedOptions.isQuiet() || mode == OutputMode.QUIET) {
            engine = AnalysisEngine.createEmpty();
            engine.register(new DeadlockAnalyzer());
            engine.register(new ThreadProgressAnalyzer());
        }

        // For short-stall mode, focus on stall-related analyzers
        if (mode == OutputMode.SHORT_STALL) {
            engine = AnalysisEngine.createEmpty();
            engine.register(new DeadlockAnalyzer());
            engine.register(new ThreadProgressAnalyzer());
            engine.register(new LockContentionAnalyzer());
            engine.register(new StackGroupAnalyzer());
        }

        return engine;
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
        // Quiet mode: only verdict
        if (sharedOptions.isQuiet() || mode == OutputMode.QUIET) {
            outputVerdict(verdict, options);
            return;
        }

        // Minimal mode: summary only
        if (mode == OutputMode.MINIMAL) {
            outputMinimal(verdict);
            return;
        }

        // Full output
        ViewRendererFactory.initialize();

        // Create composite result with verdict
        List<AnalysisResult> allResults = new ArrayList<>(result.getResults());
        allResults.add(verdict);
        AnalysisResult.CompositeResult fullResult = new AnalysisResult.CompositeResult(allResults);

        String output = ViewRendererFactory.render(fullResult, options);
        System.out.println(output);
    }

    private void outputVerdict(VerdictAnalyzer.VerdictResult verdict, OutputOptions options) {
        ViewRendererFactory.initialize();
        String output = ViewRendererFactory.render(verdict, options);
        System.out.println(output);
    }

    private void outputMinimal(VerdictAnalyzer.VerdictResult verdict) {
        // Print verdict summary
        System.out.println("=== Analysis Summary ===");
        System.out.println("Status: " + verdict.getStatus().getDescription());
        System.out.println("Severity: " + verdict.getSeverity());
        System.out.println();

        // Print key findings
        if (!verdict.getFindings().isEmpty()) {
            System.out.println("Key Findings:");
            int count = 0;
            for (AnalysisResult.Finding finding : verdict.getFindings()) {
                if (count++ >= 5) {
                    System.out.println("  ... and " + (verdict.getFindings().size() - 5) + " more");
                    break;
                }
                System.out.println("  [" + finding.severity() + "] " + finding.message());
            }
        }
    }

    private void saveResult(AnalysisResult result, Path file) throws IOException {
        OutputFormat format = OutputFormat.fromString(file.toString());
        if (format == OutputFormat.TEXT || format == OutputFormat.HTML) {
            format = OutputFormat.JSON; // Default to JSON for save
        }

        String extension = file.toString().toLowerCase();
        if (extension.endsWith(".yaml") || extension.endsWith(".yml")) {
            format = OutputFormat.YAML;
        } else if (extension.endsWith(".json")) {
            format = OutputFormat.JSON;
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