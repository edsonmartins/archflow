package br.com.archflow.events.proto;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.events.proto.generated.FlowEvent;
import br.com.archflow.events.proto.generated.FlowEventBatch;
import br.com.archflow.events.proto.generated.ScalarValue;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProtobufEventMapper} — round-trip conversions,
 * enum mapping, and ScalarValue handling.
 */
class ProtobufEventMapperTest {

    // ----------------------------------------------------------------
    // Round-trip: ArchflowEvent → FlowEvent → ArchflowEvent
    // ----------------------------------------------------------------

    @Test
    void roundTrip_flowStarted() {
        ArchflowEvent original = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.FLOW_STARTED)
                .id("event-id-001")
                .timestamp(Instant.ofEpochMilli(1_700_000_000_000L))
                .executionId("exec-001")
                .tenantId("tenant-abc")
                .addData("flowId", "flow-xyz")
                .addData("stepCount", 3)
                .build();

        FlowEvent proto = ProtobufEventMapper.toProto(original);
        ArchflowEvent restored = ProtobufEventMapper.fromProto(proto);

        assertThat(restored.getDomain()).isEqualTo(ArchflowDomain.FLOW);
        assertThat(restored.getType()).isEqualTo(ArchflowEventType.FLOW_STARTED);
        assertThat(restored.getId()).isEqualTo("event-id-001");
        assertThat(restored.getTimestamp()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(restored.getExecutionId()).isEqualTo("exec-001");
        assertThat(restored.getTenantId()).isEqualTo("tenant-abc");
        assertThat(restored.getData("flowId")).isEqualTo("flow-xyz");
        // int was stored as int → comes back as long (protobuf int64)
        assertThat(((Number) restored.getData("stepCount")).intValue()).isEqualTo(3);
    }

    @Test
    void roundTrip_chatDelta() {
        ArchflowEvent original = ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.DELTA)
                .executionId("exec-chat")
                .addData("content", "hello world")
                .addData("tokenCount", 2L)
                .build();

        ArchflowEvent restored = ProtobufEventMapper.fromProto(ProtobufEventMapper.toProto(original));

