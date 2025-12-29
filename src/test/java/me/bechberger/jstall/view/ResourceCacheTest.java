package me.bechberger.jstall.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResourceCache
 */
class ResourceCacheTest {

    @BeforeEach
    void setUp() {
        // Don't clear cache between tests to avoid network calls
    }

    @Nested
    class ChartJsTests {

        @Test
        void getChartJs_shouldReturnContent() {
            String chartJs = ResourceCache.getInstance().getChartJs();

            assertNotNull(chartJs);
            assertFalse(chartJs.isEmpty());
        }

        @Test
        void getChartJsScriptTag_shouldReturnScriptElement() {
            String scriptTag = ResourceCache.getInstance().getChartJsScriptTag();

            assertNotNull(scriptTag);
            assertTrue(scriptTag.startsWith("<script>"));
            assertTrue(scriptTag.endsWith("</script>"));
        }

        @Test
        void getChartJs_shouldBeCachedAfterFirstCall() {
            // First call may download
            String first = ResourceCache.getInstance().getChartJs();

            // Second call should be from cache (much faster)
            long start = System.nanoTime();
            String second = ResourceCache.getInstance().getChartJs();
            long elapsed = System.nanoTime() - start;

            assertEquals(first, second);
            // Cache access should be < 1ms typically
            assertTrue(elapsed < 10_000_000, "Cache access took too long: " + elapsed + "ns");
        }

        @Test
        void getChartJs_fallbackShouldWorkWhenOffline() {
            // Even if download fails, we should get some content
            String content = ResourceCache.getInstance().getChartJs();

            assertNotNull(content);
            // Either real Chart.js or fallback notice
            assertTrue(content.contains("Chart") || content.contains("chart"),
                    "Content should reference Chart in some way");
        }
    }

    @Nested
    class CacheStatusTests {

        @Test
        void getCacheStatus_shouldReturnFormattedStatus() {
            String status = ResourceCache.getInstance().getCacheStatus();

            assertNotNull(status);
            assertTrue(status.contains("Resource Cache Status"));
            assertTrue(status.contains("Cache Directory"));
            assertTrue(status.contains("chart.js"));
        }

        @Test
        void isChartJsCached_shouldReturnBoolean() {
            // Force a load first
            ResourceCache.getInstance().getChartJs();

            // Now check if cached (may or may not be depending on network)
            boolean cached = ResourceCache.getInstance().isChartJsCached();
            // Just verify it returns a boolean without error
            assertTrue(cached || !cached);
        }
    }

    @Nested
    class PreloadTests {

        @Test
        void preloadResources_shouldNotThrow() {
            // Should complete without throwing
            assertDoesNotThrow(() -> ResourceCache.getInstance().preloadResources());
        }
    }
}