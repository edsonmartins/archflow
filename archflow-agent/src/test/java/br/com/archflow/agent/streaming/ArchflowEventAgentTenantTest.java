package br.com.archflow.agent.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ArchflowEvent (Agent) — TenantId in Envelope")
class ArchflowEventAgentTenantTest {

    @Test
    @DisplayName("builder should set tenantId in envelope")
    void builderShouldSetTenantId() {
        var event = ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.DELTA)
                .tenantId("tenant-1")
                .data(Map.of("content", "hello"))
                .build();

        assertThat(event.getTenantId()).isEqualTo("tenant-1");
        assertThat(event.getEnvelope().tenantId()).isEqualTo("tenant-1");
        assertThat(event.getEnvelope().tenantIdOpt()).contains("tenant-1");
    }

    @Test
    @DisplayName("tenantId should be null when not set")
    void tenantIdShouldBeNullWhenNotSet() {
        var event = ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.MESSAGE)
                .data(Map.of("content", "hello"))
                .build();

        assertThat(event.getTenantId()).isNull();
        assertThat(event.getEnvelope().tenantIdOpt()).isEmpty();
    }

    @Test
    @DisplayName("PAYLOAD domain and types should exist")
    void payloadDomainAndTypesShouldExist() {
        assertThat(ArchflowDomain.PAYLOAD.getValue()).isEqualTo("payload");
        assertThat(ArchflowEventType.PAYLOAD_CHUNK.getValue()).isEqualTo("payload_chunk");
        assertThat(ArchflowEventType.PAYLOAD_COMPLETE.getValue()).isEqualTo("payload_complete");
    }

    @Test
    @DisplayName("should create PAYLOAD event with tenantId")
    void shouldCreatePayloadEventWithTenant() {
        var event = ArchflowEvent.builder()
                .domain(ArchflowDomain.PAYLOAD)
                .type(ArchflowEventType.PAYLOAD_COMPLETE)
                .tenantId("t1")
                .data(Map.of("type", "AI_SUGGESTION", "body", Map.of("text", "Use product X")))
                .build();

        assertThat(event.getDomain()).isEqualTo(ArchflowDomain.PAYLOAD);
        assertThat(event.getType()).isEqualTo(ArchflowEventType.PAYLOAD_COMPLETE);
        assertThat(event.getTenantId()).isEqualTo("t1");
        assertThat(event.getData("type")).isEqualTo("AI_SUGGESTION");
    }

    @Test
    @DisplayName("backward compat: 6-arg envelope constructor")
    void backwardCompatEnvelope() {
        var env = new ArchflowEvent.EventEnvelope(ArchflowDomain.CHAT, ArchflowEventType.MESSAGE,
                "id-1", Instant.now(), "corr", "exec");
        assertThat(env.tenantId()).isNull();
        assertThat(env.tenantIdOpt()).isEmpty();
    }

    @Test
    @DisplayName("builder from envelope should preserve tenantId")
    void builderFromEnvelopeShouldPreserveTenantId() {
        var original = ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.HEARTBEAT)
                .tenantId("t1")
                .build();

        var rebuilt = ArchflowEvent.builder(original.getEnvelope())
                .data(Map.of("key", "value"))
                .build();

        assertThat(rebuilt.getTenantId()).isEqualTo("t1");
    }
}
