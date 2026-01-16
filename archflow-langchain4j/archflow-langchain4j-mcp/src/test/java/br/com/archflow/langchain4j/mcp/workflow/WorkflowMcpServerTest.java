package br.com.archflow.langchain4j.mcp.workflow;

import br.com.archflow.langchain4j.mcp.JsonRpc;
import br.com.archflow.langchain4j.mcp.McpModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WorkflowMcpServer.
 */
class WorkflowMcpServerTest {

    private WorkflowMcpServer server;

    @BeforeEach
    void setUp() {
        server = new WorkflowMcpServer();
    }

    @Test
    void testServerCreation() {
        assertEquals("archflow-workflow-mcp", server.getServerInfo().name());
        assertTrue(server.supportsTools());
        assertFalse(server.supportsResources());
        assertFalse(server.supportsPrompts());
    }

    @Test
    void testRegisterWorkflow() {
        server.registerWorkflow(
                "test-flow",
                "Test workflow",
                List.of("input"),
                args -> CompletableFuture.completedFuture(
                        WorkflowMcpServer.WorkflowResult.success(Map.of("result", "done"))
                )
        );

        assertEquals(1, server.getWorkflowCount());
        assertTrue(server.hasWorkflow("test-flow"));
        assertFalse(server.hasWorkflow("unknown"));
    }

    @Test
    void testListTools() {
        server.registerWorkflow(
                "workflow1",
                "First workflow",
                List.of("param1", "param2"),
                args -> CompletableFuture.completedFuture(
                        WorkflowMcpServer.WorkflowResult.success(Map.of())
                )
        );

        server.registerWorkflow(
                "workflow2",
                "Second workflow",
                List.of("data"),
                args -> CompletableFuture.completedFuture(
                        WorkflowMcpServer.WorkflowResult.success(Map.of())
                )
        );

        List<McpModel.Tool> tools = server.listTools();
        assertEquals(2, tools.size());
        assertEquals("workflow1", tools.get(0).name());
        assertEquals("workflow2", tools.get(1).name());
    }

    @Test
    void testCallTool() throws Exception {
        server.registerWorkflow(
                "echo-flow",
                "Echo workflow",
                List.of("message"),
                args -> CompletableFuture.completedFuture(
                        WorkflowMcpServer.WorkflowResult.success(
                                Map.of("echo", "Echo: " + args.get("message"))
                        )
                )
        );

        McpModel.ToolArguments args = new McpModel.ToolArguments(
                "echo-flow",
                Map.of("message", "Hello!")
        );

        McpModel.ToolResult result = server.callTool(args).get();

        assertFalse(result.isError());
        assertTrue(result.content().get(0).text().contains("Hello!"));
    }

    @Test
    void testCallToolNotFound() {
        server.registerWorkflow(
                "test",
                "Test",
                List.of(),
                args -> CompletableFuture.completedFuture(
                        WorkflowMcpServer.WorkflowResult.success(Map.of())
                )
        );

        McpModel.ToolArguments args = new McpModel.ToolArguments(
                "unknown",
                Map.of()
        );

        assertThrows(Exception.class, () -> {
            server.callTool(args).get();
        });
    }

    @Test
    void testUnregisterWorkflow() {
        server.registerWorkflow(
                "test",
                "Test",
                List.of(),
                args -> CompletableFuture.completedFuture(
                        WorkflowMcpServer.WorkflowResult.success(Map.of())
                )
        );
        assertEquals(1, server.getWorkflowCount());

        server.unregisterWorkflow("test");
        assertEquals(0, server.getWorkflowCount());
    }

    @Test
    void testRegisterWithCustomToolName() {
        server.registerWorkflow(
                "internal-flow-id",
                "external_tool_name",
                "Tool description",
                List.of("param"),
                args -> CompletableFuture.completedFuture(
                        WorkflowMcpServer.WorkflowResult.success(Map.of())
                )
        );

        assertTrue(server.hasWorkflow("external_tool_name"));
        List<McpModel.Tool> tools = server.listTools();
        assertEquals("external_tool_name", tools.get(0).name());
    }

    @Test
    void testHandleRequestToolsList() {
        server.registerWorkflow(
                "test",
                "Test",
                List.of(),
                args -> CompletableFuture.completedFuture(
                        WorkflowMcpServer.WorkflowResult.success(Map.of())
                )
        );

        JsonRpc.Request request = JsonRpc.Request.create("tools/list", Map.of());
        JsonRpc.Response response = server.handleRequest(request);

        assertFalse(response.isError());
        assertNotNull(response.result());
    }

