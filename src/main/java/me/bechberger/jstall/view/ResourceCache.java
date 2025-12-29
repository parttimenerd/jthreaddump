package me.bechberger.jstall.view;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages caching and embedding of external resources (JS, CSS) for HTML output.
 * Downloads resources once and caches them locally for offline use.
 * Embeds resources directly into HTML output for single-file reports.
 */
public class ResourceCache {

    private static volatile ResourceCache instance;
    private static final String CACHE_DIR_NAME = ".jstall-cache";
    private static final Duration CACHE_EXPIRY = Duration.ofDays(7);
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    // In-memory cache for current session
    private final Map<String, CachedResource> memoryCache = new ConcurrentHashMap<>();
    private final Path cacheDir;

    // Known external resources with fallback URLs
    private static final Map<String, ResourceInfo> KNOWN_RESOURCES = Map.of(
            "chart.js", new ResourceInfo(
                    "https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js",
                    "chart.umd.min.js",
                    ResourceType.JAVASCRIPT
            ),
            "chart.js-cdn", new ResourceInfo(
                    "https://cdn.jsdelivr.net/npm/chart.js",
                    "chart.cdn.min.js",
                    ResourceType.JAVASCRIPT
            )
    );

    // Bundled fallback for Chart.js (minimal version for offline use)
    private static final String CHART_JS_FALLBACK_NOTICE =
            "/* Chart.js could not be loaded. Charts will not render. */\n" +
            "console.warn('Chart.js not available - charts disabled');\n" +
            "window.Chart = function() { console.warn('Chart.js not loaded'); };\n";

    private ResourceCache() {
        // Determine cache directory
        String userHome = System.getProperty("user.home");
        this.cacheDir = Path.of(userHome, CACHE_DIR_NAME);
        ensureCacheDir();
    }

    public static ResourceCache getInstance() {
        if (instance == null) {
            synchronized (ResourceCache.class) {
                if (instance == null) {
                    instance = new ResourceCache();
                }
            }
        }
        return instance;
    }

    /**
     * Get Chart.js content for embedding in HTML
     */
    @NotNull
    public String getChartJs() {
        return getResource("chart.js");
    }

    /**
     * Get a resource by name, downloading and caching if needed
     */
    @NotNull
    public String getResource(@NotNull String name) {
        // Check memory cache first
        CachedResource cached = memoryCache.get(name);
        if (cached != null && !cached.isExpired()) {
            return cached.content;
        }

        // Check disk cache
        ResourceInfo info = KNOWN_RESOURCES.get(name);
        if (info != null) {
            String diskContent = loadFromDisk(info.cacheFileName);
            if (diskContent != null) {
                memoryCache.put(name, new CachedResource(diskContent, Instant.now()));
                return diskContent;
            }

            // Download and cache
            String downloaded = downloadResource(info.url);
            if (downloaded != null) {
                saveToDisk(info.cacheFileName, downloaded);
                memoryCache.put(name, new CachedResource(downloaded, Instant.now()));
                return downloaded;
            }
        }

        // Return fallback
        return getFallback(name);
    }

    /**
     * Generate HTML script tag with embedded Chart.js
     */
    @NotNull
    public String getChartJsScriptTag() {
        String chartJs = getChartJs();
        return "<script>\n" + chartJs + "\n</script>";
    }

    /**
     * Get the interactive features JavaScript
     */
    @NotNull
    public String getInteractiveScript() {
        // Load from bundled resource
        try (var is = getClass().getResourceAsStream("/templates/html/partials/interactive.js")) {
            if (is == null) {
                return "// Interactive script not found";
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "// Error loading interactive script: " + e.getMessage();
        }
    }

    /**
     * Get the advanced visualizations JavaScript (D3.js-based)
     */
    @NotNull
    public String getVisualizationsScript() {
        // Load from bundled resource
        try (var is = getClass().getResourceAsStream("/templates/html/partials/visualizations.js")) {
            if (is == null) {
                return "// Visualizations script not found";
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "// Error loading visualizations script: " + e.getMessage();
        }
    }

    /**
     * Check if resources are cached and available offline
     */
    public boolean isChartJsCached() {
        ResourceInfo info = KNOWN_RESOURCES.get("chart.js");
        if (info == null) return false;

        Path cachePath = cacheDir.resolve(info.cacheFileName);
        return Files.exists(cachePath);
    }

    /**
     * Pre-download all known resources
     */
    public void preloadResources() {
        for (String name : KNOWN_RESOURCES.keySet()) {
            getResource(name);
        }
    }

    /**
     * Clear the resource cache
     */
    public void clearCache() {
        memoryCache.clear();
        try {
            if (Files.exists(cacheDir)) {
                Files.list(cacheDir).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    private void ensureCacheDir() {
        try {
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir);
            }
        } catch (IOException e) {
            // Ignore - will work without disk cache
        }
    }

    @Nullable
    private String loadFromDisk(@NotNull String fileName) {
        Path cachePath = cacheDir.resolve(fileName);
        Path metaPath = cacheDir.resolve(fileName + ".meta");

        try {
            if (!Files.exists(cachePath)) {
                return null;
            }

            // Check if cache is expired
            if (Files.exists(metaPath)) {
                String meta = Files.readString(metaPath);
                Instant cachedAt = Instant.parse(meta.trim());
                if (Instant.now().isAfter(cachedAt.plus(CACHE_EXPIRY))) {
                    // Cache expired
                    Files.deleteIfExists(cachePath);
                    Files.deleteIfExists(metaPath);
                    return null;
                }
            }

            return Files.readString(cachePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveToDisk(@NotNull String fileName, @NotNull String content) {
        try {
            Path cachePath = cacheDir.resolve(fileName);
            Path metaPath = cacheDir.resolve(fileName + ".meta");

            Files.writeString(cachePath, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(metaPath, Instant.now().toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // Ignore - will work without disk cache
        }
    }

    @Nullable
    private String downloadResource(@NotNull String urlString) {
        try {
            URL url = URI.create(urlString).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "jstall/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return null;
            }

            try (InputStream is = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    @NotNull
    private String getFallback(@NotNull String name) {
        if (name.startsWith("chart")) {
            return CHART_JS_FALLBACK_NOTICE;
        }
        return "/* Resource not available: " + name + " */";
    }

    /**
     * Generate cache status information
     */
    @NotNull
    public String getCacheStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Resource Cache Status:\n");
        sb.append("  Cache Directory: ").append(cacheDir).append("\n");
        sb.append("  Cache Expiry: ").append(CACHE_EXPIRY.toDays()).append(" days\n");
        sb.append("  Resources:\n");

        for (Map.Entry<String, ResourceInfo> entry : KNOWN_RESOURCES.entrySet()) {
            String name = entry.getKey();
            ResourceInfo info = entry.getValue();
            Path cachePath = cacheDir.resolve(info.cacheFileName);
            boolean cached = Files.exists(cachePath);
            String status = cached ? "CACHED" : "NOT CACHED";
            sb.append("    - ").append(name).append(": ").append(status).append("\n");
        }

        return sb.toString();
    }

    // Inner classes

    private record ResourceInfo(String url, String cacheFileName, ResourceType type) {}

    private enum ResourceType {
        JAVASCRIPT, CSS
    }

    private record CachedResource(String content, Instant cachedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plus(CACHE_EXPIRY));
        }
    }
}