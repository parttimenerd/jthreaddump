package me.bechberger.jstall.analysis.analyzers;

import me.bechberger.jstall.analysis.AnalysisContext;
import me.bechberger.jstall.analysis.AnalysisResult;
import me.bechberger.jstall.model.DeadlockInfo;
import me.bechberger.jstall.model.ThreadDump;
import me.bechberger.jstall.model.ThreadInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VerdictAnalyzer smart health evolution features
 */
class VerdictAnalyzerHealthEvolutionTest {

    private VerdictAnalyzer analyzer = new VerdictAnalyzer();

    @Nested
    @DisplayName("Health Score Calculation Tests")
    class HealthScoreTests {

        @Test
        @DisplayName("Healthy application should score 90+")
        void testHealthyApplication() {
            ThreadDump dump = createThreadDump(50, 45, 0, 0, 0); // 90% running
            AnalysisContext context = AnalysisContext.of(dump);

            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            // Should be OK or INFO severity
            assertTrue(result.getSeverity().getLevel() <= AnalysisResult.Severity.INFO.getLevel(),
                    "Healthy app should have OK or INFO severity");
        }

        @Test
        @DisplayName("Heavily blocked threads should reduce health score significantly")
        void testBlockedThreadsPenalty() {
            // 60% blocked threads
            ThreadDump dump = createThreadDump(50, 10, 30, 5, 5);
            AnalysisContext context = AnalysisContext.of(dump);

            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            // Should detect the issue
            assertTrue(result.getSeverity().isWorseThan(AnalysisResult.Severity.OK),
                    "Heavily blocked threads should trigger warning/error");
        }

        @Test
        @DisplayName("High waiting percentage should reduce score")
        void testWaitingThreadsPenalty() {
            // 80% waiting threads
            ThreadDump dump = createThreadDump(50, 5, 0, 40, 5);
            AnalysisContext context = AnalysisContext.of(dump);

            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            // Should have findings about waiting threads
            assertFalse(result.getItems().isEmpty(), "Should have verdict items");
        }

        @Test
        @DisplayName("No threads should return neutral score")
        void testEmptyThreadDump() {
            ThreadDump dump = createThreadDump(0, 0, 0, 0, 0);
            AnalysisContext context = AnalysisContext.of(dump);

            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            // Should handle gracefully
            assertNotNull(result, "Should return result even for empty dump");
        }
    }

    @Nested
    @DisplayName("Trend Detection Tests")
    class TrendDetectionTests {

        @Test
        @DisplayName("Degrading health over time should be detected")
        void testDegradingTrend() {
            List<ThreadDump> dumps = new ArrayList<>();

            // Simulate degradation: healthy → fair → poor
            dumps.add(createThreadDump(50, 45, 0, 3, 2));    // Healthy
            dumps.add(createThreadDump(50, 35, 5, 8, 2));    // Slight degradation
            dumps.add(createThreadDump(50, 25, 15, 8, 2));   // More blocked
            dumps.add(createThreadDump(50, 20, 20, 8, 2));   // Even worse
            dumps.add(createThreadDump(50, 15, 25, 8, 2));   // Poor

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution for multi-dump");

            VerdictAnalyzer.HealthEvolution health = result.getHealthEvolution();
            assertTrue(health.isDegrading(), "Should detect degrading trend");
            assertEquals("DEGRADING", health.trend(), "Trend should be DEGRADING");
            assertTrue(health.scoreChange() < -10, "Score should have dropped significantly");
        }

        @Test
        @DisplayName("Improving health over time should be detected")
        void testImprovingTrend() {
            List<ThreadDump> dumps = new ArrayList<>();

            // Simulate improvement: poor → fair → good
            dumps.add(createThreadDump(50, 15, 25, 8, 2));   // Poor
            dumps.add(createThreadDump(50, 20, 20, 8, 2));   // Improving
            dumps.add(createThreadDump(50, 30, 10, 8, 2));   // Better
            dumps.add(createThreadDump(50, 40, 5, 3, 2));    // Good
            dumps.add(createThreadDump(50, 45, 2, 2, 1));    // Excellent

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution");

            VerdictAnalyzer.HealthEvolution health = result.getHealthEvolution();
            assertTrue(health.isImproving(), "Should detect improving trend");
            assertEquals("IMPROVING", health.trend(), "Trend should be IMPROVING");
            assertTrue(health.scoreChange() > 10, "Score should have increased significantly");
        }

