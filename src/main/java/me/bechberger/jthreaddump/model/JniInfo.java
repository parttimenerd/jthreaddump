package me.bechberger.jthreaddump.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JNI resource information from thread dump
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JniInfo(
        Integer globalRefs,
        Integer weakRefs,
        Long globalRefsMemory,
        Long weakRefsMemory
) {
    /**
     * Equals comparison that ignores hex values.
     * For JniInfo, this is equivalent to standard equals since there are no hex values.
     * Provided for consistency with other model classes.
     */
    public boolean equalsIgnoringHexValues(JniInfo other) {
        return this.equals(other);
    }
}