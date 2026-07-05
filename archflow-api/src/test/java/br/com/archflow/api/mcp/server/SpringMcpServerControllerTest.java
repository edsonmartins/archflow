package br.com.archflow.api.mcp.server;

import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.langchain4j.mcp.JsonRpc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SpringMcpServerController")
class SpringMcpServerControllerTest {

    private SpringMcpServerController controller;

    @BeforeEach
    void setUp() {
        var store = new InMemoryWorkflowRuntimeStore(); // seeds wf-demo-001
        var engine = mock(FlowEngine.class);
        WorkflowDeserializer deserializer = mock(WorkflowDeserializer.class);
        when(deserializer.toFlow(any())).thenReturn(mock(br.com.archflow.model.flow.Flow.class));
        var server = new WorkflowMcpServer(
                store, deserializer, engine, mock(FlowRepository.class), new ObjectMapper());
        controller = new SpringMcpServerController(server);
    }

    private ResponseEntity<JsonRpc.Response> handle(Map<String, Object> message) throws Exception {
        return controller.handle(message).get();
    }

    @Test
    @DisplayName("tools/list returns a non-error result over the async transport")
    void toolsList() throws Exception {
        var response = handle(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isError()).isFalse();
        assertThat(response.getBody().id()).isEqualTo(1);
    }

    @Test
    @DisplayName("initialize succeeds even when the client omits capabilities/clientInfo")
    void initializeWithoutCapabilities() throws Exception {
        // A minimal (non-strict) client may send only protocolVersion. The handshake
        // must not NPE into a -32602 'Invalid client info'.
        var response = handle(Map.of(
                "jsonrpc", "2.0", "id", 2, "method", "initialize",
                "params", Map.of("protocolVersion", "2024-11-05")));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isError())
                .as("initialize with no capabilities/clientInfo must not be an error")
                .isFalse();
    }

    @Test
    @DisplayName("a notification (no id) is acknowledged with 202 and no body")
    void notificationAcknowledged() throws Exception {
        var response = handle(Map.of(
                "jsonrpc", "2.0", "method", "notifications/initialized"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNull();
    }
}
