package br.com.archflow.agent.deterministic;

import br.com.archflow.agent.config.RetryConfig;

import java.time.Duration;

/**
 * Configuration for Func-Agent Mode (Deterministic Agent Execution).
 *
 * <p>Func-Agent Mode is designed for critical business processes that require:
 * <ul>
 *   <li><b>Deterministic output:</b> Predictable, structured results</li>
 *   <li><b>Schema validation:</b> Output conforms to predefined schema</li>
 *   <li><b>Strict retry:</b> Configurable retry with validation</li>
 *   <li><b>Audit trail:</b> Full execution history</li>
 * </ul>
 *
 * <p>Use cases:</p>
 * <ul>
 *   <li>ETL workflows</li>
 *   <li>Financial calculations</li>
 *   <li>Compliance reporting</li>
 *   <li>Log processing</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * FuncAgentConfig config = FuncAgentConfig.builder()
 *     .mode(ExecutionMode.DETERMINISTIC)
 *     .outputFormat(OutputFormat.JSON)
 *     .outputSchema(schema)
 *     .strictRetry(true)
 *     .maxAttempts(3)
 *     .build();
 *
 * FuncAgentExecutor executor = new FuncAgentExecutor(config);
 * Result result = executor.execute(agent, input);
 * </pre>
 */
public class FuncAgentConfig {

    private final ExecutionMode mode;
    private final OutputFormat outputFormat;
    private final OutputSchema outputSchema;
    private final StrictRetryPolicy retryPolicy;
    private final boolean validateInput;
    private final OutputSchema inputSchema;
    private final long timeoutMs;
    private final boolean requireExplicitConfirmation;
    private final String description;

    private FuncAgentConfig(Builder builder) {
        this.mode = builder.mode;
        this.outputFormat = builder.outputFormat;
        this.outputSchema = builder.outputSchema;
        this.retryPolicy = builder.retryPolicy != null ? builder.retryPolicy :
                createDefaultRetryPolicy(builder.maxAttempts);
        this.validateInput = builder.validateInput;
        this.inputSchema = builder.inputSchema;
        this.timeoutMs = builder.timeoutMs;
        this.requireExplicitConfirmation = builder.requireExplicitConfirmation;
        this.description = builder.description;
    }

