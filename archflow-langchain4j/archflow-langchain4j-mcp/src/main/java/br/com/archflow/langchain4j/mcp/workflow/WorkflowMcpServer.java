package br.com.archflow.langchain4j.mcp.workflow;

import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.mcp.server.AbstractMcpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Server that exposes workflows as MCP tools.
 *
 * <p>This server allows external MCP clients to invoke workflows
 * as if they were native MCP tools. Each workflow is exposed as a tool
 * with input schema derived from the workflow's input parameters.</p>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * WorkflowMcpServer server = new WorkflowMcpServer();
 *
 * // Register workflows as tools
 * server.registerWorkflow("customer-support", "Customer support workflow",
 *     List.of("query", "customerId"),
 *     args -> CompletableFuture.completedFuture(
 *         WorkflowResult.success(Map.of("status", "resolved"))
 *     ));
 *
 * server.registerWorkflow("document-process", "Document processing workflow",
 *     List.of("documentId", "action"),
 *     args -> executeWorkflow(args));
 *
 * // Start STDIO transport
 * server.startStdio();
 * }</pre>
 *
 * @see AbstractMcpServer
 */
public class WorkflowMcpServer extends AbstractMcpServer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowMcpServer.class);

    private final Map<String, WorkflowToolDescriptor> workflows;
    private final Map<String, WorkflowExecutor> executors;

    /**
     * Create a new Workflow MCP Server.
     */
    public WorkflowMcpServer() {
        this("archflow-workflow-mcp", "1.0.0");
    }

    /**
     * Create a new Workflow MCP Server with custom name.
     *
     * @param name Server name
     * @param version Server version
     */
    public WorkflowMcpServer(String name, String version) {
        super(name, version, McpModel.ServerCapabilities.toolsOnly());
        this.workflows = new LinkedHashMap<>();
        this.executors = new ConcurrentHashMap<>();
    }

    // ---------------------------------------------------------------------------
    // WORKFLOW REGISTRATION
    // ---------------------------------------------------------------------------

    /**
     * Register a workflow as an MCP tool.
     *
     * @param workflowId Workflow ID
     * @param description Tool description
     * @param parameters Input parameter names
     * @param executor Workflow executor
     * @return This server
     */
    public WorkflowMcpServer registerWorkflow(String workflowId,
                                               String description,
                                               List<String> parameters,
                                               WorkflowExecutor executor) {
        return registerWorkflow(workflowId, workflowId, description, parameters, executor);
    }

    /**
     * Register a workflow as an MCP tool with custom tool name.
     *
     * @param workflowId Workflow ID (used for execution)
     * @param toolName Tool name (exposed to MCP)
     * @param description Tool description
     * @param parameters Input parameter names
     * @param executor Workflow executor
     * @return This server
     */
    public WorkflowMcpServer registerWorkflow(String workflowId,
                                               String toolName,
                                               String description,
                                               List<String> parameters,
                                               WorkflowExecutor executor) {
        // Create input schema
        Map<String, Object> schema = buildInputSchemaFromStrings(parameters);

        // Create tool descriptor
        WorkflowToolDescriptor descriptor = new WorkflowToolDescriptor(
                workflowId,
                toolName,
                description,
                schema,
                parameters
        );

        workflows.put(toolName, descriptor);
        executors.put(toolName, executor);

        log.info("Registered workflow '{}' as MCP tool '{}'", workflowId, toolName);

        return this;
    }

    /**
     * Register a workflow with full parameter specifications.
     *
     * @param workflowId Workflow ID
     * @param toolName Tool name
     * @param description Tool description
     * @param parameters Parameter specifications
     * @param executor Workflow executor
     * @return This server
     */
    public WorkflowMcpServer registerWorkflowWithSpecs(String workflowId,
                                               String toolName,
                                               String description,
                                               List<ParameterSpec> parameters,
                                               WorkflowExecutor executor) {
        Map<String, Object> schema = buildInputSchemaFromSpecs(parameters);
        List<String> paramNames = parameters.stream()
                .map(ParameterSpec::name)
                .toList();

        WorkflowToolDescriptor descriptor = new WorkflowToolDescriptor(
                workflowId,
                toolName,
                description,
                schema,
                paramNames
        );

        workflows.put(toolName, descriptor);
        executors.put(toolName, executor);

        log.info("Registered workflow '{}' as MCP tool '{}' with {} parameters",
                workflowId, toolName, parameters.size());

        return this;
    }

    /**
     * Unregister a workflow tool.
     *
     * @param toolName Tool name
     * @return This server
     */
    public WorkflowMcpServer unregisterWorkflow(String toolName) {
        workflows.remove(toolName);
        executors.remove(toolName);
        log.info("Unregistered MCP tool '{}'", toolName);
        return this;
    }

    // ---------------------------------------------------------------------------
    // MCP TOOL IMPLEMENTATION
    // ---------------------------------------------------------------------------

    @Override
    public List<McpModel.Tool> listTools() {
        return workflows.values().stream()
                .map(this::toMcpTool)
                .toList();
    }

    @Override
    public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments arguments) {
        String toolName = arguments.name();
        Map<String, Object> params = arguments.arguments();

        WorkflowToolDescriptor descriptor = workflows.get(toolName);
        if (descriptor == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Tool not found: " + toolName));
        }

        WorkflowExecutor executor = executors.get(toolName);
        if (executor == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No executor for tool: " + toolName));
        }

        try {
            return executor.execute(params)
                    .thenApply(this::toToolResult)
                    .exceptionally(e -> {
                        log.error("Error executing tool: {}", toolName, e);
                        return McpModel.ToolResult.error("Execution error: " + e.getMessage());
                    });
        } catch (Exception e) {
            log.error("Error executing tool: {}", toolName, e);
            return CompletableFuture.completedFuture(
                    McpModel.ToolResult.error("Execution error: " + e.getMessage()));
        }
    }

    // ---------------------------------------------------------------------------
    // MCP RESOURCES (optional - expose workflow definitions)
    // ---------------------------------------------------------------------------

    /**
     * Register workflow definitions as resources.
     *
     * @return This server
     */
    public WorkflowMcpServer enableWorkflowResources() {
        // Each workflow can be exposed as a readable resource
        // showing its definition/status
        for (WorkflowToolDescriptor workflow : workflows.values()) {
            String uri = "workflow://" + workflow.toolName();
            // Resources would be added here
        }
        return this;
    }

    // ---------------------------------------------------------------------------
    // UTILITY METHODS
    // ---------------------------------------------------------------------------

    /**
     * Get number of registered workflows.
     *
     * @return Workflow count
     */
    public int getWorkflowCount() {
        return workflows.size();
    }

    /**
     * Get all registered workflow descriptors.
     *
     * @return List of descriptors
     */
    public List<WorkflowToolDescriptor> getWorkflows() {
        return List.copyOf(workflows.values());
    }

    /**
     * Check if a workflow is registered.
     *
     * @param toolName Tool name
     * @return true if registered
     */
    public boolean hasWorkflow(String toolName) {
        return workflows.containsKey(toolName);
    }

    // ---------------------------------------------------------------------------
    // INTERNAL HELPERS
    // ---------------------------------------------------------------------------

    private McpModel.Tool toMcpTool(WorkflowToolDescriptor descriptor) {
        return new McpModel.Tool(
                descriptor.toolName(),
                descriptor.description(),
                descriptor.inputSchema()
        );
    }

    private Map<String, Object> buildInputSchemaFromStrings(List<String> parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (String param : parameters) {
            properties.put(param, Map.of(
                    "type", "string",
                    "description", "Parameter: " + param
            ));
            required.add(param);
        }

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required
        );
    }

    private Map<String, Object> buildInputSchemaFromSpecs(List<ParameterSpec> parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ParameterSpec param : parameters) {
            Map<String, Object> paramDef = new LinkedHashMap<>();
            paramDef.put("type", param.type());
            paramDef.put("description", param.description());

            if (param.enumValues() != null && !param.enumValues().isEmpty()) {
                paramDef.put("enum", param.enumValues());
            }

            properties.put(param.name(), paramDef);
            if (param.required()) {
                required.add(param.name());
            }
        }

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required
        );
    }

    /**
     * Convert workflow result to MCP tool result.
     */
    protected McpModel.ToolResult toToolResult(WorkflowResult workflowResult) {
        if (workflowResult.error() != null) {
            return McpModel.ToolResult.error("Workflow error: " + workflowResult.error());
        }

        // Build result content
        Map<String, Object> output = workflowResult.output();
        StringBuilder textResult = new StringBuilder();

        if (output != null && !output.isEmpty()) {
            textResult.append("Workflow execution completed successfully.\n\n");
            textResult.append("Output:\n");
            for (Map.Entry<String, Object> entry : output.entrySet()) {
                textResult.append("  ").append(entry.getKey())
                        .append(": ").append(entry.getValue()).append("\n");
            }
        } else {
            textResult.append("Workflow execution completed successfully.");
        }

        return McpModel.ToolResult.text(textResult.toString());
    }

    // ---------------------------------------------------------------------------
    // NESTED CLASSES
    // ---------------------------------------------------------------------------

    /**
     * Descriptor for a workflow exposed as an MCP tool.
     */
    public record WorkflowToolDescriptor(
            String workflowId,
            String toolName,
            String description,
            Map<String, Object> inputSchema,
            List<String> parameterNames
    ) {
        public WorkflowToolDescriptor {
            if (workflowId == null || workflowId.isBlank()) {
                throw new IllegalArgumentException("workflowId cannot be blank");
            }
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName cannot be blank");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("description cannot be blank");
            }
        }
    }

    /**
     * Specification for a workflow parameter.
     */
    public record ParameterSpec(
            String name,
            String description,
            String type,
            boolean required,
            List<String> enumValues
    ) {
        public ParameterSpec {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name cannot be blank");
            }
            if (type == null) {
                type = "string";
            }
        }

        public ParameterSpec(String name, String description, String type, boolean required) {
            this(name, description, type, required, null);
        }

        public ParameterSpec(String name, String description) {
            this(name, description, "string", true, null);
        }

        /**
         * Create an enum parameter.
         */
        public static ParameterSpec enumParam(String name, String description, List<String> values) {
            return new ParameterSpec(name, description, "string", true, values);
        }

        /**
         * Create an optional parameter.
         */
        public static ParameterSpec optional(String name, String description, String type) {
            return new ParameterSpec(name, description, type, false, null);
        }
    }

    /**
     * Result of workflow execution.
     */
    public record WorkflowResult(
            Map<String, Object> output,
            String error,
            long executionTimeMs
    ) {
        /**
         * Create a successful result.
         */
        public static WorkflowResult success(Map<String, Object> output) {
            return new WorkflowResult(output, null, 0);
        }

        /**
         * Create an error result.
         */
        public static WorkflowResult error(String error) {
            return new WorkflowResult(null, error, 0);
        }

        /**
         * Check if execution was successful.
         */
        public boolean isSuccess() {
            return error == null;
        }
    }

    /**
     * Executor for workflow tools.
     */
    @FunctionalInterface
    public interface WorkflowExecutor {
        /**
         * Execute the workflow with given parameters.
         *
         * @param parameters Tool parameters
         * @return Workflow result
         */
        CompletableFuture<WorkflowResult> execute(Map<String, Object> parameters);
    }
}
