package br.com.archflow.events.proto;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.events.proto.generated.Domain;
import br.com.archflow.events.proto.generated.EventEnvelope;
import br.com.archflow.events.proto.generated.EventType;
import br.com.archflow.events.proto.generated.FlowEvent;
import br.com.archflow.events.proto.generated.FlowEventBatch;
import br.com.archflow.events.proto.generated.ScalarValue;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Bi-directional mapper between {@link ArchflowEvent} and the generated
 * protobuf {@link FlowEvent}.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>Enum mapping is by name (case-insensitive). Unknown names produce
 *       {@code DOMAIN_UNSPECIFIED} / {@code EVENT_TYPE_UNSPECIFIED} rather than
 *       throwing, so new event types in the agent do not crash older servers.</li>
 *   <li>Map values are flattened to {@link ScalarValue}: strings stay strings,
 *       numbers become int64 or double, booleans become bool, everything else
 *       calls {@code toString()} and becomes a string.</li>
 * </ul>
 */
public final class ProtobufEventMapper {

    private ProtobufEventMapper() {}

    // ----------------------------------------------------------------
    // ArchflowEvent → FlowEvent
    // ----------------------------------------------------------------

    /**
     * Converts an {@link ArchflowEvent} to a {@link FlowEvent} protobuf.
     *
     * @param event the source event
     * @return protobuf representation
     */
    public static FlowEvent toProto(ArchflowEvent event) {
        EventEnvelope.Builder env = EventEnvelope.newBuilder()
                .setDomain(domainToProto(event.getDomain()))
                .setType(typeToProto(event.getType()))
                .setId(event.getId())
                .setTimestampMillis(event.getTimestamp().toEpochMilli());

        if (event.getCorrelationId() != null) env.setCorrelationId(event.getCorrelationId());
        if (event.getExecutionId() != null)   env.setExecutionId(event.getExecutionId());
        if (event.getTenantId() != null)       env.setTenantId(event.getTenantId());

        FlowEvent.Builder builder = FlowEvent.newBuilder().setEnvelope(env);
        flattenMap(event.getData()).forEach(builder::putData);
        flattenMap(event.getMetadata()).forEach(builder::putMetadata);

        return builder.build();
    }

    /**
     * Converts a {@link FlowEvent} protobuf back to an {@link ArchflowEvent}.
     *
     * @param proto the source protobuf
     * @return ArchflowEvent representation
     */
    public static ArchflowEvent fromProto(FlowEvent proto) {
        EventEnvelope env = proto.getEnvelope();

        ArchflowEvent.Builder builder = ArchflowEvent.builder()
                .domain(domainFromProto(env.getDomain()))
                .type(typeFromProto(env.getType()))
                .id(env.getId())
                .timestamp(Instant.ofEpochMilli(env.getTimestampMillis()));

        if (!env.getCorrelationId().isEmpty()) builder.correlationId(env.getCorrelationId());
        if (!env.getExecutionId().isEmpty())   builder.executionId(env.getExecutionId());
        if (!env.getTenantId().isEmpty())       builder.tenantId(env.getTenantId());

        Map<String, Object> data = new HashMap<>();
        proto.getDataMap().forEach((k, v) -> data.put(k, scalarToObject(v)));
        builder.data(data);

        Map<String, Object> meta = new HashMap<>();
        proto.getMetadataMap().forEach((k, v) -> meta.put(k, scalarToObject(v)));
        builder.metadata(meta);

        return builder.build();
    }

    /**
     * Wraps a collection of {@link ArchflowEvent}s into a {@link FlowEventBatch}.
     *
     * @param events       events to batch
     * @param sourceAgentId identifier of the publishing agent
     * @return protobuf batch
     */
    public static FlowEventBatch toBatch(Collection<ArchflowEvent> events, String sourceAgentId) {
        FlowEventBatch.Builder batch = FlowEventBatch.newBuilder()
                .setSourceAgentId(sourceAgentId != null ? sourceAgentId : "")
                .setBatchCreatedMillis(System.currentTimeMillis());

        for (ArchflowEvent e : events) {
            batch.addEvents(toProto(e));
        }
        return batch.build();
    }

