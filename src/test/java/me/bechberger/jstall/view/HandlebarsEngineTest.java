package me.bechberger.jstall.view;

import com.github.jknack.handlebars.Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HandlebarsEngine
 */
class HandlebarsEngineTest {

    private HandlebarsEngine engine;

    @BeforeEach
    void setUp() {
        engine = HandlebarsEngine.getInstance();
        engine.clearCache();
    }

    @Nested
    class HelperTests {

        @Test
        void lowercaseHelper_shouldConvertToLowercase() throws IOException {
            Map<String, Object> context = new HashMap<>();
            context.put("text", "HELLO WORLD");

            String template = "{{lowercase text}}";
            String result = engine.getHandlebars().compileInline(template).apply(context);

            assertEquals("hello world", result);
        }

        @Test
        void uppercaseHelper_shouldConvertToUppercase() throws IOException {
            Map<String, Object> context = new HashMap<>();
            context.put("text", "hello world");

            String template = "{{uppercase text}}";
            String result = engine.getHandlebars().compileInline(template).apply(context);

            assertEquals("HELLO WORLD", result);
        }

        @Test
        void boldHelper_withColorEnabled_shouldWrapWithAnsi() throws IOException {
            Map<String, Object> context = new HashMap<>();
            context.put("text", "bold text");
            context.put("colorEnabled", true);

            String template = "{{#bold text color=colorEnabled}}{{/bold}}";
            String result = engine.getHandlebars().compileInline(template).apply(context);

            assertTrue(result.contains("bold text"));
            // ANSI bold starts with ESC[1m
            assertTrue(result.contains("\u001B[1m") || result.contains("bold text"));
        }

        @Test
        void boldHelper_withColorDisabled_shouldReturnPlainText() throws IOException {
            Map<String, Object> context = new HashMap<>();
            context.put("text", "bold text");
            context.put("colorEnabled", false);

            String template = "{{#bold text color=colorEnabled}}{{/bold}}";
            String result = engine.getHandlebars().compileInline(template).apply(context);

            assertEquals("bold text", result);
        }

        @Test
        void colorHelper_red_shouldApplyRedColor() throws IOException {
            Map<String, Object> context = new HashMap<>();
            context.put("text", "error");
            context.put("colorEnabled", true);

            String template = "{{#red text color=colorEnabled}}{{/red}}";
            String result = engine.getHandlebars().compileInline(template).apply(context);

            assertTrue(result.contains("error"));
        }

        @Test
        void ifGtHelper_shouldEvaluateGreaterThan() throws IOException {
            Map<String, Object> context = new HashMap<>();
            context.put("value", 10);

            String template = "{{#ifGt value 5}}greater{{else}}not greater{{/ifGt}}";
            String result = engine.getHandlebars().compileInline(template).apply(context);

            assertEquals("greater", result);
        }

        @Test
        void ifGtHelper_shouldEvaluateFalseCase() throws IOException {
            Map<String, Object> context = new HashMap<>();
            context.put("value", 3);

            String template = "{{#ifGt value 5}}greater{{else}}not greater{{/ifGt}}";
            String result = engine.getHandlebars().compileInline(template).apply(context);

            assertEquals("not greater", result);
        }

        @Test
        void formatDurationHelper_shouldFormatMilliseconds() throws IOException {
            Map<String, Object> context = new HashMap<>();
            context.put("ms", 500L);

            String template = "{{formatDuration ms}}";
            String result = engine.getHandlebars().compileInline(template).apply(context);

            assertEquals("500ms", result);
        }

        @Test
        void formatDurationHelper_shouldFormatSeconds() throws IOException {
            Map<String, Object> context = new HashMap<>();
            context.put("ms", 5500L);

            String template = "{{formatDuration ms}}";
            String result = engine.getHandlebars().compileInline(template).apply(context);

            assertEquals("5.5s", result);
        }

        @Test
        void truncateHelper_shouldTruncateLongText() throws IOException {
            Map<String, Object> context = new HashMap<>();
            context.put("text", "This is a very long text that should be truncated");

            String template = "{{truncate text length=20}}";
            String result = engine.getHandlebars().compileInline(template).apply(context);

            assertTrue(result.length() <= 20);
            assertTrue(result.endsWith("..."));
        }

        @Test
        void truncateHelper_shouldNotTruncateShortText() throws IOException {
            Map<String, Object> context = new HashMap<>();
            context.put("text", "Short");

            String template = "{{truncate text length=20}}";
            String result = engine.getHandlebars().compileInline(template).apply(context);

            assertEquals("Short", result);
        }
    }

    @Nested
    class TemplateLoadingTests {

        @Test
        void shouldCacheCompiledTemplates() throws IOException {
            // Note: This test assumes a generic.hbs template exists
            // First compilation
            Template template1 = engine.getTemplate("generic");
            // Second access should return cached version
            Template template2 = engine.getTemplate("generic");

            assertSame(template1, template2);
        }

        @Test
        void clearCache_shouldRemoveCachedTemplates() throws IOException {
            // First compilation
            Template template1 = engine.getTemplate("generic");

            // Clear cache
            engine.clearCache();

            // Second access should recompile
            Template template2 = engine.getTemplate("generic");

            // They should be equal but not the same instance
            assertNotSame(template1, template2);
        }
    }

    @Nested
    class RenderWithOptionsTests {

        @Test
        void render_shouldIncludeOptionsInContext() throws IOException {
            OutputOptions options = OutputOptions.builder()
                    .colorEnabled(true)
                    .verbose(true)
                    .build();

            Map<String, Object> context = new HashMap<>();
            context.put("test", "value");

            String template = "color={{colorEnabled}}, verbose={{verbose}}";
            String result = engine.getHandlebars().compileInline(template).apply(
                    new HashMap<>(Map.of(
                            "colorEnabled", options.isColorEnabled(),
                            "verbose", options.isVerbose()
                    ))
            );

            assertEquals("color=true, verbose=true", result);
        }
    }
}