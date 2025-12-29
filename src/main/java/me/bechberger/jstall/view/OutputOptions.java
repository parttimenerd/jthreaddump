package me.bechberger.jstall.view;

import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;

/**
 * Options for controlling output rendering.
 */
public class OutputOptions {

    private OutputFormat format = OutputFormat.TEXT;
    private boolean colorEnabled;
    private ColorScheme colorScheme = ColorScheme.DEFAULT;
    private int maxThreads = 50;
    private int maxStackDepth = 20;
    private boolean verbose = false;
    private boolean showTimestamps = true;
    private boolean showThreadIds = true;

    private OutputOptions() {
        // Auto-detect color support
        this.colorEnabled = detectColorSupport();
    }

    public static OutputOptions defaults() {
        return new OutputOptions();
    }

    public static Builder builder() {
        return new Builder();
    }

    private static boolean detectColorSupport() {
        // Check environment variables
        String term = System.getenv("TERM");
        String colorTerm = System.getenv("COLORTERM");
        String noColor = System.getenv("NO_COLOR");

        if (noColor != null && !noColor.isEmpty()) {
            return false;
        }

        if (colorTerm != null && !colorTerm.isEmpty()) {
            return true;
        }

        if (term != null) {
            return term.contains("color") || term.contains("xterm") ||
                   term.contains("256") || term.contains("ansi");
        }

        // Check if stdout is a terminal
        return System.console() != null;
    }

    // Getters
    public OutputFormat getFormat() { return format; }
    public boolean isColorEnabled() { return colorEnabled; }
    public ColorScheme getColorScheme() { return colorScheme; }
    public int getMaxThreads() { return maxThreads; }
    public int getMaxStackDepth() { return maxStackDepth; }
    public boolean isVerbose() { return verbose; }
    public boolean isShowTimestamps() { return showTimestamps; }
    public boolean isShowThreadIds() { return showThreadIds; }

    /**
     * Color scheme for CLI output
     */
    public enum ColorScheme {
        DEFAULT,
        LIGHT,   // For light terminals
        DARK,    // For dark terminals
        NONE     // No colors
    }

    /**
     * ANSI color helpers
     */
    public static class Colors {
        public static String red(String text, OutputOptions options) {
            if (!options.isColorEnabled()) return text;
            return Ansi.ansi().fgRed().a(text).reset().toString();
        }

        public static String green(String text, OutputOptions options) {
            if (!options.isColorEnabled()) return text;
            return Ansi.ansi().fgGreen().a(text).reset().toString();
        }

        public static String yellow(String text, OutputOptions options) {
            if (!options.isColorEnabled()) return text;
            return Ansi.ansi().fgYellow().a(text).reset().toString();
        }

        public static String blue(String text, OutputOptions options) {
            if (!options.isColorEnabled()) return text;
            return Ansi.ansi().fgBlue().a(text).reset().toString();
        }

        public static String cyan(String text, OutputOptions options) {
            if (!options.isColorEnabled()) return text;
            return Ansi.ansi().fgCyan().a(text).reset().toString();
        }

        public static String magenta(String text, OutputOptions options) {
            if (!options.isColorEnabled()) return text;
            return Ansi.ansi().fgMagenta().a(text).reset().toString();
        }

        public static String bold(String text, OutputOptions options) {
            if (!options.isColorEnabled()) return text;
            return Ansi.ansi().bold().a(text).reset().toString();
        }

        public static String dim(String text, OutputOptions options) {
            if (!options.isColorEnabled()) return text;
            return Ansi.ansi().a(Ansi.Attribute.INTENSITY_FAINT).a(text).reset().toString();
        }

        // Semantic colors
        public static String error(String text, OutputOptions options) {
            return red(text, options);
        }

        public static String warning(String text, OutputOptions options) {
            return yellow(text, options);
        }

        public static String success(String text, OutputOptions options) {
            return green(text, options);
        }

        public static String info(String text, OutputOptions options) {
            return cyan(text, options);
        }

        public static String header(String text, OutputOptions options) {
            return bold(text, options);
        }

        // Thread state colors
        public static String threadState(Thread.State state, OutputOptions options) {
            if (state == null) return "UNKNOWN";
            String text = state.toString();
            return switch (state) {
                case RUNNABLE -> green(text, options);
                case BLOCKED -> red(text, options);
                case WAITING, TIMED_WAITING -> yellow(text, options);
                case NEW -> cyan(text, options);
                case TERMINATED -> dim(text, options);
            };
        }

        // Severity colors
        public static String severity(me.bechberger.jstall.analysis.AnalysisResult.Severity severity,
                                       String text, OutputOptions options) {
            return switch (severity) {
                case CRITICAL -> bold(red(text, options), options);
                case ERROR -> red(text, options);
                case WARNING -> yellow(text, options);
                case INFO -> cyan(text, options);
                case OK -> green(text, options);
            };
        }
    }

    public static class Builder {
        private final OutputOptions options = new OutputOptions();

        public Builder format(OutputFormat format) {
            options.format = format;
            return this;
        }

        public Builder colorEnabled(boolean enabled) {
            options.colorEnabled = enabled;
            return this;
        }

        public Builder forceColor() {
            options.colorEnabled = true;
            return this;
        }

        public Builder noColor() {
            options.colorEnabled = false;
            return this;
        }

        public Builder colorScheme(ColorScheme scheme) {
            options.colorScheme = scheme;
            if (scheme == ColorScheme.NONE) {
                options.colorEnabled = false;
            }
            return this;
        }

        public Builder maxThreads(int max) {
            options.maxThreads = max;
            return this;
        }

        public Builder maxStackDepth(int max) {
            options.maxStackDepth = max;
            return this;
        }

        public Builder verbose(boolean verbose) {
            options.verbose = verbose;
            return this;
        }

        public Builder showTimestamps(boolean show) {
            options.showTimestamps = show;
            return this;
        }

        public Builder showThreadIds(boolean show) {
            options.showThreadIds = show;
            return this;
        }

        public OutputOptions build() {
            return options;
        }
    }
}