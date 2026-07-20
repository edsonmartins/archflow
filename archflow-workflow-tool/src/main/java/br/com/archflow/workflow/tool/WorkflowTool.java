package br.com.archflow.workflow.tool;

import br.com.archflow.model.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A tool that executes a workflow as its implementation.
 *
 * <p>This enables the Workflow-as-Tool pattern, where workflows can be:
 * <ul>
 *   <li>Composed within other workflows</li>
 *   <li>Invoked by AI agents as tools</li>
 *   <li>Reused across multiple contexts</li>
 *   <li>Versioned and managed independently</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * WorkflowTool tool = WorkflowTool.builder()
 *     .id("data-processor")
 *     .description("Processes and validates data")
 *     .workflow(myWorkflow)
 *     .executor((input) -> flowEngine.execute(myFlow, context).get().getOutput().orElse(null))
 *     .inputSchema(Map.of(
 *         "data", "string",
 *         "format", "string"
 *     ))
 *     .build();
 *
 * ToolResult result = tool.execute(Map.of(
 *     "data", "sample data",
 *     "format", "json"
 * ));
 * }</pre>
 *
 * @see WorkflowToolRegistry
 * @see WorkflowToolResult
 */
public class WorkflowTool {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTool.class);

    private final String id;
    private final String name;
    private final String description;
    private final Workflow workflow;
    private final Function<Map<String, Object>, Object> executor;
    private final Map<String, Object> inputSchema;
    private final Map<String, Object> outputSchema;
    private final Duration timeout;
    private final boolean async;
    private final int maxRetries;
    private final Map<String, Object> metadata;

    private WorkflowTool(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id is required");
        this.name = builder.name != null ? builder.name : builder.id;
        this.description = builder.description;
        this.workflow = Objects.requireNonNull(builder.workflow, "workflow is required");
        this.executor = builder.executor;
        this.inputSchema = builder.inputSchema != null ? Map.copyOf(builder.inputSchema) : Map.of();
        this.outputSchema = builder.outputSchema != null ? Map.copyOf(builder.outputSchema) : Map.of();
        this.timeout = builder.timeout;
        this.async = builder.async;
        this.maxRetries = builder.maxRetries >= 0 ? builder.maxRetries : 0;
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Map.of();
    }

    /**
     * Creates a new builder for WorkflowTool.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a WorkflowTool from a workflow, using the given executor to run it.
     *
     * <p>This module has no workflow engine, so it cannot derive an executor from
     * the {@link Workflow} alone — the caller must supply one (typically a closure
     * over a flow engine, e.g. {@code input -> flowEngine.execute(flow, input)}).
     *
     * @param workflow the workflow this tool represents
     * @param executor function that actually executes the workflow
     * @return a fully executable WorkflowTool
     */
    public static WorkflowTool from(Workflow workflow, Function<Map<String, Object>, Object> executor) {
        Objects.requireNonNull(executor, "executor is required");
        return builder()
                .id(workflow.getId())
                .name(workflow.getName())
                .description(workflow.getDescription())
                .workflow(workflow)
                .executor(executor)
                .build();
    }

    /**
     * Unsupported: this module has no workflow engine, so a tool created from a
     * {@link Workflow} alone would have no way to execute it.
     *
     * <p>Failing here (at creation) is deliberate — it is more honest than
     * returning a tool that only blows up when {@code execute()} is called.
     * Use {@link #from(Workflow, Function)} and supply an executor instead.
     *
     * @throws UnsupportedOperationException always
     * @deprecated use {@link #from(Workflow, Function)} with an explicit executor
     */
    @Deprecated
    public static WorkflowTool from(Workflow workflow) {
        throw new UnsupportedOperationException(
                "WorkflowTool.from(Workflow) cannot create an executable tool: module "
                + "archflow-workflow-tool has no workflow engine to run '" + workflow.getId()
                + "'. Use WorkflowTool.from(workflow, executor) and supply an executor "
                + "(e.g. a closure over your FlowEngine).");
    }

    /**
     * Executes the workflow with the given input.
     *
     * <p>Builder options are honored as follows:
     * <ul>
     *   <li>{@code timeout} — each attempt runs at most this long; on expiry the
     *       attempt fails with a timeout error (and the underlying task is cancelled).</li>
     *   <li>{@code maxRetries} — failed attempts (including timeouts) are re-executed
     *       up to this many additional times; the last error is reported on exhaustion.</li>
     *   <li>{@code async} — a hint for callers: this method is always synchronous;
     *       tools flagged async should preferably be invoked via {@link #executeAsync(Map)}.</li>
     * </ul>
     *
     * @param input The input parameters for the workflow
     * @return The result of the workflow execution
     */
    public WorkflowToolResult execute(Map<String, Object> input) {
        Instant start = Instant.now();
        String executionId = java.util.UUID.randomUUID().toString();
        int maxAttempts = maxRetries + 1;
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.debug("Executing workflow tool {} (executionId: {}, attempt {}/{})",
                        id, executionId, attempt, maxAttempts);

                Object output = executeAttempt(input);

                Duration duration = Duration.between(start, Instant.now());
                log.debug("Workflow tool {} completed in {}ms", id, duration.toMillis());
                return WorkflowToolResult.success(output, duration, executionId);

            } catch (Exception e) {
                lastError = e;
                if (attempt < maxAttempts) {
                    log.warn("Workflow tool {} attempt {}/{} failed: {} — retrying",
                            id, attempt, maxAttempts, e.getMessage());
                }
            }
        }

        Duration duration = Duration.between(start, Instant.now());
        log.error("Workflow tool {} failed after {}ms ({} attempt(s))",
                id, duration.toMillis(), maxAttempts, lastError);

        String message = lastError.getMessage() != null
                ? lastError.getMessage()
                : lastError.getClass().getSimpleName();
        return WorkflowToolResult.failure(message, duration, executionId);
    }

    /**
     * Runs a single execution attempt, enforcing the configured timeout (if any).
     */
    private Object executeAttempt(Map<String, Object> input) throws Exception {
        if (timeout == null) {
            return executeWorkflow(input);
        }

        java.util.concurrent.CompletableFuture<Object> future =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> executeWorkflow(input));
        try {
            return future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new java.util.concurrent.TimeoutException(
                    "Workflow tool '" + id + "' timed out after " + timeout.toMillis() + "ms");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    /**
     * Executes the workflow asynchronously.
     */
    public java.util.concurrent.CompletableFuture<WorkflowToolResult> executeAsync(Map<String, Object> input) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> execute(input));
    }

    /**
     * Executes this tool as a function.
     */
    public Object apply(Map<String, Object> input) {
        return execute(input).output();
    }

    private Object executeWorkflow(Map<String, Object> input) {
        if (executor != null) {
            // Use the provided executor function
            log.debug("Using custom executor for workflow tool {}", id);
            return executor.apply(input);
        }

        // Sem executor não há execução real — devolver metadados como
        // placeholder mascarava a má configuração e o caller (inclusive um
        // LLM) consumia o "resultado" como se o workflow tivesse rodado.
        throw new IllegalStateException(
                "Workflow tool '" + id + "' has no executor configured — it cannot execute. "
                + "Configure one with .executor() in the builder.");
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public Map<String, Object> getOutputSchema() {
        return outputSchema;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public boolean isAsync() {
        return async;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Creates a new builder with values from this tool.
     */
    public Builder toBuilder() {
        return new Builder()
                .id(this.id)
                .name(this.name)
                .description(this.description)
                .workflow(this.workflow)
                .executor(this.executor)
                .inputSchema(this.inputSchema)
                .outputSchema(this.outputSchema)
                .timeout(this.timeout)
                .async(this.async)
                .maxRetries(this.maxRetries)
                .metadata(this.metadata);
    }

    /**
     * Builder for constructing WorkflowTool instances.
     */
    public static class Builder {
        private String id;
        private String name;
        private String description;
        private Workflow workflow;
        private Function<Map<String, Object>, Object> executor;
        private Map<String, Object> inputSchema;
        private Map<String, Object> outputSchema;
        private Duration timeout;
        private boolean async;
        private int maxRetries = 0;
        private Map<String, Object> metadata;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder workflow(Workflow workflow) {
            this.workflow = workflow;
            return this;
        }

        /**
         * Sets the executor function for this workflow tool.
         * The executor should accept the input map and return the workflow output.
         *
         * @param executor Function to execute the workflow
         * @return This builder
         */
        public Builder executor(Function<Map<String, Object>, Object> executor) {
            this.executor = executor;
            return this;
        }

        public Builder inputSchema(Map<String, Object> inputSchema) {
            this.inputSchema = inputSchema;
            return this;
        }

        public Builder outputSchema(Map<String, Object> outputSchema) {
            this.outputSchema = outputSchema;
            return this;
        }

        /**
         * Maximum duration of a single execution attempt. Enforced by
         * {@link WorkflowTool#execute(Map)}: on expiry the attempt fails with a
         * timeout error and is retried if {@code maxRetries > 0}. {@code null}
         * (default) means no timeout.
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Marks this tool as preferring asynchronous invocation. This is a hint
         * for callers ({@link WorkflowTool#execute(Map)} is always synchronous);
         * async-flagged tools should be invoked via {@link WorkflowTool#executeAsync(Map)}.
         */
        public Builder async(boolean async) {
            this.async = async;
            return this;
        }

        /**
         * Number of additional executions attempted by {@link WorkflowTool#execute(Map)}
         * after a failed attempt (including timeouts). Default 0 (no retries).
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public WorkflowTool build() {
            return new WorkflowTool(this);
        }
    }
}
