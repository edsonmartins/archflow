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
     * Creates a WorkflowTool from a workflow.
     */
    public static WorkflowTool from(Workflow workflow) {
        return builder()
                .id(workflow.getId())
                .name(workflow.getName())
                .description(workflow.getDescription())
                .workflow(workflow)
                .build();
    }

    /**
     * Executes the workflow with the given input.
     *
     * @param input The input parameters for the workflow
     * @return The result of the workflow execution
     */
    public WorkflowToolResult execute(Map<String, Object> input) {
        Instant start = Instant.now();
        String executionId = java.util.UUID.randomUUID().toString();

        try {
            log.debug("Executing workflow tool {} (executionId: {})", id, executionId);

            // Execute the workflow
            Object output = executeWorkflow(input);

            Instant end = Instant.now();
            Duration duration = Duration.between(start, end);

            log.debug("Workflow tool {} completed in {}ms", id, duration.toMillis());

            return WorkflowToolResult.success(output, duration, executionId);

        } catch (Exception e) {
            Instant end = Instant.now();
            Duration duration = Duration.between(start, end);

            log.error("Workflow tool {} failed after {}ms", id, duration.toMillis(), e);

            return WorkflowToolResult.failure(e.getMessage(), duration, executionId);
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

        // Fallback: return the workflow metadata as a placeholder
        // Users should configure an executor for actual workflow execution
        log.warn("No executor configured for workflow tool {}, returning metadata placeholder. " +
                 "Configure an executor using .executor() in the builder.", id);
        return Map.of(
                "workflowId", workflow.getId(),
                "workflowName", workflow.getName(),
                "input", input,
                "metadata", workflow.getMetadata(),
                "_note", "This is a placeholder. Configure a WorkflowExecutor for actual execution."
        );
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

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder async(boolean async) {
            this.async = async;
            return this;
        }

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
