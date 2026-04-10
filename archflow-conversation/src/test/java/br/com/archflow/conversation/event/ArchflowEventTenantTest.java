package br.com.archflow.conversation.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ArchflowEvent — TenantId in Envelope")
class ArchflowEventTenantTest {

    @Test
    @DisplayName("builder should set tenantId in envelope")
    void builderShouldSetTenantId() {
        var event = ArchflowEvent.builder()
                .domain(ArchflowEvent.EventDomain.CHAT)
                .type(ArchflowEvent.EventType.MESSAGE)
                .tenantId("tenant-1")
                .payload(Map.of("content", "hello"))
                .build();

        assertThat(event.getEnvelope().tenantId()).isEqualTo("tenant-1");
    }

    @Test
    @DisplayName("tenantId should be null when not set")
    void tenantIdShouldBeNullWhenNotSet() {
        var event = ArchflowEvent.builder()
                .domain(ArchflowEvent.EventDomain.CHAT)
                .type(ArchflowEvent.EventType.MESSAGE)
                .payload(Map.of("content", "hello"))
                .build();

        assertThat(event.getEnvelope().tenantId()).isNull();
    }

    @Test
    @DisplayName("envelope should preserve tenantId alongside other fields")
    void envelopeShouldPreserveTenantId() {
        var event = ArchflowEvent.builder()
                .domain(ArchflowEvent.EventDomain.TOOL)
                .type(ArchflowEvent.EventType.START)
                .tenantId("t1")
                .correlationId("corr-1")
                .executionId("exec-1")
                .payload(Map.of("toolName", "search"))
                .build();

        var env = event.getEnvelope();
        assertThat(env.tenantId()).isEqualTo("t1");
        assertThat(env.correlationId()).isEqualTo("corr-1");
        assertThat(env.executionId()).isEqualTo("exec-1");
        assertThat(env.domain()).isEqualTo(ArchflowEvent.EventDomain.TOOL);
    }

    @Test
    @DisplayName("backward compat: 6-arg envelope constructor should default tenantId to null")
    void backwardCompatEnvelope() {
        var env = new ArchflowEvent.EventEnvelope("chat", "message", "id-1",
                java.time.Instant.now(), "corr", "exec");
        assertThat(env.tenantId()).isNull();
    }

    @Test
    @DisplayName("backward compat: 4-arg envelope constructor should default tenantId to null")
    void backwardCompat4ArgEnvelope() {
        var env = new ArchflowEvent.EventEnvelope("chat", "message", "id-1",
                java.time.Instant.now());
        assertThat(env.tenantId()).isNull();
    }
}
