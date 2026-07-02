package br.com.archflow.api.mcp.server;

import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.mcp.McpServer;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Exposes every stored workflow as an MCP tool ({@code workflow_<id>}),
 * so any MCP client (Claude, IDEs, other agents) can list and run
 * archflow workflows. The consumption counterpart of the MCP-client
 * support the platform already has.
 *
 * <p>Tool calls run through the same engine wiring as
 * {@code POST /api/workflows/{id}/execute} — an execution record is
 * created and completed, so runs triggered over MCP show up in the
 * executions history like any other run.
 */
public class WorkflowMcpServer implements McpServer {

    static final String TOOL_PREFIX = "workflow_";

    private final InMemoryWorkflowRuntimeStore store;
    private final WorkflowDeserializer deserializer;
    private final FlowEngine flowEngine;
    private final FlowRepository flowRepository;
    private final ObjectMapper json;

    public WorkflowMcpServer(InMemoryWorkflowRuntimeStore store,
                             WorkflowDeserializer deserializer,
                             FlowEngine flowEngine,
                             FlowRepository flowRepository,
                             ObjectMapper json) {
        this.store = Objects.requireNonNull(store, "store");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer");
        this.flowEngine = Objects.requireNonNull(flowEngine, "flowEngine");
        this.flowRepository = Objects.requireNonNull(flowRepository, "flowRepository");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public McpModel.ServerMetadata getServerInfo() {
        return new McpModel.ServerMetadata("archflow", "1.0.0");
    }

    @Override
    public McpModel.ServerCapabilities getCapabilities() {
        return McpModel.ServerCapabilities.toolsOnly();
    }

    @Override
    public CompletableFuture<McpModel.InitializeResult> initialize(McpModel.ClientInfo clientInfo) {
        return CompletableFuture.completedFuture(
                new McpModel.InitializeResult(getCapabilities(), getServerInfo()));
    }

    @Override
    public void initialized() {
        // stateless — nothing to do
    }

    @Override
    public void shutdown() {
        // stateless — nothing to do
    }

    @Override
    public List<McpModel.Tool> listTools() {
        List<McpModel.Tool> tools = new ArrayList<>();
        for (Map<String, Object> workflow : store.workflows()) {
            String id = String.valueOf(workflow.get("id"));
            tools.add(new McpModel.Tool(
                    TOOL_PREFIX + id,
                    toolDescription(workflow),
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "input", Map.of(
                                            "type", "string",
                                            "description", "Input passed to the workflow's entry step")),
                            "required", List.of())));
        }
        return tools;
    }

    @Override
    public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments arguments) {
        String name = arguments.name();
        if (name == null || !name.startsWith(TOOL_PREFIX)) {
            return CompletableFuture.completedFuture(
                    McpModel.ToolResult.error("Unknown tool: " + name));
        }
        String workflowId = name.substring(TOOL_PREFIX.length());
        Map<String, Object> workflow = store.getWorkflow(workflowId);
        if (workflow == null) {
            return CompletableFuture.completedFuture(
                    McpModel.ToolResult.error("Unknown workflow: " + workflowId));
        }

        var execution = store.createExecution(workflowId, workflowName(workflow));
        String executionId = String.valueOf(execution.get("id"));

        Map<String, Object> flowJson = new HashMap<>(workflow);
        flowJson.put("id", executionId);
        Flow flow = deserializer.toFlow(flowJson);
        flowRepository.save(flow);

        ExecutionContext ctx = new DefaultExecutionContext(
                null, "mcp", executionId,
                MessageWindowChatMemory.builder().maxMessages(20).build());
        arguments.arguments().forEach(ctx::set);

        // MCP tool calls are request/response: wait for the run to finish and
        // return its output. The execution record is completed either way so
        // the run appears in the executions history.
        return flowEngine.execute(flow, ctx).handle((result, err) -> {
            if (err != null) {
                store.completeExecution(executionId, "FAILED", rootMessage(err));
                return McpModel.ToolResult.error(
                        "Workflow failed (" + executionId + "): " + rootMessage(err));
            }
            String status = result != null && result.getStatus() != null
                    ? result.getStatus().name() : "COMPLETED";
            store.completeExecution(executionId, status, null);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("executionId", executionId);
            payload.put("status", status);
            payload.put("output", result != null ? result.getOutput().orElse(null) : null);
            return McpModel.ToolResult.text(toJson(payload));
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String toolDescription(Map<String, Object> workflow) {
        String name = workflowName(workflow);
        String description = null;
        if (workflow.get("metadata") instanceof Map<?, ?> meta && meta.get("description") != null) {
            description = meta.get("description").toString();
        }
        String text = description == null || description.isBlank()
                ? "Runs the archflow workflow \"" + name + "\""
                : "Runs the archflow workflow \"" + name + "\": " + description;
        return text;
    }

    private static String workflowName(Map<String, Object> workflow) {
        if (workflow.get("metadata") instanceof Map<?, ?> meta && meta.get("name") != null) {
            return meta.get("name").toString();
        }
        return String.valueOf(workflow.get("id"));
    }

    private static String rootMessage(Throwable err) {
        Throwable cause = err;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
