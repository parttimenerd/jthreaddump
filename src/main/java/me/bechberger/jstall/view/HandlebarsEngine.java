package me.bechberger.jstall.view;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import com.github.jknack.handlebars.io.TemplateLoader;
import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.ProgressClassification;
import org.fusesource.jansi.Ansi;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton wrapper around Handlebars template engine.
 * Provides template loading, caching, and custom helpers for CLI/HTML output.
 */
public class HandlebarsEngine {

    private static volatile HandlebarsEngine instance;

    private final Handlebars cliHandlebars;
    private final Handlebars htmlHandlebars;
    private final Map<String, Template> templateCache = new ConcurrentHashMap<>();

    private HandlebarsEngine() {
        // Set up template loaders for classpath resources
        TemplateLoader cliLoader = new ClassPathTemplateLoader("/templates/cli", ".hbs");
        TemplateLoader htmlLoader = new ClassPathTemplateLoader("/templates/html", ".hbs");

        // Create separate Handlebars instances for CLI and HTML
        this.cliHandlebars = new Handlebars(cliLoader);
        this.htmlHandlebars = new Handlebars(htmlLoader);

        // Register custom helpers on both instances
        registerHelpers(cliHandlebars);
        registerHelpers(htmlHandlebars);
    }

    public static HandlebarsEngine getInstance() {
        if (instance == null) {
            synchronized (HandlebarsEngine.class) {
                if (instance == null) {
                    instance = new HandlebarsEngine();
                }
            }
        }
        return instance;
    }

    /**
     * Get the Handlebars instance for the specified format
     */
    private Handlebars getHandlebarsForFormat(OutputFormat format) {
        return switch (format) {
            case HTML -> htmlHandlebars;
            default -> cliHandlebars;
        };
    }

    /**
     * Get a compiled template by name for the specified format
     */
    public Template getTemplate(String name, OutputFormat format) throws IOException {
        String cacheKey = format.name() + "/" + name;
        return templateCache.computeIfAbsent(cacheKey, k -> {
            try {
                return getHandlebarsForFormat(format).compile(name);
            } catch (IOException e) {
                throw new RuntimeException("Failed to compile template: " + k, e);
            }
        });
    }

    /**
     * Get a compiled template by name (defaults to CLI/TEXT format)
     */
    public Template getTemplate(String name) throws IOException {
        return getTemplate(name, OutputFormat.TEXT);
    }

    /**
     * Get a template from a specific path (for custom template locations)
     */
    public Template getTemplateFromPath(String basePath, String name) throws IOException {
        String cacheKey = basePath + "/" + name;
        return templateCache.computeIfAbsent(cacheKey, k -> {
            try {
                TemplateLoader loader = new ClassPathTemplateLoader(basePath, ".hbs");
                Handlebars hb = new Handlebars(loader);
                registerHelpers(hb);
                return hb.compile(name);
            } catch (IOException e) {
                throw new RuntimeException("Failed to compile template: " + k, e);
            }
        });
    }

    /**
     * Render a template with the given context (defaults to CLI/TEXT format)
     */
    public String render(String templateName, Object context) throws IOException {
        Template template = getTemplate(templateName);
        return template.apply(context);
    }

    /**
     * Render a template with the given context and output options
     */
    public String render(String templateName, Object context, OutputOptions options) throws IOException {
        // Wrap context with options
        Map<String, Object> fullContext = new java.util.HashMap<>();
        if (context instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> contextMap = (Map<String, Object>) context;
            fullContext.putAll(contextMap);
        } else {
            fullContext.put("data", context);
        }
        fullContext.put("options", options);
        fullContext.put("colorEnabled", options.isColorEnabled());
        fullContext.put("verbose", options.isVerbose());

        Template template = getTemplate(templateName, options.getFormat());
        return template.apply(fullContext);
    }

