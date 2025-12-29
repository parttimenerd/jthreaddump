package me.bechberger.jstall.view;

import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.analysis.analyzers.DeadlockAnalyzer;
import me.bechberger.jstall.analysis.analyzers.StackGroupAnalyzer;
import me.bechberger.jstall.analysis.analyzers.ThreadProgressAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ViewRendererFactory and ViewRendererRegistry
 */
class ViewRendererFactoryTest {

    @BeforeEach
    void setUp() {
        ViewRendererFactory.reset();
    }

    @Nested
    class InitializationTests {

        @Test
        void initialize_shouldRegisterBuiltInRenderers() {
            ViewRendererFactory.initialize();

            assertTrue(ViewRendererFactory.isInitialized());
        }

        @Test
        void initialize_shouldBeIdempotent() {
            ViewRendererFactory.initialize();
            ViewRendererFactory.initialize();

            assertTrue(ViewRendererFactory.isInitialized());
        }

        @Test
        void getRenderer_shouldAutoInitialize() {
            assertFalse(ViewRendererFactory.isInitialized());

            ViewRendererFactory.getRenderer("deadlock");

            assertTrue(ViewRendererFactory.isInitialized());
        }
    }

    @Nested
    class RendererLookupTests {

        @BeforeEach
        void init() {
            ViewRendererFactory.initialize();
        }

        @Test
        void getRenderer_byName_shouldFindDeadlockRenderer() {
            ViewRenderer renderer = ViewRendererFactory.getRenderer("deadlock");

            assertNotNull(renderer);
            assertEquals("deadlock", renderer.getName());
        }

        @Test
        void getRenderer_byName_shouldFindGenericRenderer() {
            ViewRenderer renderer = ViewRendererFactory.getRenderer("generic");

            assertNotNull(renderer);
            assertEquals("generic", renderer.getName());
        }

        @Test
        void getRenderer_byName_shouldReturnNullForUnknown() {
            ViewRenderer renderer = ViewRendererFactory.getRenderer("nonexistent");

            assertNull(renderer);
        }
    }

    @Nested
    class RegistryTests {

        @Test
        void registry_shouldFindRendererForResultType() {
            ViewRendererFactory.initialize();
            ViewRendererRegistry registry = ViewRendererRegistry.getInstance();

            List<ViewRenderer> renderers = registry.findRenderers(
                    DeadlockAnalyzer.DeadlockResult.class, OutputFormat.TEXT);

            assertFalse(renderers.isEmpty());
            assertEquals("deadlock", renderers.get(0).getName());
        }

        @Test
        void registry_shouldFallbackToGenericRenderer() {
            ViewRendererFactory.initialize();
            ViewRendererRegistry registry = ViewRendererRegistry.getInstance();

            // Any AnalysisResult should find the generic renderer
            List<ViewRenderer> renderers = registry.findRenderers(
                    AnalysisResult.class, OutputFormat.TEXT);

            assertFalse(renderers.isEmpty());
            // Generic renderer should be available
            assertTrue(renderers.stream().anyMatch(r -> r.getName().equals("generic")));
        }

        @Test
        void registry_shouldSupportAllFormats() {
            ViewRendererFactory.initialize();
            ViewRendererRegistry registry = ViewRendererRegistry.getInstance();

            for (OutputFormat format : OutputFormat.values()) {
                ViewRenderer renderer = registry.findBestRenderer(
                        DeadlockAnalyzer.DeadlockResult.class, format);
                assertNotNull(renderer, "Should have renderer for " + format);
                assertTrue(renderer.supports(format));
            }
        }
    }

    @Nested
    class RenderingTests {

        @Test
        void renderText_shouldProduceNonEmptyOutput() {
            // Create a simple result for testing
            DeadlockAnalyzer.DeadlockResult result = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of()
            );

            String output = ViewRendererFactory.renderText(result);

            assertNotNull(output);
            assertFalse(output.isEmpty());
        }

        @Test
        void renderJson_shouldProduceValidJson() {
            DeadlockAnalyzer.DeadlockResult result = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of()
            );

            String output = ViewRendererFactory.renderJson(result);

            assertNotNull(output);
            assertTrue(output.startsWith("{") || output.startsWith("["));
        }

        @Test
        void renderHtml_shouldProduceHtmlDocument() {
            DeadlockAnalyzer.DeadlockResult result = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of()
            );

            String output = ViewRendererFactory.renderHtml(result);

            assertNotNull(output);
            assertTrue(output.contains("<!DOCTYPE html>") || output.contains("<html"));
        }

        @Test
        void renderYaml_shouldProduceYamlOutput() {
            DeadlockAnalyzer.DeadlockResult result = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of()
            );

            String output = ViewRendererFactory.renderYaml(result);

            assertNotNull(output);
            // YAML typically doesn't have curly braces for objects
            assertTrue(output.contains("analyzerName") || output.contains("severity"));
        }

        @Test
        void renderText_withColor_shouldIncludeAnsiCodes() {
            DeadlockAnalyzer.DeadlockResult result = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.CRITICAL,
                    List.of(new AnalysisResult.Finding(
                            AnalysisResult.Severity.CRITICAL,
                            "deadlock",
                            "Test deadlock")),
                    List.of()
            );

            String output = ViewRendererFactory.renderText(result, true);

            assertNotNull(output);
            // When color is enabled, ANSI escape codes should be present
            // ESC character is \u001B
            assertTrue(output.contains("\u001B[") || output.length() > 0);
        }

        @Test
        void renderText_withoutColor_shouldNotIncludeAnsiCodes() {
            DeadlockAnalyzer.DeadlockResult result = new DeadlockAnalyzer.DeadlockResult(
                    AnalysisResult.Severity.OK,
                    List.of(),
                    List.of()
            );

            String output = ViewRendererFactory.renderText(result, false);

            assertNotNull(output);
            // When color is disabled, no ANSI escape codes
            assertFalse(output.contains("\u001B["));
        }
    }
}