package me.bechberger.jthreaddump.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents lock/monitor information
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LockInfo(
        String lockId,
        String className,
        String lockType  // e.g., "waiting on", "locked", "parking"
) {
    @Override
    public String toString() {
        return String.format("%s <%s> (%s)", lockType, lockId, className);
    }

    /**
     * Equals comparison that ignores lockId (hex value).
     * Useful for test comparisons where memory addresses differ between runs.
     */
    public boolean equalsIgnoringHexValues(LockInfo other) {
        if (this == other) return true;
        if (other == null) return false;
        return java.util.Objects.equals(className, other.className) &&
               java.util.Objects.equals(lockType, other.lockType);
        // Intentionally ignore lockId (hex value)
    }
}