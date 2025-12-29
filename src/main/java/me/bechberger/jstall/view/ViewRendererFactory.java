package me.bechberger.jstall.view;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.view.views.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Factory for creating and selecting ViewRenderers.
 * Provides convenient access to the renderer registry with auto-initialization.
 */
public class ViewRendererFactory {

    private static volatile boolean initialized = false;
    private static final Object initLock = new Object();

    private ViewRendererFactory() {
        // Utility class
    }

    /**
     * Initialize the factory by registering all built-in renderers
     */
    public static void initialize() {
        if (initialized) return;

        synchronized (initLock) {
            if (initialized) return;

            ViewRendererRegistry registry = ViewRendererRegistry.getInstance();

            // Register specialized renderers (order matters - first registered has higher priority)
            registry.register(new VerdictView());  // Verdict first - most important summary
            registry.register(new DeadlockView());
            registry.register(new ThreadProgressView());
            registry.register(new StackGroupView());
            registry.register(new LockContentionView());
            registry.register(new GCActivityView());
            registry.register(new ThreadChurnView());
            registry.register(new ThreadPoolView());
            registry.register(new IOBlockView());
            registry.register(new SimilarStackView());
            registry.register(new JfrProfilingView());
            registry.register(new ClassLoadingView());
            registry.register(new JniView());
            registry.register(new MultiDumpTableView());
            registry.register(new CompositeView());

            // Register generic renderer as fallback
            registry.register(new GenericResultView());

            initialized = true;
        }
    }

    /**
     * Get the best renderer for a result type and format
     */
    @Nullable
    public static ViewRenderer getRenderer(@NotNull AnalysisResult result, @NotNull OutputFormat format) {
        initialize();
        return ViewRendererRegistry.getInstance().findBestRenderer(result, format);
    }

    /**
     * Get a renderer by name
     */
    @Nullable
    public static ViewRenderer getRenderer(@NotNull String name) {
        initialize();
        return ViewRendererRegistry.getInstance().getByName(name);
    }

    /**
     * Render a result using the best available renderer
     */
    @NotNull
    public static String render(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        initialize();

        ViewRenderer renderer = getRenderer(result, options.getFormat());
        if (renderer == null) {
            // Fallback to summary if no renderer found
            return result.getSummary();
        }

        return renderer.render(result, options);
    }

    /**
     * Render a result to a specific format
     */
    @NotNull
    public static String render(@NotNull AnalysisResult result, @NotNull OutputFormat format) {
        return render(result, OutputOptions.builder().format(format).build());
    }

    /**
     * Render a result as text
     */
    @NotNull
    public static String renderText(@NotNull AnalysisResult result) {
        return render(result, OutputFormat.TEXT);
    }

    /**
     * Render a result as text with color support
     */
    @NotNull
    public static String renderText(@NotNull AnalysisResult result, boolean colorEnabled) {
        OutputOptions options = OutputOptions.builder()
                .format(OutputFormat.TEXT)
                .colorEnabled(colorEnabled)
                .build();
        return render(result, options);
    }

    /**
     * Render a result as HTML
     */
    @NotNull
    public static String renderHtml(@NotNull AnalysisResult result) {
        return render(result, OutputFormat.HTML);
    }

    /**
     * Render a result as JSON
     */
    @NotNull
    public static String renderJson(@NotNull AnalysisResult result) {
        return render(result, OutputFormat.JSON);
    }

    /**
     * Render a result as YAML
     */
    @NotNull
    public static String renderYaml(@NotNull AnalysisResult result) {
        return render(result, OutputFormat.YAML);
    }

    /**
     * Check if the factory has been initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Reset the factory (mainly for testing)
     */
    public static void reset() {
        synchronized (initLock) {
            ViewRendererRegistry.getInstance().clear();
            initialized = false;
        }
    }
}