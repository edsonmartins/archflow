package br.com.archflow.api.agent;

import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.agent.dto.InvokeRequest;
import br.com.archflow.api.agent.dto.InvokeResponse;
import br.com.archflow.api.agent.impl.AgentControllerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AgentControllerImpl")
class AgentControllerImplTest {

    private InMemoryAgentInvocationQueue queue;
    private AgentControllerImpl controller;

    @BeforeEach
    void setUp() {
        queue = new InMemoryAgentInvocationQueue();
        controller = new AgentControllerImpl(queue);
    }

    @Test
    @DisplayName("should return ACCEPTED response for invoke and enqueue")
    void shouldReturnAccepted() {
        var request = new InvokeRequest("t1", "s1", Map.of("key", "value"));
        var response = controller.invoke("agent-1", request);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.requestId()).isNotNull();
        assertThat(response.tenantId()).isEqualTo("t1");
        assertThat(response.agentId()).isEqualTo("agent-1");
        assertThat(response.timestamp()).isNotNull();

        assertThat(queue.size()).isEqualTo(1);
        InvocationRequest enqueued = queue.poll().orElseThrow();
        assertThat(enqueued.tenantId()).isEqualTo("t1");
        assertThat(enqueued.agentId()).isEqualTo("agent-1");
        assertThat(enqueued.payload()).containsEntry("key", "value");
        assertThat(enqueued.payload()).containsEntry("sessionId", "s1");
    }

    @Test
    @DisplayName("should fail when queue is not wired")
    void shouldFailWithoutQueue() {
        @SuppressWarnings("deprecation")
        AgentControllerImpl noQueueController = new AgentControllerImpl();
        var request = new InvokeRequest("t1", "s1", Map.of());

        assertThatThrownBy(() -> noQueueController.invoke("agent-1", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AgentInvocationQueue");
    }

    @Test
    @DisplayName("InvokeRequest should reject null tenantId")
    void shouldRejectNullTenantId() {
        assertThatThrownBy(() -> new InvokeRequest(null, "s1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("InvokeRequest should default payload to empty map")
    void shouldDefaultPayload() {
        var request = new InvokeRequest("t1", null, null);
        assertThat(request.payload()).isEmpty();
    }

    @Test
    @DisplayName("InvokeResponse.accepted should create valid response")
    void invokeResponseAccepted() {
        var response = InvokeResponse.accepted("req-1", "t1", "agent-1");
        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.requestId()).isEqualTo("req-1");
    }

    @Test
    @DisplayName("MessageResponse.accepted should create valid response")
    void messageResponseAccepted() {
        var response = br.com.archflow.api.events.dto.MessageResponse.accepted("req-1", "t1", "s1", "a1");
        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.response()).isNull();
    }
}
