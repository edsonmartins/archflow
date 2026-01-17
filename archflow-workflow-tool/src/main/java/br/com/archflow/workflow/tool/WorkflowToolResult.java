package br.com.archflow.workflow.tool;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Result of executing a WorkflowTool.
 *
 * @param success Whether the execution was successful
 * @param output The output produced by the workflow
 * @param error Error message if execution failed
 * @param duration Time taken to execute
 * @param executionId Unique identifier for this execution
 */
public record WorkflowToolResult(
        boolean success,
        Object output,
        String error,
        Duration duration,
        String executionId,
        Map<String, Object> metadata
) {
    /**
     * Creates a successful result.
     */
    public static WorkflowToolResult success(Object output, Duration duration, String executionId) {
        return new WorkflowToolResult(true, output, null, duration, executionId, Map.of());
    }

    /**
     * Creates a successful result with metadata.
     */
    public static WorkflowToolResult success(Object output, Duration duration, String executionId, Map<String, Object> metadata) {
        return new WorkflowToolResult(true, output, null, duration, executionId, metadata);
    }

    /**
     * Creates a failed result.
     */
    public static WorkflowToolResult failure(String error, Duration duration, String executionId) {
        return new WorkflowToolResult(false, null, error, duration, executionId, Map.of());
    }

    /**
     * Creates a failed result with metadata.
     */
    public static WorkflowToolResult failure(String error, Duration duration, String executionId, Map<String, Object> metadata) {
        return new WorkflowToolResult(false, null, error, duration, executionId, metadata);
    }

    /**
     * Gets the output as a specific type.
     */
    @SuppressWarnings("unchecked")
    public <T> T outputAs(Class<T> type) {
        if (!success || output == null) {
            return null;
        }
        if (type.isInstance(output)) {
            return (T) output;
        }
        return null;
    }

    /**
     * Gets the output as a Map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> outputAsMap() {
        return outputAs(Map.class);
    }

    /**
     * Gets the output as a String.
     */
    public String outputAsString() {
        return output != null ? output.toString() : null;
    }

    /**
     * Returns the output if successful, or throws an exception with the error message.
     */
    public Object outputOrThrow() {
        if (!success) {
            throw new WorkflowToolExecutionException(error != null ? error : "Execution failed");
        }
        return output;
    }

    /**
     * Returns the output if successful, or the default value if failed.
     */
    public Object outputOrElse(Object defaultValue) {
        return success ? output : defaultValue;
    }

    /**
     * Gets the error message, if present.
     */
    public Optional<String> getError() {
        return Optional.ofNullable(error);
    }

    /**
     * Exception thrown when workflow tool execution fails.
     */
    public static class WorkflowToolExecutionException extends RuntimeException {
        public WorkflowToolExecutionException(String message) {
            super(message);
        }

        public WorkflowToolExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