    // ----------------------------------------------------------------
    // Enum conversions
    // ----------------------------------------------------------------

    private static Domain domainToProto(ArchflowDomain d) {
        if (d == null) return Domain.DOMAIN_UNSPECIFIED;
        try {
            return Domain.valueOf("DOMAIN_" + d.name());
        } catch (IllegalArgumentException e) {
            return Domain.DOMAIN_UNSPECIFIED;
        }
    }

    private static ArchflowDomain domainFromProto(Domain d) {
        if (d == Domain.DOMAIN_UNSPECIFIED || d == Domain.UNRECOGNIZED) return ArchflowDomain.SYSTEM;
        // Strip the "DOMAIN_" prefix added to avoid proto3 C++ scoping conflicts
        String name = d.name();
        if (name.startsWith("DOMAIN_")) {
            name = name.substring(7);
        }
        try {
            return ArchflowDomain.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ArchflowDomain.SYSTEM;
        }
    }

    private static EventType typeToProto(ArchflowEventType t) {
        if (t == null) return EventType.EVENT_TYPE_UNSPECIFIED;
        try {
            return EventType.valueOf(t.name());
        } catch (IllegalArgumentException e) {
            return EventType.EVENT_TYPE_UNSPECIFIED;
        }
    }

    private static ArchflowEventType typeFromProto(EventType t) {
        if (t == EventType.EVENT_TYPE_UNSPECIFIED || t == EventType.UNRECOGNIZED)
            return ArchflowEventType.LOG;
        try {
            return ArchflowEventType.valueOf(t.name());
        } catch (IllegalArgumentException e) {
            return ArchflowEventType.LOG;
        }
    }

    // ----------------------------------------------------------------
    // ScalarValue helpers
    // ----------------------------------------------------------------

    private static Map<String, ScalarValue> flattenMap(Map<String, Object> source) {
        Map<String, ScalarValue> result = new HashMap<>();
        if (source == null) return result;
        source.forEach((k, v) -> result.put(k, objectToScalar(v)));
        return result;
    }

    static ScalarValue objectToScalar(Object value) {
        if (value == null) {
            return ScalarValue.newBuilder().setNullValue(true).build();
        }
        if (value instanceof String s) {
            return ScalarValue.newBuilder().setStringValue(s).build();
        }
        if (value instanceof Boolean b) {
            return ScalarValue.newBuilder().setBoolValue(b).build();
        }
        if (value instanceof Long l) {
            return ScalarValue.newBuilder().setIntValue(l).build();
        }
        if (value instanceof Integer i) {
            return ScalarValue.newBuilder().setIntValue(i).build();
        }
        if (value instanceof Double d) {
            return ScalarValue.newBuilder().setDoubleValue(d).build();
        }
        if (value instanceof Float f) {
            return ScalarValue.newBuilder().setDoubleValue(f).build();
        }
        if (value instanceof Number n) {
            // generic Number → long
            return ScalarValue.newBuilder().setIntValue(n.longValue()).build();
        }
        // Fallback: toString
        return ScalarValue.newBuilder().setStringValue(value.toString()).build();
    }

    static Object scalarToObject(ScalarValue scalar) {
        return switch (scalar.getKindCase()) {
            case STRING_VALUE -> scalar.getStringValue();
            case INT_VALUE    -> scalar.getIntValue();
            case DOUBLE_VALUE -> scalar.getDoubleValue();
            case BOOL_VALUE   -> scalar.getBoolValue();
            case BYTES_VALUE  -> scalar.getBytesValue().toByteArray();
            case NULL_VALUE   -> null;
            default           -> null;
        };
    }
}