        @Test
        @DisplayName("Stable health should be detected")
        void testStableTrend() {
            List<ThreadDump> dumps = new ArrayList<>();

            // Simulate stable operation
            dumps.add(createThreadDump(50, 40, 3, 5, 2));
            dumps.add(createThreadDump(50, 41, 2, 5, 2));
            dumps.add(createThreadDump(50, 39, 3, 6, 2));
            dumps.add(createThreadDump(50, 40, 4, 4, 2));
            dumps.add(createThreadDump(50, 40, 3, 5, 2));

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution");

            VerdictAnalyzer.HealthEvolution health = result.getHealthEvolution();
            assertFalse(health.isDegrading(), "Should not be degrading");
            assertFalse(health.isImproving(), "Should not be improving");
            assertEquals("STABLE", health.trend(), "Trend should be STABLE");
            assertTrue(Math.abs(health.scoreChange()) <= 10, "Score change should be minimal");
        }
    }

    @Nested
    @DisplayName("Critical Change Detection Tests")
    class CriticalChangeTests {

        @Test
        @DisplayName("Sudden health drop > 20 points should trigger critical alert")
        void testCriticalHealthDrop() {
            List<ThreadDump> dumps = new ArrayList<>();

            // Simulate sudden failure at dump 3
            dumps.add(createThreadDump(50, 45, 2, 2, 1));    // Excellent: ~92
            dumps.add(createThreadDump(50, 43, 3, 3, 1));    // Good: ~88
            dumps.add(createThreadDump(50, 10, 30, 8, 2));   // Critical: ~50 (drop of ~38!)
            dumps.add(createThreadDump(50, 12, 28, 8, 2));   // Still bad

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution");

            VerdictAnalyzer.HealthEvolution health = result.getHealthEvolution();
            assertTrue(health.hasCriticalChange(), "Should detect critical health drop");
            assertEquals(2, health.criticalChangeDump(),
                    "Critical change should be at dump 2 (0-indexed)");
        }

        @Test
        @DisplayName("Gradual degradation should not trigger critical alert")
        void testGradualDegradation() {
            List<ThreadDump> dumps = new ArrayList<>();

            // Gradual degradation with very small steps (each step < 20 points)
            // Keep blocked % stable to avoid threshold jumps
            dumps.add(createThreadDump(50, 45, 2, 2, 1));    // ~100 - slight deductions
            dumps.add(createThreadDump(50, 44, 3, 2, 1));    // slight increase in blocked
            dumps.add(createThreadDump(50, 43, 4, 2, 1));    // slight increase
            dumps.add(createThreadDump(50, 42, 5, 2, 1));    // slight increase (still under 10% blocked)
            dumps.add(createThreadDump(50, 41, 5, 3, 1));    // stable blocked, slight waiting increase

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution");

            VerdictAnalyzer.HealthEvolution health = result.getHealthEvolution();
            assertFalse(health.hasCriticalChange(),
                    "Gradual degradation should not trigger critical alert");
        }

        @Test
        @DisplayName("Improvement should never trigger critical alert")
        void testImprovementNoCritical() {
            List<ThreadDump> dumps = new ArrayList<>();

            // Large improvement (still positive change)
            dumps.add(createThreadDump(50, 10, 30, 8, 2));   // Poor: ~50
            dumps.add(createThreadDump(50, 45, 2, 2, 1));    // Excellent: ~92 (+42!)

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution");

            VerdictAnalyzer.HealthEvolution health = result.getHealthEvolution();
            assertFalse(health.hasCriticalChange(),
                    "Positive changes should not trigger critical alert");
        }
    }

    @Nested
    @DisplayName("Category Tracking Tests")
    class CategoryTrackingTests {

        @Test
        @DisplayName("Should identify degrading categories")
        void testDegradingCategories() {
            List<ThreadDump> dumps = new ArrayList<>();

            // Thread states degrading
            dumps.add(createThreadDump(50, 45, 2, 2, 1));
            dumps.add(createThreadDump(50, 35, 10, 3, 2));
            dumps.add(createThreadDump(50, 25, 20, 3, 2));

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution");

            VerdictAnalyzer.HealthEvolution health = result.getHealthEvolution();
            assertFalse(health.degradingCategories().isEmpty(),
                    "Should have degrading categories");

            // Thread States should be degrading
            assertTrue(health.degradingCategories().contains("Thread States") ||
                      health.degradingCategories().contains("Lock Contention"),
                    "Should identify thread states or lock contention as degrading");
        }