    private void registerHelpers(Handlebars hb) {
        // Color helpers
        hb.registerHelper("red", colorHelper(Ansi.Color.RED));
        hb.registerHelper("green", colorHelper(Ansi.Color.GREEN));
        hb.registerHelper("yellow", colorHelper(Ansi.Color.YELLOW));
        hb.registerHelper("blue", colorHelper(Ansi.Color.BLUE));
        hb.registerHelper("cyan", colorHelper(Ansi.Color.CYAN));
        hb.registerHelper("magenta", colorHelper(Ansi.Color.MAGENTA));

        // Bold helper
        hb.registerHelper("bold", (Object text, Options options) -> {
            Boolean colorEnabled = getColorEnabled(options);
            String textStr = text != null ? text.toString() : "";
            if (Boolean.TRUE.equals(colorEnabled)) {
                return Ansi.ansi().bold().a(textStr).reset().toString();
            }
            return textStr;
        });

        // Dim helper
        hb.registerHelper("dim", (Object text, Options options) -> {
            Boolean colorEnabled = getColorEnabled(options);
            String textStr = text != null ? text.toString() : "";
            if (Boolean.TRUE.equals(colorEnabled)) {
                return Ansi.ansi().a(Ansi.Attribute.INTENSITY_FAINT).a(textStr).reset().toString();
            }
            return textStr;
        });

        // Semantic color helpers
        hb.registerHelper("error", colorHelper(Ansi.Color.RED));
        hb.registerHelper("warning", colorHelper(Ansi.Color.YELLOW));
        hb.registerHelper("success", colorHelper(Ansi.Color.GREEN));
        hb.registerHelper("info", colorHelper(Ansi.Color.CYAN));

        // Thread state color helper
        hb.registerHelper("threadStateColor", (Thread.State state, Options options) -> {
            Boolean colorEnabled = getColorEnabled(options);
            if (state == null) return "UNKNOWN";
            String text = state.toString();
            if (!Boolean.TRUE.equals(colorEnabled)) return text;

            return switch (state) {
                case RUNNABLE -> Ansi.ansi().fgGreen().a(text).reset().toString();
                case BLOCKED -> Ansi.ansi().fgRed().a(text).reset().toString();
                case WAITING, TIMED_WAITING -> Ansi.ansi().fgYellow().a(text).reset().toString();
                case NEW -> Ansi.ansi().fgCyan().a(text).reset().toString();
                case TERMINATED -> Ansi.ansi().a(Ansi.Attribute.INTENSITY_FAINT).a(text).reset().toString();
            };
        });

        // Progress classification color helper
        hb.registerHelper("progressColor", (ProgressClassification progress, Options options) -> {
            Boolean colorEnabled = getColorEnabled(options);
            if (progress == null) return "UNKNOWN";
            String text = progress.toString();
            if (!Boolean.TRUE.equals(colorEnabled)) return text;

            return switch (progress) {
                case ACTIVE -> Ansi.ansi().fgGreen().a(text).reset().toString();
                case RUNNABLE_NO_PROGRESS, STUCK -> Ansi.ansi().fgRed().a(text).reset().toString();
                case BLOCKED_ON_LOCK -> Ansi.ansi().fgRed().bold().a(text).reset().toString();
                case WAITING_EXPECTED, TIMED_WAITING_EXPECTED -> Ansi.ansi().fgYellow().a(text).reset().toString();
                case RESTARTED, NEW -> Ansi.ansi().fgCyan().a(text).reset().toString();
                case TERMINATED, IGNORED -> Ansi.ansi().a(Ansi.Attribute.INTENSITY_FAINT).a(text).reset().toString();
                case UNKNOWN -> text;
            };
        });

        // Severity color helper
        hb.registerHelper("severityColor", (AnalysisResult.Severity severity, Options options) -> {
            Boolean colorEnabled = getColorEnabled(options);
            if (severity == null) return "OK";
            String text = severity.toString();
            if (!Boolean.TRUE.equals(colorEnabled)) return text;

            return switch (severity) {
                case CRITICAL -> Ansi.ansi().bold().fgRed().a(text).reset().toString();
                case ERROR -> Ansi.ansi().fgRed().a(text).reset().toString();
                case WARNING -> Ansi.ansi().fgYellow().a(text).reset().toString();
                case INFO -> Ansi.ansi().fgCyan().a(text).reset().toString();
                case OK -> Ansi.ansi().fgGreen().a(text).reset().toString();
            };
        });

        // Formatting helpers
        hb.registerHelper("pad", (Object text, Options options) -> {
            int width = options.hash("width", 20);
            String align = options.hash("align", "left");
            String textStr = text != null ? text.toString() : "";

            return switch (align) {
                case "right" -> String.format("%" + width + "s", textStr);
                case "center" -> {
                    int padding = (width - textStr.length()) / 2;
                    yield " ".repeat(Math.max(0, padding)) + textStr +
                          " ".repeat(Math.max(0, width - textStr.length() - padding));
                }
                default -> String.format("%-" + width + "s", textStr);
            };
        });

        // Repeat helper (for creating separator lines)
        hb.registerHelper("repeat", (Object text, Options options) -> {
            int count = options.hash("count", 1);
            String textStr = text != null ? text.toString() : "";
            return textStr.repeat(count);
        });

        // Conditional helpers
        hb.registerHelper("ifEq", (Object a, Options options) -> {
            Object b = options.param(0, null);
            if (a != null && a.equals(b)) {
                return options.fn();
            }
            return options.inverse();
        });

        hb.registerHelper("ifGt", (Number a, Options options) -> {
            Number b = options.param(0, 0);
            if (a != null && a.doubleValue() > b.doubleValue()) {
                return options.fn();
            }
            return options.inverse();
        });

        hb.registerHelper("ifGte", (Number a, Options options) -> {
            Number b = options.param(0, 0);
            if (a != null && a.doubleValue() >= b.doubleValue()) {
                return options.fn();
            }
            return options.inverse();
        });

        // Duration formatting (use US locale for consistent decimal separator)
        hb.registerHelper("formatDuration", (Long millis, Options options) -> {
            if (millis == null) return "N/A";
            if (millis < 1000) return millis + "ms";
            if (millis < 60000) return String.format(java.util.Locale.US, "%.1fs", millis / 1000.0);
            if (millis < 3600000) return String.format(java.util.Locale.US, "%.1fm", millis / 60000.0);
            return String.format(java.util.Locale.US, "%.1fh", millis / 3600000.0);
        });

        // Percentage formatting (use US locale for consistent decimal separator)
        hb.registerHelper("formatPercent", (Number value, Options options) -> {
            if (value == null) return "0%";
            return String.format(java.util.Locale.US, "%.1f%%", value.doubleValue() * 100);
        });

        // Truncate helper
        hb.registerHelper("truncate", (Object text, Options options) -> {
            int maxLength = options.hash("length", 50);
            String suffix = options.hash("suffix", "...");
            String textStr = text != null ? text.toString() : "";

            if (textStr.length() <= maxLength) return textStr;
            return textStr.substring(0, maxLength - suffix.length()) + suffix;
        });

        // Index helper for arrays
        hb.registerHelper("addOne", (Integer index, Options options) -> index + 1);

        hb.registerHelper("subtract", (Integer a, Options options) -> {
            Integer b = options.param(0, 1);
            return a - b;
        });

        // Lowercase helper (for CSS classes, etc.)
        hb.registerHelper("lowercase", (Object text, Options options) -> {
            if (text == null) return "";
            return text.toString().toLowerCase();
        });

        // Uppercase helper
        hb.registerHelper("uppercase", (Object text, Options options) -> {
            if (text == null) return "";
            return text.toString().toUpperCase();
        });

        // JSON helper for debugging
        hb.registerHelper("json", (Object obj, Options options) -> {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                return mapper.writeValueAsString(obj);
            } catch (Exception e) {
                return obj != null ? obj.toString() : "null";
            }
        });

