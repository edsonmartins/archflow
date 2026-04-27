package br.com.archflow.api.events;

import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.events.dto.MessageRequest;
import br.com.archflow.api.events.impl.EventControllerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EventControllerImpl")
class EventControllerImplTest {

    private InMemoryAgentInvocationQueue queue;
    private EventControllerImpl controller;

    @BeforeEach
    void setUp() {
        queue = new InMemoryAgentInvocationQueue();
        controller = new EventControllerImpl(queue);
    }

    @Test
    @DisplayName("should return ACCEPTED response and enqueue the invocation")
    void shouldReturnAcceptedAndEnqueue() {
        var request = new MessageRequest("t1", "s1", "agent-1", "Hello", Map.of());
        var response = controller.sendMessage(request);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.requestId()).isNotNull().isNotBlank();
        assertThat(response.tenantId()).isEqualTo("t1");
        assertThat(response.agentId()).isEqualTo("agent-1");
        assertThat(response.timestamp()).isNotNull();

        assertThat(queue.size()).isEqualTo(1);
        InvocationRequest enqueued = queue.poll().orElseThrow();
        assertThat(enqueued.tenantId()).isEqualTo("t1");
        assertThat(enqueued.agentId()).isEqualTo("agent-1");
        assertThat(enqueued.payload()).containsEntry("message", "Hello");
        assertThat(enqueued.payload()).containsEntry("sessionId", "s1");
    }

    @Test
    @DisplayName("should generate sessionId when not provided")
    void shouldGenerateSessionId() {
        var request = new MessageRequest("t1", null, "agent-1", "msg", Map.of());
        var response = controller.sendMessage(request);

        assertThat(response.sessionId()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("should preserve provided sessionId")
    void shouldPreserveSessionId() {
        var request = new MessageRequest("t1", "my-session", "agent-1", "msg", Map.of());
        var response = controller.sendMessage(request);

        assertThat(response.sessionId()).isEqualTo("my-session");
    }

    @Test
    @DisplayName("should fail when queue is not wired")
    void shouldFailWithoutQueue() {
        @SuppressWarnings("deprecation")
        EventControllerImpl noQueueController = new EventControllerImpl();
        var request = new MessageRequest("t1", "s1", "agent-1", "Hello", Map.of());

        assertThatThrownBy(() -> noQueueController.sendMessage(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AgentInvocationQueue");
    }
}
