package br.com.archflow.api.events;

import br.com.archflow.api.events.dto.MessageRequest;
import br.com.archflow.api.events.dto.MessageResponse;
import br.com.archflow.api.events.impl.EventControllerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EventControllerImpl")
class EventControllerImplTest {

    private EventControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new EventControllerImpl();
    }

    @Test
    @DisplayName("should return ACCEPTED response with generated requestId")
    void shouldReturnAccepted() {
        var request = new MessageRequest("t1", "s1", "agent-1", "Hello", Map.of());
        var response = controller.sendMessage(request);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.requestId()).isNotNull().isNotBlank();
        assertThat(response.tenantId()).isEqualTo("t1");
        assertThat(response.agentId()).isEqualTo("agent-1");
        assertThat(response.timestamp()).isNotNull();
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
}