        assertThat(restored.getDomain()).isEqualTo(ArchflowDomain.CHAT);
        assertThat(restored.getType()).isEqualTo(ArchflowEventType.DELTA);
        assertThat(restored.getExecutionId()).isEqualTo("exec-chat");
        assertThat(restored.getData("content")).isEqualTo("hello world");
        assertThat(restored.getData("tokenCount")).isEqualTo(2L);
    }

    @Test
    void roundTrip_allDomains() {
        for (ArchflowDomain domain : ArchflowDomain.values()) {
            ArchflowEvent event = ArchflowEvent.builder()
                    .domain(domain)
                    .type(ArchflowEventType.LOG)
                    .build();
            ArchflowEvent restored = ProtobufEventMapper.fromProto(ProtobufEventMapper.toProto(event));
            assertThat(restored.getDomain())
                    .as("round-trip domain %s", domain)
                    .isEqualTo(domain);
        }
    }

    @Test
    void roundTrip_flowLifecycleEventTypes() {
        ArchflowEventType[] flowTypes = {
                ArchflowEventType.FLOW_STARTED, ArchflowEventType.FLOW_COMPLETED,
                ArchflowEventType.FLOW_FAILED, ArchflowEventType.STEP_STARTED,
                ArchflowEventType.STEP_COMPLETED, ArchflowEventType.STEP_FAILED,
                ArchflowEventType.STEP_SKIPPED
        };
        for (ArchflowEventType type : flowTypes) {
            ArchflowEvent event = ArchflowEvent.builder()
                    .domain(ArchflowDomain.FLOW)
                    .type(type)
                    .build();
            ArchflowEvent restored = ProtobufEventMapper.fromProto(ProtobufEventMapper.toProto(event));
            assertThat(restored.getType())
                    .as("round-trip type %s", type)
                    .isEqualTo(type);
        }
    }

    // ----------------------------------------------------------------
    // ScalarValue helpers
    // ----------------------------------------------------------------

    @Test
    void objectToScalar_string() {
        ScalarValue sv = ProtobufEventMapper.objectToScalar("hello");
        assertThat(sv.getStringValue()).isEqualTo("hello");
    }

    @Test
    void objectToScalar_long() {
        ScalarValue sv = ProtobufEventMapper.objectToScalar(42L);
        assertThat(sv.getIntValue()).isEqualTo(42L);
    }

    @Test
    void objectToScalar_integer() {
        ScalarValue sv = ProtobufEventMapper.objectToScalar(7);
        assertThat(sv.getIntValue()).isEqualTo(7L);
    }

    @Test
    void objectToScalar_double() {
        ScalarValue sv = ProtobufEventMapper.objectToScalar(3.14);
        assertThat(sv.getDoubleValue()).isEqualTo(3.14);
    }

    @Test
    void objectToScalar_boolean() {
        assertThat(ProtobufEventMapper.objectToScalar(true).getBoolValue()).isTrue();
        assertThat(ProtobufEventMapper.objectToScalar(false).getBoolValue()).isFalse();
    }

    @Test
    void objectToScalar_null() {
        ScalarValue sv = ProtobufEventMapper.objectToScalar(null);
        assertThat(sv.getNullValue()).isTrue();
    }

    @Test
    void objectToScalar_fallbackToString() {
        ScalarValue sv = ProtobufEventMapper.objectToScalar(new Object() {
            @Override public String toString() { return "custom"; }
        });
        assertThat(sv.getStringValue()).isEqualTo("custom");
    }

    @Test
    void scalarToObject_null() {
        ScalarValue sv = ScalarValue.newBuilder().setNullValue(true).build();
        assertThat(ProtobufEventMapper.scalarToObject(sv)).isNull();
    }

    // ----------------------------------------------------------------
    // toBatch
    // ----------------------------------------------------------------

    @Test
    void toBatch_setsAgentIdAndTimestamp() {
        ArchflowEvent e1 = ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.LOG)
                .build();
        ArchflowEvent e2 = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.FLOW_COMPLETED)
                .build();

        long before = System.currentTimeMillis();
        FlowEventBatch batch = ProtobufEventMapper.toBatch(List.of(e1, e2), "agent-standalone");
        long after = System.currentTimeMillis();

        assertThat(batch.getSourceAgentId()).isEqualTo("agent-standalone");
        assertThat(batch.getBatchCreatedMillis()).isBetween(before, after);
        assertThat(batch.getEventsList()).hasSize(2);
    }

    @Test
    void toBatch_nullAgentIdBecomesEmpty() {
        FlowEventBatch batch = ProtobufEventMapper.toBatch(List.of(), null);
        assertThat(batch.getSourceAgentId()).isEmpty();
    }

    // ----------------------------------------------------------------
    // Null / optional fields
    // ----------------------------------------------------------------

    @Test
    void toProto_nullOptionalFieldsNotSet() {
        ArchflowEvent event = ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.TRACE)
                .build();

        FlowEvent proto = ProtobufEventMapper.toProto(event);

        assertThat(proto.getEnvelope().getCorrelationId()).isEmpty();
        assertThat(proto.getEnvelope().getExecutionId()).isEmpty();
        assertThat(proto.getEnvelope().getTenantId()).isEmpty();
    }

    @Test
    void fromProto_emptyOptionalFieldsAreNull() {
        ArchflowEvent event = ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.TRACE)
                .build();

        ArchflowEvent restored = ProtobufEventMapper.fromProto(ProtobufEventMapper.toProto(event));

        assertThat(restored.getCorrelationId()).isNull();
        assertThat(restored.getExecutionId()).isNull();
        assertThat(restored.getTenantId()).isNull();
    }

    @Test
    void roundTrip_withMetadata() {
        ArchflowEvent original = ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.TOOL_START)
                .addData("toolName", "search")
                .addMetadata("source", "junit")
                .addMetadata("version", 1L)
                .build();

        ArchflowEvent restored = ProtobufEventMapper.fromProto(ProtobufEventMapper.toProto(original));

        assertThat(restored.getData("toolName")).isEqualTo("search");
        assertThat(restored.getMetadata("source")).isEqualTo("junit");
        assertThat(restored.getMetadata("version")).isEqualTo(1L);
    }
}
