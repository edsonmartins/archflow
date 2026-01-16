package br.com.archflow.agent.deterministic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Strict retry policy for critical business processes in Func-Agent Mode.
 *
 * <p>Unlike normal retry policies, strict retry ensures:
 * <ul>
 *   <li><b>Exact match validation:</b> Output must match expected schema</li>
 *   <li><b>No data corruption:</b> Each retry is independent</li>
 *   <li><b>Audit trail:</b> All attempts are logged for compliance</li>
 *   <li><b>Fail-fast on validation:</b> Invalid output fails immediately</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * StrictRetryPolicy policy = StrictRetryPolicy.builder()
 *     .maxAttempts(3)
 *     .backoff(Duration.ofMillis(100), 2.0)
 *     .validateOutput(true)
 *     .outputSchema(schema)
 *     .build();
 *
 * Result result = policy.execute(() -> criticalOperation());
 * </pre>
 */
public class StrictRetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(StrictRetryPolicy.class);

    private final int maxAttempts;
    private final Duration initialDelay;
    private final double backoffMultiplier;
    private final boolean validateOutput;
    private final OutputSchema outputSchema;
    private final boolean failOnValidationError;
    private final RetryListener listener;

    private StrictRetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialDelay = builder.initialDelay;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.validateOutput = builder.validateOutput;
        this.outputSchema = builder.outputSchema;
        this.failOnValidationError = builder.failOnValidationError;
        this.listener = builder.listener;
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Executes the operation with strict retry policy.
     *
     * @param operation The operation to execute
     * @param <T> The result type
     * @return The execution result
     * @throws RetryExhaustedException if all retries fail
     */
    public <T> Result<T> execute(Supplier<T> operation) throws RetryExhaustedException {
        List<Attempt> attempts = new ArrayList<>();
        Exception lastException = null;
        T result = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long startTime = System.currentTimeMillis();
            boolean success = false;
            String errorMessage = null;

            try {
                log.debug("Strict retry attempt {}/{}", attempt, maxAttempts);

                // Execute operation
                result = operation.get();

                // Validate output if enabled
                if (validateOutput && outputSchema != null) {
                    try {
                        if (result instanceof String) {
                            outputSchema.validateJson((String) result);
                        } else {
                            outputSchema.validate(result);
                        }
                        log.debug("Output validation passed on attempt {}", attempt);
                    } catch (OutputSchema.ValidationException e) {
                        if (failOnValidationError) {
                            // Validation error is fatal - don't retry
                            log.warn("Output validation failed (fatal): {}", e.getMessage());
                            if (listener != null) {
                                listener.onValidationFailure(attempt, e);
                            }
                            return new Result<>(null, attempts, true, e);
                        }
                        // Treat as soft failure, will retry
                        throw new OutputSchema.ValidationException("Validation failed: " + e.getMessage(), e);
                    }
                }

                success = true;
                log.info("Strict retry succeeded on attempt {}", attempt);

                if (listener != null) {
                    listener.onSuccess(attempt, System.currentTimeMillis() - startTime);
                }

                return new Result<>(result, attempts, false, null);

            } catch (Exception e) {
                lastException = e;
                errorMessage = e.getMessage();
                long duration = System.currentTimeMillis() - startTime;

                attempts.add(new Attempt(attempt, duration, false, errorMessage));

                if (listener != null) {
                    listener.onFailure(attempt, duration, e);
                }

                if (attempt < maxAttempts) {
                    long delayMs = calculateDelay(attempt);
                    log.warn("Strict retry attempt {} failed: {}. Retrying in {}ms",
                            attempt, errorMessage, delayMs);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RetryExhaustedException("Retry interrupted", attempts);
                    }
                }
            }
        }

        log.error("Strict retry exhausted after {} attempts", maxAttempts);
        if (listener != null) {
            listener.onExhausted(attempts);
        }

        throw new RetryExhaustedException("All " + maxAttempts + " retry attempts failed", attempts);
    }

    private long calculateDelay(int attemptNumber) {
        long delay = (long) (initialDelay.toMillis() * Math.pow(backoffMultiplier, attemptNumber - 1));
        // Cap at 30 seconds
        return Math.min(delay, 30000);
    }

    /**
     * Gets the maximum number of attempts.
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Gets the initial delay.
     */
    public Duration getInitialDelay() {
        return initialDelay;
    }

    /**
     * Gets the backoff multiplier.
     */
    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    /**
     * Checks if output validation is enabled.
     */
    public boolean isValidateOutput() {
        return validateOutput;
    }

    /**
     * Gets the output schema.
     */
    public OutputSchema getOutputSchema() {
        return outputSchema;
    }

    /**
     * Builder for StrictRetryPolicy.
     */
    public static class Builder {
        private int maxAttempts = 3;
        private Duration initialDelay = Duration.ofMillis(100);
        private double backoffMultiplier = 2.0;
        private boolean validateOutput = false;
        private OutputSchema outputSchema;
        private boolean failOnValidationError = true;
        private RetryListener listener;

        private Builder() {
        }

        /**
         * Sets the maximum number of retry attempts.
         */
        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets the initial delay before first retry.
         */
        public Builder initialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
            return this;
        }

        /**
         * Sets the initial delay in milliseconds.
         */
        public Builder initialDelay(long millis) {
            return initialDelay(Duration.ofMillis(millis));
        }

        /**
         * Sets the backoff multiplier (each retry waits longer).
         */
        public Builder backoffMultiplier(double multiplier) {
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
            }
            this.backoffMultiplier = multiplier;
            return this;
        }

        /**
         * Enables output validation with the given schema.
         */
        public Builder validateOutput(OutputSchema schema) {
            this.validateOutput = true;
            this.outputSchema = schema;
            return this;
        }

        /**
         * Sets whether validation errors should fail immediately (no retry).
         */
        public Builder failOnValidationError(boolean failFast) {
            this.failOnValidationError = failFast;
            return this;
        }

        /**
         * Sets a listener for retry events.
         */
        public Builder listener(RetryListener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * Builds the StrictRetryPolicy.
         */
        public StrictRetryPolicy build() {
            return new StrictRetryPolicy(this);
        }
    }

    /**
     * Result of a retry operation.
     */
    public record Result<T>(
            T value,
            List<Attempt> attempts,
            boolean validationFailed,
            Exception validationError
    ) {
        /**
         * Checks if the operation succeeded.
         */
        public boolean isSuccess() {
            return value != null && !validationFailed;
        }

        /**
         * Gets the total number of attempts made.
         */
        public int getAttemptCount() {
            return attempts.size();
        }

        /**
         * Gets the total duration of all attempts.
         */
        public long getTotalDuration() {
            return attempts.stream()
                    .mapToLong(Attempt::duration)
                    .sum();
        }
    }

    /**
     * Information about a single retry attempt.
     */
    public record Attempt(
            int attemptNumber,
            long duration,
            boolean success,
            String errorMessage
    ) {
        /**
         * Creates a successful attempt record.
         */
        public static Attempt success(int attemptNumber, long duration) {
            return new Attempt(attemptNumber, duration, true, null);
        }

        /**
         * Creates a failed attempt record.
         */
        public static Attempt failure(int attemptNumber, long duration, String error) {
            return new Attempt(attemptNumber, duration, false, error);
        }
    }

    /**
     * Listener for retry events.
     */
    public interface RetryListener {
        /**
         * Called when an attempt succeeds.
         */
        void onSuccess(int attemptNumber, long duration);

        /**
         * Called when an attempt fails.
         */
        void onFailure(int attemptNumber, long duration, Exception error);

        /**
         * Called when output validation fails.
         */
        void onValidationFailure(int attemptNumber, OutputSchema.ValidationException error);

        /**
         * Called when all retry attempts are exhausted.
         */
        void onExhausted(List<Attempt> attempts);
    }

    /**
     * Exception thrown when all retry attempts are exhausted.
     */
    public static class RetryExhaustedException extends Exception {
        private final List<Attempt> attempts;

        public RetryExhaustedException(String message, List<Attempt> attempts) {
            super(message);
            this.attempts = attempts;
        }

        public List<Attempt> getAttempts() {
            return attempts;
        }
    }
}