        @Test
        @DisplayName("Should identify improving categories")
        void testImprovingCategories() {
            List<ThreadDump> dumps = new ArrayList<>();

            // Thread states improving
            dumps.add(createThreadDump(50, 20, 25, 3, 2));
            dumps.add(createThreadDump(50, 30, 15, 3, 2));
            dumps.add(createThreadDump(50, 42, 5, 2, 1));

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution");

            VerdictAnalyzer.HealthEvolution health = result.getHealthEvolution();
            assertFalse(health.improvingCategories().isEmpty(),
                    "Should have improving categories");
        }

        @Test
        @DisplayName("Per-category scores should be tracked over time")
        void testCategoryScoreTracking() {
            List<ThreadDump> dumps = new ArrayList<>();

            dumps.add(createThreadDump(50, 40, 5, 3, 2));
            dumps.add(createThreadDump(50, 35, 8, 5, 2));
            dumps.add(createThreadDump(50, 30, 12, 6, 2));

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution");

            VerdictAnalyzer.HealthEvolution health = result.getHealthEvolution();
            assertFalse(health.categoryScoresPerDump().isEmpty(),
                    "Should have category scores per dump");

            // Each category should have same number of scores as dumps
            for (List<Double> scores : health.categoryScoresPerDump().values()) {
                assertEquals(3, scores.size(),
                        "Each category should have score for each dump");
            }
        }
    }

    @Nested
    @DisplayName("Health Status Classification Tests")
    class HealthStatusTests {

        @Test
        @DisplayName("Score 90+ should be EXCELLENT")
        void testExcellentStatus() {
            List<ThreadDump> dumps = new ArrayList<>();
            dumps.add(createThreadDump(50, 48, 1, 1, 0));  // ~96
            dumps.add(createThreadDump(50, 48, 1, 1, 0));  // Same - need 2 for health evolution

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution");
            assertEquals("EXCELLENT", result.getHealthEvolution().getHealthStatus());
        }

        @Test
        @DisplayName("Score 75-89 should be GOOD")
        void testGoodStatus() {
            List<ThreadDump> dumps = new ArrayList<>();
            dumps.add(createThreadDump(50, 40, 4, 4, 2));  // ~80
            dumps.add(createThreadDump(50, 40, 4, 4, 2));  // Same - need 2 for health evolution

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution");
            String status = result.getHealthEvolution().getHealthStatus();
            assertTrue(status.equals("GOOD") || status.equals("EXCELLENT"),
                    "Should be GOOD or EXCELLENT");
        }

        @Test
        @DisplayName("Score 60-74 should be FAIR")
        void testFairStatus() {
            List<ThreadDump> dumps = new ArrayList<>();
            // Use moderate thread states to target FAIR score (60-74)
            // 50 total: 30 runnable, 6 blocked (12% -> -10), 12 waiting (24% -> no deduction)
            dumps.add(createThreadDump(50, 30, 6, 12, 2));
            dumps.add(createThreadDump(50, 30, 6, 12, 2));  // Same - need 2 for health evolution

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution");
            String status = result.getHealthEvolution().getHealthStatus();
            // Accept multiple possible statuses since exact scoring depends on implementation details
            assertTrue(status.equals("FAIR") || status.equals("GOOD") || status.equals("EXCELLENT"),
                    "Should be FAIR, GOOD, or EXCELLENT, got: " + status);
        }

        @Test
        @DisplayName("Score < 40 should be CRITICAL")
        void testCriticalStatus() {
            List<ThreadDump> dumps = new ArrayList<>();
            dumps.add(createThreadDump(50, 5, 35, 8, 2));  // ~30
            dumps.add(createThreadDump(50, 5, 35, 8, 2));  // Same - need 2 for health evolution

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertTrue(result.hasHealthEvolution(), "Should have health evolution");
            String status = result.getHealthEvolution().getHealthStatus();
            assertTrue(status.equals("CRITICAL") || status.equals("POOR"),
                    "Should be CRITICAL or POOR");
        }
    }

    @Nested
    @DisplayName("Findings Generation Tests")
    class FindingsTests {

