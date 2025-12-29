package me.bechberger.jstall.view;

import me.bechberger.jstall.analysis.AnalysisResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for mapping AnalysisResult types to their appropriate ViewRenderer implementations.
 * Supports automatic renderer discovery and fallback to generic renderers.
 */
public class ViewRendererRegistry {

    private static final ViewRendererRegistry INSTANCE = new ViewRendererRegistry();

    private final Map<Class<? extends AnalysisResult>, List<ViewRenderer>> renderersByType = new ConcurrentHashMap<>();
    private final Map<String, ViewRenderer> renderersByName = new ConcurrentHashMap<>();
    private final List<ViewRenderer> genericRenderers = new ArrayList<>();

    private ViewRendererRegistry() {
        // Private constructor for singleton
    }

    public static ViewRendererRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Register a renderer for specific result types
     */
    public void register(@NotNull ViewRenderer renderer) {
        renderersByName.put(renderer.getName(), renderer);

        Class<? extends AnalysisResult>[] resultTypes = renderer.getResultTypes();
        if (resultTypes == null || resultTypes.length == 0 ||
            (resultTypes.length == 1 && resultTypes[0] == AnalysisResult.class)) {
            // Generic renderer
            genericRenderers.add(renderer);
        } else {
            for (Class<? extends AnalysisResult> type : resultTypes) {
                renderersByType.computeIfAbsent(type, k -> new ArrayList<>()).add(renderer);
            }
        }
    }

    /**
     * Unregister a renderer
     */
    public void unregister(@NotNull ViewRenderer renderer) {
        renderersByName.remove(renderer.getName());
        genericRenderers.remove(renderer);
        renderersByType.values().forEach(list -> list.remove(renderer));
    }

    /**
     * Get a renderer by name
     */
    @Nullable
    public ViewRenderer getByName(@NotNull String name) {
        return renderersByName.get(name);
    }

    /**
     * Find renderers that can handle the given result type and format
     */
    @NotNull
    public List<ViewRenderer> findRenderers(@NotNull Class<? extends AnalysisResult> resultType,
                                            @NotNull OutputFormat format) {
        List<ViewRenderer> matches = new ArrayList<>();

        // Check specific type mappings
        List<ViewRenderer> typeRenderers = renderersByType.get(resultType);
        if (typeRenderers != null) {
            for (ViewRenderer renderer : typeRenderers) {
                if (renderer.supports(format)) {
                    matches.add(renderer);
                }
            }
        }

        // Check parent types
        Class<?> parentType = resultType.getSuperclass();
        while (parentType != null && AnalysisResult.class.isAssignableFrom(parentType)) {
            @SuppressWarnings("unchecked")
            List<ViewRenderer> parentRenderers = renderersByType.get((Class<? extends AnalysisResult>) parentType);
            if (parentRenderers != null) {
                for (ViewRenderer renderer : parentRenderers) {
                    if (renderer.supports(format) && !matches.contains(renderer)) {
                        matches.add(renderer);
                    }
                }
            }
            parentType = parentType.getSuperclass();
        }

        // Add generic renderers as fallback
        for (ViewRenderer renderer : genericRenderers) {
            if (renderer.supports(format) && !matches.contains(renderer)) {
                matches.add(renderer);
            }
        }

        return matches;
    }

    /**
     * Find the best renderer for the given result type and format
     */
    @Nullable
    public ViewRenderer findBestRenderer(@NotNull Class<? extends AnalysisResult> resultType,
                                         @NotNull OutputFormat format) {
        List<ViewRenderer> renderers = findRenderers(resultType, format);
        return renderers.isEmpty() ? null : renderers.get(0);
    }

    /**
     * Find the best renderer for a result instance
     */
    @Nullable
    public ViewRenderer findBestRenderer(@NotNull AnalysisResult result, @NotNull OutputFormat format) {
        return findBestRenderer(result.getClass(), format);
    }

    /**
     * Get all registered renderers
     */
    @NotNull
    public Collection<ViewRenderer> getAllRenderers() {
        return Collections.unmodifiableCollection(renderersByName.values());
    }

    /**
     * Get all renderer names
     */
    @NotNull
    public Set<String> getRendererNames() {
        return Collections.unmodifiableSet(renderersByName.keySet());
    }

    /**
     * Clear all registrations
     */
    public void clear() {
        renderersByType.clear();
        renderersByName.clear();
        genericRenderers.clear();
    }
}