    private static StrictRetryPolicy createDefaultRetryPolicy(int maxAttempts) {
        return StrictRetryPolicy.builder()
                .maxAttempts(maxAttempts)
                .build();
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a config from legacy RetryConfig.
     */
    public static FuncAgentConfig fromRetryConfig(RetryConfig retryConfig) {
        return builder()
                .maxAttempts(retryConfig.maxAttempts())
                .initialDelay(retryConfig.initialDelay())
                .backoffMultiplier(retryConfig.backoffMultiplier())
                .build();
    }

    /**
     * Gets the execution mode.
     */
    public ExecutionMode getMode() {
        return mode;
    }

    /**
     * Gets the output format.
     */
    public OutputFormat getOutputFormat() {
        return outputFormat;
    }

    /**
     * Gets the output schema.
     */
    public OutputSchema getOutputSchema() {
        return outputSchema;
    }

    /**
     * Gets the retry policy.
     */
    public StrictRetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    /**
     * Checks if input validation is enabled.
     */
    public boolean isValidateInput() {
        return validateInput;
    }

    /**
     * Gets the input schema.
     */
    public OutputSchema getInputSchema() {
        return inputSchema;
    }

    /**
     * Gets the timeout in milliseconds.
     */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * Checks if explicit confirmation is required.
     */
    public boolean isRequireExplicitConfirmation() {
        return requireExplicitConfirmation;
    }

    /**
     * Gets the description.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Checks if this is a deterministic config.
     */
    public boolean isDeterministic() {
        return mode == ExecutionMode.DETERMINISTIC;
    }

    /**
     * Checks if this is a creative config.
     */
    public boolean isCreative() {
        return mode == ExecutionMode.CREATIVE;
    }

    /**
     * Builder for FuncAgentConfig.
     */
    public static class Builder {
        private ExecutionMode mode = ExecutionMode.DETERMINISTIC;
        private OutputFormat outputFormat = OutputFormat.JSON;
        private OutputSchema outputSchema;
        private StrictRetryPolicy retryPolicy;
        private int maxAttempts = 3;
        private long initialDelay = 1000;
        private double backoffMultiplier = 2.0;
        private boolean validateInput = false;
        private OutputSchema inputSchema;
        private long timeoutMs = 300000; // 5 minutes default
        private boolean requireExplicitConfirmation = false;
        private String description;

        private Builder() {
        }

        /**
         * Sets the execution mode.
         */
        public Builder mode(ExecutionMode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * Sets the output format.
         */
        public Builder outputFormat(OutputFormat format) {
            this.outputFormat = format;
            return this;
        }

        /**
         * Sets the output format from string.
         */
        public Builder outputFormat(String format) {
            this.outputFormat = OutputFormat.fromString(format);
            return this;
        }

        /**
         * Sets the output schema for validation.
         */
        public Builder outputSchema(OutputSchema schema) {
            this.outputSchema = schema;
            return this;
        }

        /**
         * Sets the retry policy.
         */
        public Builder retryPolicy(StrictRetryPolicy policy) {
            this.retryPolicy = policy;
            return this;
        }

        /**
         * Sets the maximum number of retry attempts.
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets the initial retry delay.
         */
        public Builder initialDelay(long delayMs) {
            this.initialDelay = delayMs;
            return this;
        }

        /**
         * Sets the initial retry delay.
         */
        public Builder initialDelay(Duration delay) {
            this.initialDelay = delay.toMillis();
            return this;
        }

        /**
         * Sets the backoff multiplier.
         */
        public Builder backoffMultiplier(double multiplier) {
            this.backoffMultiplier = multiplier;
            return this;
        }

        /**
         * Enables input validation with the given schema.
         */
        public Builder validateInput(OutputSchema schema) {
            this.validateInput = true;
            this.inputSchema = schema;
            return this;
        }

        /**
         * Sets the input schema.
         */
        public Builder inputSchema(OutputSchema schema) {
            this.inputSchema = schema;
            return this;
        }

        /**
         * Sets the execution timeout.
         */
        public Builder timeout(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * Sets the execution timeout.
         */
        public Builder timeout(Duration timeout) {
            this.timeoutMs = timeout.toMillis();
            return this;
        }

        /**
         * Requires explicit confirmation before execution.
         */
        public Builder requireExplicitConfirmation(boolean require) {
            this.requireExplicitConfirmation = require;
            return this;
        }

        /**
         * Sets the description of this agent.
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Builds the FuncAgentConfig.
         */
        public FuncAgentConfig build() {
            return new FuncAgentConfig(this);
        }
    }

    /**
     * Execution mode for agents.
     */
    public enum ExecutionMode {
        /**
         * Deterministic mode - predictable, validated output for critical processes.
         */
        DETERMINISTIC,

        /**
         * Creative mode - flexible output for generative tasks.
         */
        CREATIVE,

        /**
         * Hybrid mode - validated structure with creative content.
         */
        HYBRID
    }

    /**
     * Pre-configured configs for common use cases.
     */
    public static class Presets {
        /**
         * Financial calculation - strict JSON output, high retry count.
         */
        public static FuncAgentConfig financialCalculation() {
            return builder()
                    .mode(ExecutionMode.DETERMINISTIC)
                    .outputFormat(OutputFormat.JSON)
                    .maxAttempts(5)
                    .requireExplicitConfirmation(true)
                    .description("Financial calculation - deterministic with validation")
                    .build();
        }

        /**
         * ETL process - CSV output, large data handling.
         */
        public static FuncAgentConfig etlProcess() {
            return builder()
                    .mode(ExecutionMode.DETERMINISTIC)
                    .outputFormat(OutputFormat.CSV)
                    .timeout(Duration.ofMinutes(30))
                    .maxAttempts(3)
                    .description("ETL process - batch data processing")
                    .build();
        }

        /**
         * Compliance report - structured JSON with audit trail.
         */
        public static FuncAgentConfig complianceReport() {
            return builder()
                    .mode(ExecutionMode.DETERMINISTIC)
                    .outputFormat(OutputFormat.JSON)
                    .requireExplicitConfirmation(true)
                    .maxAttempts(3)
                    .description("Compliance report - auditable output")
                    .build();
        }

        /**
         * Creative content - flexible output, minimal validation.
         */
        public static FuncAgentConfig creativeContent() {
            return builder()
                    .mode(ExecutionMode.CREATIVE)
                    .outputFormat(OutputFormat.PLAIN)
                    .maxAttempts(1) // No retry for creative tasks
                    .description("Creative content - generative output")
                    .build();
        }

        /**
         * Data extraction - structured data from unstructured input.
         */
        public static FuncAgentConfig dataExtraction(OutputSchema schema) {
            return builder()
                    .mode(ExecutionMode.HYBRID)
                    .outputFormat(OutputFormat.JSON)
                    .outputSchema(schema)
                    .maxAttempts(2)
                    .description("Data extraction - hybrid mode with schema validation")
                    .build();
        }
    }
}