        // Trend helpers
        hb.registerHelper("trendArrow", (Object trend, Options options) -> {
            if (trend == null) return "→";
            String trendStr = trend.toString().toUpperCase();
            return switch (trendStr) {
                case "INCREASING", "GROWING", "UP" -> "↑";
                case "DECREASING", "SHRINKING", "DOWN" -> "↓";
                case "STABLE", "UNCHANGED" -> "→";
                case "OSCILLATING" -> "↕";
                default -> "→";
            };
        });

        hb.registerHelper("trendColor", (Object trend, Options options) -> {
            Boolean colorEnabled = getColorEnabled(options);
            if (trend == null) return "";

            String trendStr = trend.toString().toUpperCase();
            String arrow = switch (trendStr) {
                case "INCREASING", "GROWING", "UP" -> "↑";
                case "DECREASING", "SHRINKING", "DOWN" -> "↓";
                case "STABLE", "UNCHANGED" -> "→";
                case "OSCILLATING" -> "↕";
                default -> "→";
            };

            if (!colorEnabled) return arrow;

            return switch (trendStr) {
                case "INCREASING", "GROWING", "UP" ->
                    Ansi.ansi().fgYellow().a(arrow).reset().toString();
                case "DECREASING", "SHRINKING", "DOWN" ->
                    Ansi.ansi().fgGreen().a(arrow).reset().toString();
                case "STABLE", "UNCHANGED" ->
                    Ansi.ansi().fgCyan().a(arrow).reset().toString();
                case "OSCILLATING" ->
                    Ansi.ansi().fgMagenta().a(arrow).reset().toString();
                default -> arrow;
            };
        });

