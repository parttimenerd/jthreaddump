package me.bechberger.jstall.analysis;

/**
 * Classification of thread progress between dumps.
 * Used to categorize thread behavior over time.
 */
public enum ProgressClassification {

    /**
     * Thread is actively making progress (CPU time increased or stack changed)
     */
    ACTIVE("Thread is actively making progress"),

    /**
     * Thread is RUNNABLE but shows no CPU progress and stack unchanged
     */
    RUNNABLE_NO_PROGRESS("Thread is RUNNABLE but making no progress"),

    /**
     * Thread is blocked waiting for a lock
     */
    BLOCKED_ON_LOCK("Thread is blocked waiting for a lock"),

    /**
     * Thread is in WAITING state but this is expected (e.g., pool thread waiting for work)
     */
    WAITING_EXPECTED("Thread is waiting (expected behavior)"),

    /**
     * Thread is in TIMED_WAITING state but this is expected (e.g., scheduled task)
     */
    TIMED_WAITING_EXPECTED("Thread is timed waiting (expected behavior)"),

    /**
     * Thread appears stuck - no progress over multiple dumps
     */
    STUCK("Thread appears stuck"),

    /**
     * Thread was restarted (elapsed time decreased)
     */
    RESTARTED("Thread was restarted"),

    /**
     * Thread has terminated
     */
    TERMINATED("Thread has terminated"),

    /**
     * Thread is ignored based on filter options
     */
    IGNORED("Thread is ignored by filter"),

    /**
     * Thread is new (appeared in later dump)
     */
    NEW("Thread is new"),

    /**
     * Classification cannot be determined (single dump or insufficient data)
     */
    UNKNOWN("Classification cannot be determined");

    private final String description;

    ProgressClassification(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Check if this classification indicates a potential problem
     */
    public boolean isProblem() {
        return this == RUNNABLE_NO_PROGRESS ||
               this == BLOCKED_ON_LOCK ||
               this == STUCK;
    }

    /**
     * Check if this classification indicates healthy behavior
     */
    public boolean isHealthy() {
        return this == ACTIVE ||
               this == WAITING_EXPECTED ||
               this == TIMED_WAITING_EXPECTED ||
               this == NEW;
    }

    /**
     * Check if this classification should be included in stall detection
     */
    public boolean countsForStallDetection() {
        return this != IGNORED &&
               this != TERMINATED &&
               this != UNKNOWN &&
               this != NEW;
    }
}