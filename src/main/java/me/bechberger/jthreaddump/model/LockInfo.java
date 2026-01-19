package me.bechberger.jthreaddump.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents lock/monitor information from thread dumps.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LockInfo(
        String lockId,
        String className,
        LockOperation operation,
        String ownerThreadId  // For parking blockers, this is the parkBlockerOwner thread ID
) {
    /**
     * Lock operation type based on thread dump format.
     */
    public enum LockOperation {
        /** Monitor is owned/locked by the thread */
        LOCKED("locked"),
        /** Thread is blocked trying to acquire monitor (Thread.State.BLOCKED) */
        WAITING_TO_LOCK("waiting to lock"),
        /** Thread is waiting on Object.wait() (Thread.State.WAITING/TIMED_WAITING) */
        WAITING_ON("waiting on"),
        /** Thread is parked waiting for object (parkBlocker) */
        PARKING("parking"),
        /** Lock was eliminated by JIT compiler optimization */
        ELIMINATED("eliminated");

        private final String displayName;

        LockOperation(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        /**
         * Parse lock operation from string (case-insensitive).
         */
        public static LockOperation fromString(String value) {
            if (value == null) return null;
            String normalized = value.toLowerCase().trim();
            return switch (normalized) {
                case "locked" -> LOCKED;
                case "waiting to lock" -> WAITING_TO_LOCK;
                case "waiting on" -> WAITING_ON;
                case "parking", "parking to wait for" -> PARKING;
                case "eliminated", "lock is eliminated" -> ELIMINATED;
                default -> null;
            };
        }
    }

    /**
     * Constructor with just lock ID, class name, and operation (most common case).
     */
    public LockInfo(String lockId, String className, LockOperation operation) {
        this(lockId, className, operation, null);
    }

    @Override
    public String toString() {
        if (ownerThreadId != null) {
            return String.format("%s <%s> (%s), owner #%s",
                    operation.getDisplayName(), lockId, className, ownerThreadId);
        }
        return String.format("%s <%s> (%s)",
                operation.getDisplayName(), lockId, className);
    }

    /**
     * Equals comparison that ignores lockId (hex value) and ownerThreadId.
     * Useful for test comparisons where memory addresses differ between runs.
     */
    public boolean equalsIgnoringHexValues(LockInfo other) {
        if (this == other) return true;
        if (other == null) return false;
        return java.util.Objects.equals(className, other.className) &&
               java.util.Objects.equals(operation, other.operation);
        // Intentionally ignore lockId (hex value) and ownerThreadId
    }

    @JsonIgnore
    public boolean isWaitingOn() {
        return operation == LockOperation.WAITING_ON;
    }

    @JsonIgnore
    public boolean isWaitingToLock() {
        return operation == LockOperation.WAITING_TO_LOCK;
    }

    @JsonIgnore
    public boolean isLocked() {
        return operation == LockOperation.LOCKED;
    }

    @JsonIgnore
    public boolean isParking() {
        return operation == LockOperation.PARKING;
    }

    @JsonIgnore
    public boolean isEliminated() {
        return operation == LockOperation.ELIMINATED;
    }

    @JsonIgnore
    public boolean isBlocking() {
        return operation == LockOperation.WAITING_TO_LOCK ||
               operation == LockOperation.WAITING_ON ||
               operation == LockOperation.PARKING;
    }

    public String lockType() {
        return operation.getDisplayName();
    }
}