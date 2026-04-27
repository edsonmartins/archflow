package br.com.archflow.model.escalation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EscalationChannels registry")
class EscalationChannelsTest {

    @AfterEach
    void clearRegistry() {
        EscalationChannels.setDefault(null);
    }

    @Test
    @DisplayName("tryEscalate returns false when no channel is configured")
    void noChannel() {
        boolean handled = EscalationChannels.tryEscalate(
                EscalationRequest.of("t", "c", "reason"));
        assertThat(handled).isFalse();
    }

    @Test
    @DisplayName("tryEscalate forwards to the registered channel and returns true")
    void forwards() {
        AtomicReference<EscalationRequest> captured = new AtomicReference<>();
        EscalationChannels.setDefault(new EscalationChannel() {
            @Override public String id() { return "mock"; }
            @Override public void escalate(EscalationRequest r) { captured.set(r); }
        });

        boolean handled = EscalationChannels.tryEscalate(
                new EscalationRequest("t1", "c1", "u1", "low-conf", Map.of("k", 1)));

        assertThat(handled).isTrue();
        assertThat(captured.get().tenantId()).isEqualTo("t1");
        assertThat(captured.get().conversationId()).isEqualTo("c1");
        assertThat(captured.get().targetUserId()).isEqualTo("u1");
        assertThat(captured.get().reason()).isEqualTo("low-conf");
    }

    @Test
    @DisplayName("EscalationRequest requires non-null tenantId and conversationId")
    void requiresIds() {
        assertThat(EscalationRequest.of("t", "c", null).context()).isEmpty();
        try {
            new EscalationRequest(null, "c", null, null, null);
            throw new AssertionError("expected NPE");
        } catch (NullPointerException expected) {
            assertThat(expected).hasMessageContaining("tenantId");
        }
    }
}
