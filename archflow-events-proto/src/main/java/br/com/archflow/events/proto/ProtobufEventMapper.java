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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

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

    private static final Logger log = Logger.getLogger(ProtobufEventMapper.class.getName());

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
        warnOnceAboutFlattening(value.getClass());
        return ScalarValue.newBuilder().setStringValue(value.toString()).build();
    }

    /**
     * Tipos já reportados no aviso de achatamento — um por classe, para sempre.
     *
     * <p>O achatamento acontece por chave de {@code data}/{@code metadata}, em
     * todo evento: avisar sempre inundaria o log justamente no caminho quente e
     * o aviso viraria ruído a ser filtrado. Uma vez por tipo diz o que precisa
     * ser dito — que <i>aquela</i> classe está virando texto — e não se repete.
     *
     * <p>Não tem limite de tamanho de propósito: o conjunto de classes que
     * passam por aqui é o conjunto de tipos que o código coloca em {@code data},
     * que é finito e pequeno. Um cache com expiração voltaria a avisar
     * periodicamente, que é o que se quer evitar.
     */
    private static final Set<Class<?>> FLATTENED_TYPES_WARNED = ConcurrentHashMap.newKeySet();

    /**
     * Avisa que um valor não escalar foi convertido para texto.
     *
     * <p>Isto era <b>completamente silencioso</b>. Um {@code Map}, uma lista ou
     * um objeto de domínio colocado em {@code data} chegava do outro lado como o
     * {@code toString()} dele — sem erro, sem aviso, e com a estrutura perdida.
     * Quem consome o evento recebe algo que parece um valor legítimo e não é.
     *
     * <p>O achatamento continua acontecendo: mudá-lo agora quebraria quem já
     * depende do texto. O que muda é que ele deixa de ser invisível.
     */
    private static void warnOnceAboutFlattening(Class<?> type) {
        if (FLATTENED_TYPES_WARNED.add(type)) {
            log.warning(() -> "Valor do tipo " + type.getName() + " em data/metadata de evento "
                    + "não é escalar e foi convertido com toString() — a estrutura se perde no "
                    + "protobuf. Serialize explicitamente (por exemplo, JSON numa string) se o "
                    + "consumidor precisar dela. Este aviso sai uma vez por tipo.");
        }
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
