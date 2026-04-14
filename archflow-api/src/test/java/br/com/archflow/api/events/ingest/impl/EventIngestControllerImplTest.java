package br.com.archflow.api.events.ingest.impl;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.events.ingest.EventIngestController.IngestResultDto;
import br.com.archflow.events.proto.ProtobufEventMapper;
import br.com.archflow.events.proto.generated.FlowEventBatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EventIngestControllerImpl}.
 *
 * <p>Uses a real {@link EventStreamRegistry} with a global listener to capture
 * broadcast events, so there are no mocks and no Spring context required.
 */
class EventIngestControllerImplTest {

    private EventStreamRegistry registry;
    private EventIngestControllerImpl controller;
    private List<ArchflowEvent> captured;

    @BeforeEach
    void setUp() {
        registry = new EventStreamRegistry(60_000, 300_000);
        controller = new EventIngestControllerImpl(registry);
        captured = new ArrayList<>();
        registry.addGlobalListener(captured::add);
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    // ----------------------------------------------------------------
    // Happy path
    // ----------------------------------------------------------------

    @Test
    void ingest_validBatch_acceptsAllEvents() {
        ArchflowEvent e1 = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.FLOW_STARTED)
                .executionId("exec-001")
                .tenantId("tenant-rogue") // will be overridden
                .addData("flowId", "flow-001")
                .build();
        ArchflowEvent e2 = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.STEP_COMPLETED)
                .executionId("exec-001")
                .tenantId("tenant-rogue")
                .build();

        byte[] body = ProtobufEventMapper.toBatch(List.of(e1, e2), "agent-1").toByteArray();

        IngestResultDto result = controller.ingest(body, "tenant-real");

        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.rejected()).isEqualTo(0);
        assertThat(result.message()).isEqualTo("ok");
    }

    @Test
    void ingest_broadcastsToExecutionChannel() {
        List<ArchflowEvent> execChannel = new ArrayList<>();
        registry.createEmitter("exec-flow1").onSend(execChannel::add);

        ArchflowEvent event = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.FLOW_COMPLETED)
                .executionId("exec-flow1")
                .build();

        byte[] body = ProtobufEventMapper.toBatch(List.of(event), "agent").toByteArray();
        controller.ingest(body, "tenant-a");

        // Should arrive in execution-specific channel
        assertThat(execChannel).hasSize(1);
        assertThat(execChannel.get(0).getType()).isEqualTo(ArchflowEventType.FLOW_COMPLETED);
    }

    // ----------------------------------------------------------------
    // Tenant pinning (anti-spoofing)
    // ----------------------------------------------------------------

    @Test
    void ingest_tenantPinning_overridesEventTenantId() {
        ArchflowEvent spoofed = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.FLOW_STARTED)
                .executionId("exec-spoof")
                .tenantId("tenant-victim") // attacker claims to be tenant-victim
                .build();

        byte[] body = ProtobufEventMapper.toBatch(List.of(spoofed), "rogue-agent").toByteArray();
        IngestResultDto result = controller.ingest(body, "tenant-attacker");

        assertThat(result.accepted()).isEqualTo(1);

        // Global listener fires once per broadcast call (execution channel + admin channel = 2 calls).
        // In both cases the tenant must be pinned to the authenticated caller.
        assertThat(captured).isNotEmpty();
        assertThat(captured).allSatisfy(e ->
                assertThat(e.getTenantId()).isEqualTo("tenant-attacker"));
    }

    @Test
    void ingest_broadcastsToAdminChannel() {
        List<ArchflowEvent> adminChannel = new ArrayList<>();
        registry.createEmitter("__admin__:tenant-x").onSend(adminChannel::add);

        ArchflowEvent event = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.STEP_STARTED)
                .executionId("exec-abc")
                .build();

        byte[] body = ProtobufEventMapper.toBatch(List.of(event), "agent").toByteArray();
        controller.ingest(body, "tenant-x");

        assertThat(adminChannel).hasSize(1);
    }

    @Test
    void ingest_nullTenantId_skipsAdminChannel_andSkipsPinning() {
        ArchflowEvent event = ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.LOG)
                .executionId("exec-notenant")
                .tenantId("original-tenant")
                .build();

        byte[] body = ProtobufEventMapper.toBatch(List.of(event), "agent").toByteArray();
        IngestResultDto result = controller.ingest(body, null);

        assertThat(result.accepted()).isEqualTo(1);
        // With null authenticatedTenantId, no override occurs — tenant from the event is preserved
        assertThat(captured).isNotEmpty();
        assertThat(captured.get(0).getTenantId()).isEqualTo("original-tenant");
    }

    // ----------------------------------------------------------------
    // Invalid protobuf
    // ----------------------------------------------------------------

    @Test
    void ingest_invalidProtobuf_throwsIllegalArgumentException() {
        byte[] garbage = {0x01, 0x02, (byte) 0xFF, 0x03};

        assertThatThrownBy(() -> controller.ingest(garbage, "tenant-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid protobuf FlowEventBatch");
    }

    @Test
    void ingest_emptyBytes_returnsNoEventsProcessed() {
        // An empty byte array is a valid (empty) protobuf message
        byte[] emptyBatch = FlowEventBatch.newBuilder().build().toByteArray();
        IngestResultDto result = controller.ingest(emptyBatch, "tenant-x");

        assertThat(result.accepted()).isEqualTo(0);
        assertThat(result.rejected()).isEqualTo(0);
        assertThat(result.message()).isEqualTo("no events processed");
    }

    // ----------------------------------------------------------------
    // Constructor validation
    // ----------------------------------------------------------------

    @Test
    void constructor_nullRegistry_throwsNPE() {
        assertThatThrownBy(() -> new EventIngestControllerImpl(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ----------------------------------------------------------------
    // Multiple events — partial success scenario
    // ----------------------------------------------------------------

    @Test
    void ingest_multipleEvents_countsCorrectly() {
        List<ArchflowEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(ArchflowEvent.builder()
                    .domain(ArchflowDomain.FLOW)
                    .type(ArchflowEventType.STEP_COMPLETED)
                    .executionId("exec-multi")
                    .build());
        }

        byte[] body = ProtobufEventMapper.toBatch(events, "agent").toByteArray();
        IngestResultDto result = controller.ingest(body, "tenant-t");

        assertThat(result.accepted()).isEqualTo(5);
        assertThat(result.rejected()).isEqualTo(0);
        assertThat(result.message()).isEqualTo("ok");
    }
}
