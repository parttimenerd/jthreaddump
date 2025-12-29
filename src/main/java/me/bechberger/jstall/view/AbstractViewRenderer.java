package me.bechberger.jstall.view;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.bechberger.jstall.analysis.AnalysisResult;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base class for view renderers providing common functionality.
 * Handles JSON and YAML serialization via Jackson; subclasses implement TEXT and HTML.
 */
public abstract class AbstractViewRenderer implements ViewRenderer {

    private static final ObjectMapper JSON_MAPPER = createJsonMapper();
    private static final ObjectMapper YAML_MAPPER = createYamlMapper();

    private final String name;

    protected AbstractViewRenderer(String name) {
        this.name = name;
    }

    private static ObjectMapper createJsonMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    private static ObjectMapper createYamlMapper() {
        YAMLFactory factory = YAMLFactory.builder()
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build();
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Override
    public @NotNull String getName() {
        return name;
    }

    @Override
    public boolean supports(@NotNull OutputFormat format) {
        return true; // Base implementation supports all formats
    }

    @Override
    public @NotNull String render(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        return switch (options.getFormat()) {
            case JSON -> renderJson(result, options);
            case YAML -> renderYaml(result, options);
            case HTML -> renderHtml(result, options);
            case TEXT -> renderText(result, options);
        };
    }

    /**
     * Render result as JSON
     */
    protected String renderJson(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        try {
            Object renderModel = createRenderModel(result, options);
            return JSON_MAPPER.writeValueAsString(renderModel);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to render JSON", e);
        }
    }

    /**
     * Render result as YAML
     */
    protected String renderYaml(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        try {
            Object renderModel = createRenderModel(result, options);
            return YAML_MAPPER.writeValueAsString(renderModel);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to render YAML", e);
        }
    }

    /**
     * Render result as plain text for CLI.
     * Subclasses should override this to provide formatted output.
     */
    protected abstract String renderText(@NotNull AnalysisResult result, @NotNull OutputOptions options);

    /**
     * Render result as HTML.
     * Subclasses should override this to provide HTML output.
     */
    protected abstract String renderHtml(@NotNull AnalysisResult result, @NotNull OutputOptions options);

    /**
     * Create a model object for JSON/YAML serialization.
     * Override this to customize the serialized structure.
     */
    protected Object createRenderModel(@NotNull AnalysisResult result, @NotNull OutputOptions options) {
        return result;
    }

    /**
     * Helper to get the JSON mapper
     */
    protected static ObjectMapper getJsonMapper() {
        return JSON_MAPPER;
    }

    /**
     * Helper to get the YAML mapper
     */
    protected static ObjectMapper getYamlMapper() {
        return YAML_MAPPER;
    }
}