package br.com.archflow.api.mcp.server;

import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowMcpServerTest {

    private InMemoryWorkflowRuntimeStore store;
    private FlowEngine engine;
    private WorkflowMcpServer server;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowRuntimeStore(); // seeds wf-demo-001
        engine = mock(FlowEngine.class);
        WorkflowDeserializer deserializer = mock(WorkflowDeserializer.class);
        when(deserializer.toFlow(any())).thenReturn(mock(Flow.class));
        server = new WorkflowMcpServer(
                store, deserializer, engine, mock(FlowRepository.class), new ObjectMapper());
    }

    @Test
    @DisplayName("lists every stored workflow as a prefixed MCP tool")
    void listTools() {
        List<McpModel.Tool> tools = server.listTools();

        assertThat(tools).anySatisfy(tool -> {
            assertThat(tool.name()).isEqualTo("workflow_wf-demo-001");
            assertThat(tool.description()).contains("Customer Support Agent");
            assertThat(tool.inputSchema()).containsKey("properties");
        });
    }

    @Test
    @DisplayName("capabilities advertise tools only")
    void capabilities() {
        assertThat(server.getCapabilities().tools()).isNotNull();
        assertThat(server.getCapabilities().resources()).isNull();
        assertThat(server.getServerInfo().name()).isEqualTo("archflow");
    }

    @Test
    @DisplayName("calling an unknown tool returns an MCP error result, not an exception")
    void unknownTool() throws Exception {
        McpModel.ToolResult result = server
                .callTool(new McpModel.ToolArguments("workflow_ghost", Map.of()))
                .get();

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("a successful run completes the execution record and returns its id")
    void successfulRun() throws Exception {
        FlowResult flowResult = mock(FlowResult.class);
        when(flowResult.getStatus()).thenReturn(ExecutionStatus.COMPLETED);
        when(flowResult.getOutput()).thenReturn(Optional.of(Map.of("answer", 42)));
        when(engine.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(flowResult));

        McpModel.ToolResult result = server
                .callTool(new McpModel.ToolArguments("workflow_wf-demo-001", Map.of("input", "hi")))
                .get();

        assertThat(result.isError()).isFalse();
        String text = result.content().get(0).text();
        assertThat(text).contains("COMPLETED").contains("answer");

        // The run is tracked like any other execution.
        String executionId = text.replaceAll(".*\"executionId\":\"([^\"]+)\".*", "$1");
        assertThat(store.getExecution(executionId)).isNotNull();
        assertThat(store.getExecution(executionId).get("status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("an engine failure marks the execution FAILED and surfaces the cause")
    void failedRun() throws Exception {
        when(engine.execute(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        McpModel.ToolResult result = server
                .callTool(new McpModel.ToolArguments("workflow_wf-demo-001", Map.of()))
                .get();

        assertThat(result.isError()).isTrue();
        assertThat(result.content().get(0).text()).contains("boom");
    }
}
