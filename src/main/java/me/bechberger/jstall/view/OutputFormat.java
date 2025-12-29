package me.bechberger.jstall.view;

/**
 * Output format for rendering analysis results.
 */
public enum OutputFormat {
    /** Plain text for CLI (with optional ANSI colors) */
    TEXT,
    /** HTML for web viewing */
    HTML,
    /** JSON for machine processing */
    JSON,
    /** YAML for human-readable machine format */
    YAML;

    /**
     * Parse a format from string (case-insensitive)
     */
    public static OutputFormat fromString(String format) {
        if (format == null) {
            return TEXT;
        }
        return switch (format.toLowerCase()) {
            case "text", "txt", "cli" -> TEXT;
            case "html", "htm" -> HTML;
            case "json" -> JSON;
            case "yaml", "yml" -> YAML;
            default -> TEXT;
        };
    }

    /**
     * Get file extension for this format
     */
    public String getExtension() {
        return switch (this) {
            case TEXT -> "txt";
            case HTML -> "html";
            case JSON -> "json";
            case YAML -> "yaml";
        };
    }

    /**
     * Get MIME type for this format
     */
    public String getMimeType() {
        return switch (this) {
            case TEXT -> "text/plain";
            case HTML -> "text/html";
            case JSON -> "application/json";
            case YAML -> "application/x-yaml";
        };
    }
}