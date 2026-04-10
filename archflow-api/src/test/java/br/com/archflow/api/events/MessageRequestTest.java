package br.com.archflow.api.events;

import br.com.archflow.api.events.dto.MessageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MessageRequest")
class MessageRequestTest {

    @Test
    @DisplayName("should create valid request")
    void shouldCreateValidRequest() {
        var req = new MessageRequest("t1", "s1", "agent-1", "Hello", Map.of("k", "v"));

        assertThat(req.tenantId()).isEqualTo("t1");
        assertThat(req.sessionId()).isEqualTo("s1");
        assertThat(req.agentId()).isEqualTo("agent-1");
        assertThat(req.message()).isEqualTo("Hello");
        assertThat(req.metadata()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("should reject null tenantId")
    void shouldRejectNullTenantId() {
        assertThatThrownBy(() -> new MessageRequest(null, "s", "a", "msg", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject blank message")
    void shouldRejectBlankMessage() {
        assertThatThrownBy(() -> new MessageRequest("t", "s", "a", "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should default metadata to empty map")
    void shouldDefaultMetadata() {
        var req = new MessageRequest("t", "s", "a", "msg", null);
        assertThat(req.metadata()).isEmpty();
    }
}
