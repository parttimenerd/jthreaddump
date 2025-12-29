package me.bechberger.jstall.test.scenarios;

/**
 * Interface for defining test scenarios that can be recorded with JFR.
 */
public interface ScenarioDefinition {

    /**
     * Get the name of this scenario
     */
    String getName();

    /**
     * Get description of what this scenario tests
     */
    String getDescription();

    /**
     * Optional setup code to run before the scenario starts.
     * Called before JFR recording begins.
     */
    default void setup() {
        // No-op by default
    }

    /**
     * The main scenario code to run.
     * This runs while JFR is recording and thread dumps are being captured.
     */
    void run();

    /**
     * Optional cleanup code to run after the scenario completes.
     */
    default void cleanup() {
        // No-op by default
    }

    /**
     * Get the expected analysis results for validation.
     * Returns null if no validation is needed.
     */
    default ExpectedResults getExpectedResults() {
        return null;
    }

    /**
     * Expected results for scenario validation
     */
    record ExpectedResults(
            boolean expectDeadlock,
            boolean expectLockContention,
            boolean expectIOBlocking,
            boolean expectHighCPU,
            boolean expectHighAllocation,
            int minThreadCount,
            int maxThreadCount
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private boolean expectDeadlock = false;
            private boolean expectLockContention = false;
            private boolean expectIOBlocking = false;
            private boolean expectHighCPU = false;
            private boolean expectHighAllocation = false;
            private int minThreadCount = 0;
            private int maxThreadCount = Integer.MAX_VALUE;

            public Builder expectDeadlock() {
                this.expectDeadlock = true;
                return this;
            }

            public Builder expectLockContention() {
                this.expectLockContention = true;
                return this;
            }

            public Builder expectIOBlocking() {
                this.expectIOBlocking = true;
                return this;
            }

            public Builder expectHighCPU() {
                this.expectHighCPU = true;
                return this;
            }

            public Builder expectHighAllocation() {
                this.expectHighAllocation = true;
                return this;
            }

            public Builder minThreadCount(int min) {
                this.minThreadCount = min;
                return this;
            }

            public Builder maxThreadCount(int max) {
                this.maxThreadCount = max;
                return this;
            }

            public ExpectedResults build() {
                return new ExpectedResults(
                        expectDeadlock, expectLockContention, expectIOBlocking,
                        expectHighCPU, expectHighAllocation,
                        minThreadCount, maxThreadCount
                );
            }
        }
    }
}