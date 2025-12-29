package me.bechberger.jstall.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents a single stack frame in a thread's stack trace
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StackFrame(
        String className,
        String methodName,
        String fileName,
        Integer lineNumber,
        Boolean nativeMethod
) {
    /**
     * Convenience constructor without nativeMethod (defaults to null/false)
     */
    public StackFrame(String className, String methodName, String fileName, Integer lineNumber) {
        this(className, methodName, fileName, lineNumber, null);
    }

    @Override
    public String toString() {
        if (Boolean.TRUE.equals(nativeMethod)) {
            return String.format("at %s.%s(Native Method)", className, methodName);
        } else if (fileName != null && lineNumber != null) {
            return String.format("at %s.%s(%s:%d)", className, methodName, fileName, lineNumber);
        } else if (fileName != null) {
            return String.format("at %s.%s(%s)", className, methodName, fileName);
        } else {
            return String.format("at %s.%s", className, methodName);
        }
    }

    /**
     * Equals comparison that ignores hex values.
     * For StackFrame, this is equivalent to standard equals since there are no hex values.
     * Provided for consistency with other model classes.
     */
    public boolean equalsIgnoringHexValues(StackFrame other) {
        return this.equals(other);
    }
}