package br.com.archflow.agent.streaming;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/**
 * Event envelope do protocolo de streaming do archflow.
 *
 * <p>Este envelope fornece uma estrutura uniforme para todos os eventos
 * transmitidos via SSE (Server-Sent Events) ou WebSocket.
 *
 * <h3>Estrutura do Evento:</h3>
 * <pre>{@code
 * {
 *   "envelope": {
 *     "domain": "chat" | "thinking" | "tool" | "audit" | "interaction" | "system",
 *     "type": "message" | "delta" | "start" | "end" | "error" | ...,
 *     "id": "uuid-v4",
 *     "timestamp": "2025-01-16T10:30:00Z",
 *     "correlationId": "uuid-v4", // Opcional, para correlacionar eventos
 *     "executionId": "exec_123"   // Opcional, ID da execução
 *   },
 *   "data": {
 *     // Conteúdo específico do domain/type
 *   },
 *   "metadata": {
 *     // Metadados opcionais
 *   }
 * }
 * }</pre>
 *
 * <h3>Exemplos de Uso:</h3>
 * <pre>{@code
 * // Evento de chat com delta
 * ArchflowEvent chatDelta = ArchflowEvent.builder()
 *     .domain(ArchflowDomain.CHAT)
 *     .type(ArchflowEventType.DELTA)
 *     .data(Map.of("content", "Hello"))
 *     .build();
 *
 * // Evento de tool iniciada
 * ArchflowEvent toolStart = ArchflowEvent.builder()
 *     .domain(ArchflowDomain.TOOL)
 *     .type(ArchflowEventType.TOOL_START)
 *     .executionId("exec_123")
 *     .data(Map.of(
 *         "toolName", "search",
 *         "input", Map.of("query", "Java")
 *     ))
 *     .build();
 * }</pre>
 *
 * @see ArchflowDomain
 * @see ArchflowEventType
 */
public class ArchflowEvent {

    private final EventEnvelope envelope;
    private final Map<String, Object> data;
    private final Map<String, Object> metadata;

    private ArchflowEvent(Builder builder) {
        this.envelope = new EventEnvelope(
                builder.domain,
                builder.type,
                builder.id != null ? builder.id : UUID.randomUUID().toString(),
                builder.timestamp != null ? builder.timestamp : Instant.now(),
                builder.correlationId,
                builder.executionId
        );
        this.data = builder.data != null ? new HashMap<>(builder.data) : new HashMap<>();
        this.metadata = builder.metadata != null ? new HashMap<>(builder.metadata) : new HashMap<>();
    }

    /**
     * Retorna o envelope do evento.
     *
     * @return EventEnvelope
     */
    public EventEnvelope getEnvelope() {
        return envelope;
    }

    /**
     * Retorna o domínio do evento.
     *
     * @return ArchflowDomain
     */
    public ArchflowDomain getDomain() {
        return envelope.domain();
    }

    /**
     * Retorna o tipo do evento.
     *
     * @return ArchflowEventType
     */
    public ArchflowEventType getType() {
        return envelope.type();
    }

    /**
     * Retorna o ID único do evento.
     *
     * @return UUID do evento
     */
    public String getId() {
        return envelope.id();
    }

    /**
     * Retorna o timestamp do evento.
     *
     * @return Instant do evento
     */
    public Instant getTimestamp() {
        return envelope.timestamp();
    }

    /**
     * Retorna o ID de correlação (opcional).
     *
     * @return correlationId ou null
     */
    public String getCorrelationId() {
        return envelope.correlationIdOpt().orElse(null);
    }

    /**
     * Retorna o ID de execução (opcional).
     *
     * @return executionId ou null
     */
    public String getExecutionId() {
        return envelope.executionIdOpt().orElse(null);
    }

    /**
     * Retorna os dados do evento.
     *
     * @return Map com os dados
     */
    public Map<String, Object> getData() {
        return new HashMap<>(data);
    }

    /**
     * Retorna um valor específico dos dados.
     *
     * @param key Chave do dado
     * @return Valor ou null se não existir
     */
    public Object getData(String key) {
        return data.get(key);
    }

    /**
     * Retorna um valor específico dos dados com tipo.
     *
     * @param key Chave do dado
     * @param type Tipo do valor
     * @param <T> Tipo do valor
     * @return Valor ou null se não existir
     */
    @SuppressWarnings("unchecked")
    public <T> T getData(String key, Class<T> type) {
        Object value = data.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    /**
     * Retorna os metadados do evento.
     *
     * @return Map com os metadados
     */
    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }

