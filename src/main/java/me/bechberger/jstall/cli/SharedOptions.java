package me.bechberger.jstall.cli;

import me.bechberger.jstall.analysis.AnalysisOptions;
import me.bechberger.jstall.view.OutputFormat;
import me.bechberger.jstall.view.OutputOptions;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared command options mixin.
 * Use with @Mixin annotation in commands.
 */
public class SharedOptions {

    @Option(names = {"--color"}, description = "Force colored output", negatable = true)
    private Boolean colorEnabled = null;

    @Option(names = {"-o", "--output"}, description = "Output format: text, json, yaml, html (default: ${DEFAULT-VALUE})")
    private String outputFormat = "text";

    @Option(names = {"--ignore"}, description = "Regex pattern(s) for threads to ignore", split = ",")
    private List<String> ignorePatterns = new ArrayList<>();

    @Option(names = {"--include-gc"}, description = "Include GC threads in analysis")
    private boolean includeGc = false;

    @Option(names = {"--include-vm"}, description = "Include VM internal threads in analysis")
    private boolean includeVm = false;

    @Option(names = {"--include-daemon"}, description = "Include daemon threads in analysis")
    private boolean includeDaemon = true;

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose output")
    private boolean verbose = false;

    @Option(names = {"-q", "--quiet"}, description = "Minimal output (only verdict)")
    private boolean quiet = false;

    // Getters

    public Boolean getColorEnabled() {
        return colorEnabled;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public List<String> getIgnorePatterns() {
        return ignorePatterns;
    }

    public boolean isIncludeGc() {
        return includeGc;
    }

    public boolean isIncludeVm() {
        return includeVm;
    }

    public boolean isIncludeDaemon() {
        return includeDaemon;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public boolean isQuiet() {
        return quiet;
    }

    /**
     * Build AnalysisOptions from shared options
     */
    public AnalysisOptions buildAnalysisOptions() {
        AnalysisOptions.Builder builder = AnalysisOptions.builder()
                .includeDaemon(includeDaemon)
                .includeGc(includeGc)
                .includeVm(includeVm);

        for (String pattern : ignorePatterns) {
            try {
                builder.addIgnorePattern(pattern);
            } catch (Exception e) {
                System.err.println("Warning: Invalid regex pattern: " + pattern);
            }
        }

        return builder.build();
    }

    /**
     * Build OutputOptions from shared options
     */
    public OutputOptions buildOutputOptions() {
        OutputOptions.Builder builder = OutputOptions.builder()
                .format(OutputFormat.fromString(outputFormat))
                .verbose(verbose);

        if (colorEnabled != null) {
            if (colorEnabled) {
                builder.forceColor();
            } else {
                builder.noColor();
            }
        }

        return builder.build();
    }

    /**
     * Print verbose message if verbose mode is enabled
     */
    public void verboseLog(String message) {
        if (verbose) {
            System.err.println(message);
        }
    }

    /**
     * Print error message
     */
    public void errorLog(String message) {
        System.err.println("Error: " + message);
    }

    /**
     * Print warning message
     */
    public void warnLog(String message) {
        System.err.println("Warning: " + message);
    }
}