package br.com.archflow.agent.streaming;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EventStreamRegistry — Tenant Partitioning")
class EventStreamRegistryTenantTest {

    private EventStreamRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new EventStreamRegistry(60000, 300000); // disable frequent heartbeat/cleanup
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    @Test
    @DisplayName("createEmitter(tenantId, sessionId) should create tenant-scoped emitter")
    void shouldCreateTenantScopedEmitter() {
        var emitter = registry.createEmitter("tenant-1", "session-1");

        assertThat(emitter).isNotNull();
        assertThat(emitter.getExecutionId()).isEqualTo("tenant-1:session-1");
    }

    @Test
    @DisplayName("getEmittersByTenant should return only emitters for that tenant")
    void shouldReturnOnlyTenantEmitters() {
        registry.createEmitter("tenant-A", "session-1");
        registry.createEmitter("tenant-A", "session-2");
        registry.createEmitter("tenant-B", "session-1");

        var emittersA = registry.getEmittersByTenant("tenant-A");
        var emittersB = registry.getEmittersByTenant("tenant-B");

        assertThat(emittersA).hasSize(2);
        assertThat(emittersB).hasSize(1);
    }

    @Test
    @DisplayName("getEmittersByTenant should return empty for unknown tenant")
    void shouldReturnEmptyForUnknownTenant() {
        registry.createEmitter("tenant-A", "session-1");

        assertThat(registry.getEmittersByTenant("unknown")).isEmpty();
    }

    @Test
    @DisplayName("tenant-scoped emitters should support broadcast")
    void shouldSupportBroadcast() {
        registry.createEmitter("t1", "s1");

        int sent = registry.broadcast("t1:s1",
                ArchflowEvent.builder()
                        .domain(ArchflowDomain.CHAT)
                        .type(ArchflowEventType.MESSAGE)
                        .tenantId("t1")
                        .build());

        assertThat(sent).isEqualTo(1);
    }

    @Test
    @DisplayName("backward compat: createEmitter(executionId) should still work")
    void backwardCompatCreateEmitter() {
        var emitter = registry.createEmitter("exec-123");

        assertThat(emitter).isNotNull();
        assertThat(emitter.getExecutionId()).isEqualTo("exec-123");
    }
}