    @Test
    void testHandleRequestToolCall() {
        server.registerWorkflow(
                "echo",
                "Echo",
                List.of("msg"),
                args -> CompletableFuture.completedFuture(
                        WorkflowMcpServer.WorkflowResult.success(
                                Map.of("result", "test")
                        )
                )
        );

        Map<String, Object> params = Map.of(
                "name", "echo",
                "arguments", Map.of("msg", "test")
        );

        JsonRpc.Request request = JsonRpc.Request.create("tools/call", params);
        JsonRpc.Response response = server.handleRequest(request);

        assertFalse(response.isError());
        assertNotNull(response.result());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        assertNotNull(result.get("content"));
    }

    @Test
    void testGetWorkflows() {
        server.registerWorkflow(
                "w1",
                "Workflow 1",
                List.of("a"),
                args -> CompletableFuture.completedFuture(
                        WorkflowMcpServer.WorkflowResult.success(Map.of())
                )
        );
        server.registerWorkflow(
                "w2",
                "Workflow 2",
                List.of("b"),
                args -> CompletableFuture.completedFuture(
                        WorkflowMcpServer.WorkflowResult.success(Map.of())
                )
        );

        List<WorkflowMcpServer.WorkflowToolDescriptor> workflows = server.getWorkflows();
        assertEquals(2, workflows.size());
    }

    @Test
    void testWorkflowResultSuccess() {
        WorkflowMcpServer.WorkflowResult result = WorkflowMcpServer.WorkflowResult.success(
                Map.of("key", "value")
        );

        assertTrue(result.isSuccess());
        assertNull(result.error());
        assertEquals("value", result.output().get("key"));
    }

    @Test
    void testWorkflowResultError() {
        WorkflowMcpServer.WorkflowResult result = WorkflowMcpServer.WorkflowResult.error("Test error");

        assertFalse(result.isSuccess());
        assertEquals("Test error", result.error());
        assertNull(result.output());
    }

    @Test
    void testParameterSpecEnum() {
        WorkflowMcpServer.ParameterSpec param = WorkflowMcpServer.ParameterSpec.enumParam(
                "action",
                "Action to perform",
                List.of("create", "update", "delete")
        );

        assertEquals("action", param.name());
        assertEquals("string", param.type());
        assertTrue(param.required());
        assertEquals(3, param.enumValues().size());
    }

    @Test
    void testParameterSpecOptional() {
        WorkflowMcpServer.ParameterSpec param = WorkflowMcpServer.ParameterSpec.optional(
                "count",
                "Number of items",
                "integer"
        );

        assertEquals("count", param.name());
        assertEquals("integer", param.type());
        assertFalse(param.required());
    }

    @Test
    void testWorkflowWithParameterSpecs() throws Exception {
        server.registerWorkflowWithSpecs(
                "test-flow",
                "test_tool",
                "Test workflow with parameter specs",
                List.of(
                        WorkflowMcpServer.ParameterSpec.enumParam(
                                "action",
                                "Action",
                                List.of("run", "stop")
                        ),
                        WorkflowMcpServer.ParameterSpec.optional(
                                "count",
                                "Count",
                                "integer"
                        )
                ),
                args -> CompletableFuture.completedFuture(
                        WorkflowMcpServer.WorkflowResult.success(
                                Map.of("action", args.get("action"))
                        )
                )
        );

        McpModel.Tool tool = server.listTools().get(0);
        assertEquals("test_tool", tool.name());

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = tool.inputSchema();
        assertTrue(schema.containsKey("properties"));
    }

    @Test
    void testExecutorExceptionHandling() throws Exception {
        server.registerWorkflow(
                "error-flow",
                "Error workflow",
                List.of(),
                args -> CompletableFuture.failedFuture(
                        new RuntimeException("Test error")
                )
        );

        McpModel.ToolArguments args = new McpModel.ToolArguments(
                "error-flow",
                Map.of()
        );

        McpModel.ToolResult result = server.callTool(args).get();

        // Should return error result
        assertTrue(result.isError());
        assertTrue(result.content().get(0).text().contains("Execution error"));
    }
}
