package br.com.archflow.agent.handoff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@DisplayName("AgentHandoff")
class AgentHandoffTest {

    @Test @DisplayName("should generate UUID when id is null")
    void shouldGenerateId() {
        var h = AgentHandoff.peer("a", "b", Map.of(), "test");
        assertThat(h.id()).isNotNull().isNotEmpty();
    }

    @Test @DisplayName("should generate timestamp when null")
    void shouldGenerateTimestamp() {
        var h = AgentHandoff.peer("a", "b", Map.of(), "test");
        assertThat(h.timestamp()).isNotNull();
    }

    @Test @DisplayName("should make defensive copy of transferState")
    void shouldDefensiveCopyState() {
        var state = new java.util.HashMap<String, Object>();
        state.put("key", "value");
        var h = AgentHandoff.peer("a", "b", state, "test");
        assertThatThrownBy(() -> h.transferState().put("new", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test @DisplayName("should reject null sourceAgentId")
    void shouldRejectNullSource() {
        assertThatThrownBy(() -> AgentHandoff.peer(null, "b", Map.of(), "test"))
                .isInstanceOf(NullPointerException.class);
    }
}