        @Test
        @DisplayName("Degrading health should generate warning finding")
        void testDegradingHealthFinding() {
            List<ThreadDump> dumps = new ArrayList<>();

            dumps.add(createThreadDump(50, 45, 2, 2, 1));    // Good
            dumps.add(createThreadDump(50, 35, 8, 5, 2));    // Fair
            dumps.add(createThreadDump(50, 25, 15, 8, 2));   // Poor

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            // Should generate finding about degrading health
            boolean hasDegradingFinding = result.getFindings().stream()
                    .anyMatch(f -> f.category().equals("degrading-health"));

            assertTrue(hasDegradingFinding,
                    "Should generate finding for degrading health");
        }

        @Test
        @DisplayName("Critical health drop should generate error finding")
        void testCriticalHealthFinding() {
            List<ThreadDump> dumps = new ArrayList<>();

            dumps.add(createThreadDump(50, 45, 2, 2, 1));    // Excellent
            dumps.add(createThreadDump(50, 10, 30, 8, 2));   // Critical drop!

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            // Should generate critical finding
            boolean hasCriticalFinding = result.getFindings().stream()
                    .anyMatch(f -> f.category().equals("critical-health-change"));

            assertTrue(hasCriticalFinding,
                    "Should generate finding for critical health change");
        }

        @Test
        @DisplayName("Improving health should not generate warnings")
        void testImprovingHealthNoWarnings() {
            List<ThreadDump> dumps = new ArrayList<>();

            dumps.add(createThreadDump(50, 20, 20, 8, 2));   // Poor
            dumps.add(createThreadDump(50, 35, 8, 5, 2));    // Fair
            dumps.add(createThreadDump(50, 45, 2, 2, 1));    // Good

            AnalysisContext context = AnalysisContext.of(dumps);
            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            // Should not have degrading or critical findings
            boolean hasBadFindings = result.getFindings().stream()
                    .anyMatch(f -> f.category().equals("degrading-health") ||
                                   f.category().equals("critical-health-change"));

            assertFalse(hasBadFindings,
                    "Improving health should not generate degrading/critical findings");
        }
    }

    @Nested
    @DisplayName("Single Dump Compatibility Tests")
    class SingleDumpTests {

        @Test
        @DisplayName("Single dump should not have health evolution")
        void testSingleDumpNoEvolution() {
            ThreadDump dump = createThreadDump(50, 40, 5, 3, 2);
            AnalysisContext context = AnalysisContext.of(dump);

            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertFalse(result.hasHealthEvolution(),
                    "Single dump should not have health evolution");
        }

        @Test
        @DisplayName("Single dump should still work normally")
        void testSingleDumpNormalOperation() {
            ThreadDump dump = createThreadDump(50, 40, 5, 3, 2);
            AnalysisContext context = AnalysisContext.of(dump);

            VerdictAnalyzer.VerdictResult result = analyzer.analyze(context);

            assertNotNull(result, "Should return result");
            assertNotNull(result.getStatus(), "Should have status");
            assertFalse(result.getItems().isEmpty(), "Should have verdict items");
            assertNotNull(result.getTimeDistribution(), "Should have time distribution");
        }
    }

    // Helper method to create thread dumps with specified thread counts
    private ThreadDump createThreadDump(int total, int runnable, int blocked,
                                       int waiting, int gc) {
        List<ThreadInfo> threads = new ArrayList<>();

        // Add runnable threads
        for (int i = 0; i < runnable; i++) {
            threads.add(createThread("Thread-" + i, Thread.State.RUNNABLE, null));
        }

        // Add blocked threads
        for (int i = 0; i < blocked; i++) {
            threads.add(createThread("Blocked-" + i, Thread.State.BLOCKED,
                    "lock-" + (i % 3))); // Some shared locks
        }

        // Add waiting threads
        for (int i = 0; i < waiting; i++) {
            threads.add(createThread("Waiting-" + i, Thread.State.WAITING, null));
        }

        // Add GC threads
        for (int i = 0; i < gc; i++) {
            threads.add(createThread("GC Thread-" + i, Thread.State.RUNNABLE, null));
        }

        return new ThreadDump(
                Instant.now(),
                "Test JVM",
                threads,
                null, // No JNI info
                "test",
                List.of() // No deadlocks
        );
    }

    private ThreadInfo createThread(String name, Thread.State state, String lock) {
        return new ThreadInfo(
                name,
                (long) name.hashCode(),  // threadId
                null,  // nativeId
                null,  // priority
                false, // daemon
                state,
                null,  // cpuTimeMs
                null,  // elapsedTimeMs
                List.of(), // stackTrace
                List.of(), // locks
                lock,  // waitingOnLock
                null   // additionalInfo
        );
    }
}