        hb.registerHelper("changeIndicator", (Number change, Options options) -> {
            if (change == null) return "→";
            double changeVal = change.doubleValue();

            if (Math.abs(changeVal) < 0.01) {
                return "→";
            } else if (changeVal > 0) {
                return "+" + String.format(java.util.Locale.US, "%.1f", changeVal) + " ↑";
            } else {
                return String.format(java.util.Locale.US, "%.1f", changeVal) + " ↓";
            }
        });

        hb.registerHelper("changeColor", (Number change, Options options) -> {
            Boolean colorEnabled = getColorEnabled(options);
            if (change == null) return "→";

            double changeVal = change.doubleValue();
            String indicator = "";

            if (Math.abs(changeVal) < 0.01) {
                indicator = "→";
            } else if (changeVal > 0) {
                indicator = "+" + String.format(java.util.Locale.US, "%.1f", changeVal) + " ↑";
            } else {
                indicator = String.format(java.util.Locale.US, "%.1f", changeVal) + " ↓";
            }

            if (!Boolean.TRUE.equals(colorEnabled)) return indicator;

            if (Math.abs(changeVal) < 0.01) {
                return Ansi.ansi().fgCyan().a(indicator).reset().toString();
            } else if (changeVal > 0) {
                return Ansi.ansi().fgYellow().a(indicator).reset().toString();
            } else {
                return Ansi.ansi().fgGreen().a(indicator).reset().toString();
            }
        });

        hb.registerHelper("percentChange", (Number from, Options options) -> {
            Number to = options.param(0);
            if (from == null || to == null) return "0%";

            double fromVal = from.doubleValue();
            double toVal = to.doubleValue();

            if (fromVal == 0) {
                return toVal > 0 ? "∞%" : "0%";
            }

            double change = ((toVal - fromVal) / Math.abs(fromVal)) * 100;

            if (change > 0) {
                return "+" + String.format(java.util.Locale.US, "%.1f%%", change);
            } else {
                return String.format(java.util.Locale.US, "%.1f%%", change);
            }
        });

        hb.registerHelper("trendBadge", (Object trend, Options options) -> {
            if (trend == null) return "STABLE";
            String trendStr = trend.toString().toUpperCase();

            return switch (trendStr) {
                case "INCREASING", "GROWING" -> "GROWING ↑";
                case "DECREASING", "SHRINKING" -> "SHRINKING ↓";
                case "STABLE", "UNCHANGED" -> "STABLE →";
                case "OSCILLATING" -> "OSCILLATING ↕";
                case "STUCK" -> "STUCK ⚠";
                case "PERSISTENT" -> "PERSISTENT 🔴";
                default -> trendStr;
            };
        });

        hb.registerHelper("formatBytes", (Number bytes, Options options) -> {
            if (bytes == null) return "0 B";
            long b = bytes.longValue();

            if (b < 1024) return b + " B";
            if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
            if (b < 1024 * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024));
            return String.format(java.util.Locale.US, "%.1f GB", b / (1024.0 * 1024 * 1024));
        });

        hb.registerHelper("formatNumber", (Number num, Options options) -> {
            if (num == null) return "0";
            return String.format(java.util.Locale.US, "%,d", num.longValue());
        });
    }

    private Helper<Object> colorHelper(Ansi.Color color) {
        return (Object text, Options options) -> {
            Boolean colorEnabled = getColorEnabled(options);
            String textStr = text != null ? text.toString() : "";
            if (Boolean.TRUE.equals(colorEnabled)) {
                return Ansi.ansi().fg(color).a(textStr).reset().toString();
            }
            return textStr;
        };
    }

    private Boolean getColorEnabled(Options options) {
        // Check options hash first
        Boolean colorParam = options.hash("color");
        if (colorParam != null) return colorParam;

        // Check context
        Object colorEnabled = options.context.get("colorEnabled");
        if (colorEnabled instanceof Boolean) return (Boolean) colorEnabled;

        // Check nested options object
        Object opts = options.context.get("options");
        if (opts instanceof OutputOptions) {
            return ((OutputOptions) opts).isColorEnabled();
        }

        return false;
    }

    private void copyHelpers(Handlebars target) {
        registerHelpers(target);
    }

    /**
     * Clear the template cache (useful for testing or hot-reload scenarios)
     */
    public void clearCache() {
        templateCache.clear();
    }

    /**
     * Get the underlying Handlebars instance for CLI templates
     */
    public Handlebars getHandlebars() {
        return cliHandlebars;
    }

    /**
     * Get the underlying Handlebars instance for HTML templates
     */
    public Handlebars getHtmlHandlebars() {
        return htmlHandlebars;
    }
}