    /**
     * Retorna um valor específico dos metadados.
     *
     * @param key Chave do metadado
     * @return Valor ou null se não existir
     */
    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * Converte o evento para JSON (representação simples).
     *
     * @return String JSON do evento
     */
    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"envelope\":{");
        json.append("\"domain\":\"").append(envelope.domain().getValue()).append("\",");
        json.append("\"type\":\"").append(envelope.type().getValue()).append("\",");
        json.append("\"id\":\"").append(envelope.id()).append("\",");
        json.append("\"timestamp\":\"").append(envelope.timestamp()).append("\"");
        envelope.correlationIdOpt().ifPresent(id -> json.append(",\"correlationId\":\"").append(id).append("\""));
        envelope.executionIdOpt().ifPresent(id -> json.append(",\"executionId\":\"").append(id).append("\""));
        json.append("},");
        json.append("\"data\":{").append(mapToJson(data)).append("},");
        json.append("\"metadata\":{").append(mapToJson(metadata)).append("}");
        json.append("}");
        return json.toString();
    }

    private String mapToJson(Map<String, Object> map) {
        StringBuilder json = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                json.append("null");
            } else if (value instanceof String) {
                json.append("\"").append(value).append("\"");
            } else if (value instanceof Number) {
                json.append(value);
            } else if (value instanceof Boolean) {
                json.append(value);
            } else {
                json.append("\"").append(value.toString()).append("\"");
            }
        }
        return json.toString();
    }

    /**
     * Cria um novo builder.
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Cria um evento a partir de um envelope existente.
     *
     * @param envelope Envelope existente
     * @return Builder com o envelope
     */
    public static Builder builder(EventEnvelope envelope) {
        return new Builder(envelope);
    }

    /**
     * Envelope do evento contendo metadados de roteamento.
     *
     * @param domain Domínio do evento
     * @param type Tipo do evento
     * @param id ID único do evento
     * @param timestamp Timestamp do evento
     * @param correlationId ID de correlação (opcional)
     * @param executionId ID de execução (opcional)
     */
    public record EventEnvelope(
            ArchflowDomain domain,
            ArchflowEventType type,
            String id,
            Instant timestamp,
            String correlationId,
            String executionId
    ) {
        public EventEnvelope {
            if (domain == null) {
                throw new IllegalArgumentException("domain cannot be null");
            }
            if (type == null) {
                throw new IllegalArgumentException("type cannot be null");
            }
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id cannot be null or blank");
            }
            if (timestamp == null) {
                throw new IllegalArgumentException("timestamp cannot be null");
            }
        }

        /**
         * Construtor simplificado sem IDs opcionais.
         */
        public EventEnvelope(ArchflowDomain domain, ArchflowEventType type, String id, Instant timestamp) {
            this(domain, type, id, timestamp, null, null);
        }

        /**
         * Retorna o correlationId como Optional.
         */
        public java.util.Optional<String> correlationIdOpt() {
            return java.util.Optional.ofNullable(correlationId);
        }

        /**
         * Retorna o executionId como Optional.
         */
        public java.util.Optional<String> executionIdOpt() {
            return java.util.Optional.ofNullable(executionId);
        }
    }

    /**
     * Builder para criar eventos.
     */
    public static class Builder {
        private ArchflowDomain domain;
        private ArchflowEventType type;
        private String id;
        private Instant timestamp;
        private String correlationId;
        private String executionId;
        private Map<String, Object> data;
        private Map<String, Object> metadata;

        private Builder() {
        }

        private Builder(EventEnvelope envelope) {
            this.domain = envelope.domain();
            this.type = envelope.type();
            this.id = envelope.id();
            this.timestamp = envelope.timestamp();
            this.correlationId = envelope.correlationIdOpt().orElse(null);
            this.executionId = envelope.executionIdOpt().orElse(null);
        }

        public Builder domain(ArchflowDomain domain) {
            this.domain = domain;
            return this;
        }

        public Builder type(ArchflowEventType type) {
            this.type = type;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder executionId(String executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder data(Map<String, Object> data) {
            this.data = data;
            return this;
        }

        public Builder addData(String key, Object value) {
            if (this.data == null) {
                this.data = new HashMap<>();
            }
            this.data.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        /**
         * Constrói o evento.
         *
         * @return ArchflowEvent
         * @throws IllegalArgumentException Se domain ou type não forem definidos
         */
        public ArchflowEvent build() {
            if (domain == null) {
                throw new IllegalArgumentException("domain is required");
            }
            if (type == null) {
                throw new IllegalArgumentException("type is required");
            }
            return new ArchflowEvent(this);
        }
    }
}
