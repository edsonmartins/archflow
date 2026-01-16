package br.com.archflow.agent.deterministic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Executor for Func-Agent Mode (Deterministic Agent Execution).
 *
 * <p>This executor ensures:
 * <ul>
 *   <li><b>Deterministic output:</b> Results match expected format and schema</li>
 *   <li><b>Input validation:</b> Optional pre-execution validation</li>
 *   <li><b>Strict retry:</b> Configurable retry with output validation</li>
 *   <li><b>Timeout enforcement:</b> Operations fail if too slow</li>
 *   <li><b>Full audit trail:</b> All executions are logged</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * FuncAgentConfig config = FuncAgentConfig.Presets.financialCalculation();
 * FuncAgentExecutor executor = new FuncAgentExecutor(config);
 *
 * // Execute with input validation
 * ExecutionResult result = executor.execute(
 *     () -> agent.process(input),
 *     input
 * );
 * </pre>
 */
public class FuncAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(FuncAgentExecutor.class);

    private final FuncAgentConfig config;
    private final ExecutorService executorService;

    /**
     * Creates a new executor with the given config.
     *
     * @param config The Func-Agent configuration
     */
    public FuncAgentExecutor(FuncAgentConfig config) {
        this.config = config;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Creates a new executor with the given config and custom executor.
     *
     * @param config The Func-Agent configuration
     * @param executorService The executor service for async execution
     */
    public FuncAgentExecutor(FuncAgentConfig config, ExecutorService executorService) {
        this.config = config;
        this.executorService = executorService;
    }

    /**
     * Executes an operation with Func-Agent guarantees.
     *
     * @param operation The operation to execute
     * @param <T> The result type
     * @return The execution result
     * @throws ExecutionException if execution fails
     */
    public <T> ExecutionResult<T> execute(Supplier<T> operation) throws ExecutionException {
        return execute(operation, null);
    }

    /**
     * Executes an operation with input validation.
     *
     * @param operation The operation to execute
     * @param input The input data (for validation)
     * @param <T> The result type
     * @return The execution result
     * @throws ExecutionException if execution fails
     */
    @SuppressWarnings("unchecked")
    public <T> ExecutionResult<T> execute(Supplier<T> operation, Object input) throws ExecutionException {
        Instant startTime = Instant.now();
        String executionId = java.util.UUID.randomUUID().toString();

        log.info("Func-Agent execution started: id={}, mode={}, format={}",
                executionId, config.getMode(), config.getOutputFormat());

        try {
            // Validate input if enabled
            if (config.isValidateInput() && config.getInputSchema() != null && input != null) {
                validateInput(input, executionId);
            }

            // Execute with retry policy
            Supplier<T> validatedOperation = () -> {
                T result = operation.get();
                try {
                    validateOutput(result, executionId);
                } catch (ExecutionException e) {
                    throw new RuntimeException("Output validation failed", e);
                }
                return result;
            };

            StrictRetryPolicy.Result<T> retryResult;
            if (config.getMode() == FuncAgentConfig.ExecutionMode.DETERMINISTIC) {
                retryResult = executeWithRetry(validatedOperation);
            } else {
                // Creative mode - no retry, just execute
                T result = operation.get();
                retryResult = new StrictRetryPolicy.Result<>(
                        result,
                        java.util.List.of(StrictRetryPolicy.Attempt.success(1, 0)),
                        false,
                        null
                );
            }

            // Format output
            String formattedOutput = formatOutput(retryResult.value());

            Instant endTime = Instant.now();
            long duration = Duration.between(startTime, endTime).toMillis();

            ExecutionResult<T> result = new ExecutionResult<>(
                    retryResult.value(),
                    formattedOutput,
                    executionId,
                    true,
                    duration,
                    retryResult.attempts(),
                    null
            );

            log.info("Func-Agent execution completed: id={}, attempts={}, duration={}ms",
                    executionId, retryResult.getAttemptCount(), duration);

            return result;

        } catch (Exception e) {
            Instant endTime = Instant.now();
            long duration = Duration.between(startTime, endTime).toMillis();

            log.error("Func-Agent execution failed: id={}, error={}", executionId, e.getMessage());

            return new ExecutionResult<>(
                    null,
                    null,
                    executionId,
                    false,
                    duration,
                    java.util.List.of(),
                    e
            );
        }
    }

    /**
     * Executes an operation asynchronously.
     *
     * @param operation The operation to execute
     * @param <T> The result type
     * @return A Future that will complete with the execution result
     */
    public <T> CompletableFuture<ExecutionResult<T>> executeAsync(Supplier<T> operation) throws ExecutionException {
        return executeAsync(operation, null);
    }

    /**
     * Executes an operation asynchronously with input validation.
     *
     * @param operation The operation to execute
     * @param input The input data (for validation)
     * @param <T> The result type
     * @return A Future that will complete with the execution result
     */
    public <T> CompletableFuture<ExecutionResult<T>> executeAsync(Supplier<T> operation, Object input) throws ExecutionException {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return execute(operation, input);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }, executorService);
    }

    /**
     * Executes with timeout enforcement.
     *
     * @param operation The operation to execute
     * @param timeoutMs The timeout in milliseconds
     * @param <T> The result type
     * @return The execution result
     * @throws ExecutionException if execution fails or times out
     */
    public <T> ExecutionResult<T> executeWithTimeout(Supplier<T> operation, long timeoutMs) throws ExecutionException {
        try {
            Future<ExecutionResult<T>> future = executorService.submit(() -> execute(operation));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ExecutionException("Operation timed out after " + timeoutMs + "ms", e);
        } catch (Exception e) {
            throw new ExecutionException("Execution failed", e);
        }
    }

    private <T> StrictRetryPolicy.Result<T> executeWithRetry(Supplier<T> operation) throws ExecutionException {
        try {
            return config.getRetryPolicy().execute(operation);
        } catch (StrictRetryPolicy.RetryExhaustedException e) {
            throw new ExecutionException("Retry policy exhausted", e);
        }
    }

    private void validateInput(Object input, String executionId) throws ExecutionException {
        try {
            if (input instanceof String) {
                config.getInputSchema().validateJson((String) input);
            } else {
                config.getInputSchema().validate(input);
            }
            log.debug("Input validation passed for execution: {}", executionId);
        } catch (OutputSchema.ValidationException e) {
            throw new ExecutionException("Input validation failed: " + e.getMessage(), e);
        }
    }

    private <T> void validateOutput(T output, String executionId) throws ExecutionException {
        if (config.getOutputSchema() == null) {
            return;
        }

        try {
            if (output instanceof String) {
                config.getOutputSchema().validateJson((String) output);
            } else {
                config.getOutputSchema().validate(output);
            }
            log.debug("Output validation passed for execution: {}", executionId);
        } catch (OutputSchema.ValidationException e) {
            throw new ExecutionException("Output validation failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> String formatOutput(T output) throws ExecutionException {
        if (output == null) {
            return null;
        }

        try {
            OutputFormat format = config.getOutputFormat();

            if (output instanceof Map) {
                return format.format((Map<String, Object>) output, config.getOutputSchema());
            } else if (output instanceof java.util.List) {
                // Try to convert to List<Map>
                if (!((java.util.List<?>) output).isEmpty() &&
                        ((java.util.List<?>) output).get(0) instanceof Map) {
                    return format.format((java.util.List<Map<String, Object>>) output, config.getOutputSchema());
                }
            }

            return format.format(output, config.getOutputSchema());

        } catch (OutputFormat.FormatException e) {
            throw new ExecutionException("Failed to format output", e);
        }
    }

    /**
     * Shuts down the executor service.
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Gets the configuration.
     */
    public FuncAgentConfig getConfig() {
        return config;
    }

    /**
     * Result of a Func-Agent execution.
     *
     * @param <T> The result type
     */
    public record ExecutionResult<T>(
            T value,
            String formattedOutput,
            String executionId,
            boolean success,
            long durationMs,
            java.util.List<StrictRetryPolicy.Attempt> attempts,
            Exception error
    ) {
        /**
         * Gets the value as a Map (if applicable).
         */
        @SuppressWarnings("unchecked")
        public Map<String, Object> asMap() {
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            throw new IllegalStateException("Value is not a Map: " + (value != null ? value.getClass() : "null"));
        }

        /**
         * Gets the value as a String.
         */
        public String asString() {
            if (value instanceof String) {
                return (String) value;
            }
            return formattedOutput != null ? formattedOutput : String.valueOf(value);
        }

        /**
         * Throws the error if execution failed.
         */
        public ExecutionResult<T> throwIfFailed() throws ExecutionException {
            if (!success && error != null) {
                throw new ExecutionException("Execution failed", error);
            }
            return this;
        }
    }

    /**
     * Exception thrown when Func-Agent execution fails.
     */
    public static class ExecutionException extends Exception {
        public ExecutionException(String message) {
            super(message);
        }

        public ExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
