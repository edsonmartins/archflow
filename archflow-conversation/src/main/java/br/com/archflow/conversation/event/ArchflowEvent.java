package br.com.archflow.conversation.event;

import br.com.archflow.conversation.form.FormData;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Archflow Streaming Protocol Event.
 *
 * <p>Events are structured messages sent over SSE/WebSocket for real-time
 * communication between the AI workflow and the client.</p>
 *
 * <p>Event Domains:
 * <ul>
 *   <li><b>chat:</b> Messages from the AI model</li>
 *   <li><b>interaction:</b> Forms and user input requests</li>
 *   <li><b>thinking:</b> Reasoning process (o1, etc.)</li>
 *   <li><b>tool:</b> Tool execution events</li>
 *   <li><b>audit:</b> Tracing, debugging, logs</li>
 * </ul>
 *
 * <p>Event Types:
 * <ul>
 *   <li><b>message:</b> Complete message</li>
 *   <li><b>delta:</b> Streaming chunk</li>
 *   <li><b>form:</b> Form presentation</li>
 *   <li><b>error:</b> Error occurred</li>
 *   <li><b>suspend:</b> Conversation suspended</li>
 *   <li><b>resume:</b> Conversation resumed</li>
 * </ul>
 *
 * @see EventEnvelope
 * @see EventData
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArchflowEvent {

    private final EventEnvelope envelope;
    private final EventData data;

    private ArchflowEvent(Builder builder) {
        this.envelope = new EventEnvelope(
                builder.domain,
                builder.type,
                builder.id != null ? builder.id : UUID.randomUUID().toString(),
                builder.timestamp != null ? builder.timestamp : Instant.now(),
                builder.correlationId,
                builder.executionId
        );
        this.data = new EventData(builder.payload);
    }

    /**
     * Creates a new builder for constructing events.
     *
     * @return A new EventBuilder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a chat message event.
     */
    public static ArchflowEvent chatMessage(String content) {
        return builder()
                .domain(EventDomain.CHAT)
                .type(EventType.MESSAGE)
                .payload(Map.of("content", content))
                .build();
    }

    /**
     * Creates a chat delta event for streaming.
     */
    public static ArchflowEvent chatDelta(String content) {
        return builder()
                .domain(EventDomain.CHAT)
                .type(EventType.DELTA)
                .payload(Map.of("content", content))
                .build();
    }

    /**
     * Creates a form presentation event (suspend).
     */
    public static ArchflowEvent formPresent(String formId, FormData form) {
        return builder()
                .domain(EventDomain.INTERACTION)
                .type(EventType.FORM)
                .payload(Map.of(
                        "formId", formId,
                        "form", form
                ))
                .build();
    }

    /**
     * Creates a suspend event.
     */
    public static ArchflowEvent suspend(String conversationId, String resumeToken, FormData form) {
        return builder()
                .domain(EventDomain.INTERACTION)
                .type(EventType.SUSPEND)
                .payload(Map.of(
                        "conversationId", conversationId,
                        "resumeToken", resumeToken,
                        "form", form
                ))
                .build();
    }

    /**
     * Creates a resume event.
     */
    public static ArchflowEvent resume(String conversationId, Map<String, Object> formData) {
        return builder()
                .domain(EventDomain.INTERACTION)
                .type(EventType.RESUME)
                .payload(Map.of(
                        "conversationId", conversationId,
                        "formData", formData
                ))
                .build();
    }

    /**
     * Creates a tool execution event.
     */
    public static ArchflowEvent toolStart(String toolName, Map<String, Object> input) {
        return builder()
                .domain(EventDomain.TOOL)
                .type(EventType.START)
                .payload(Map.of(
                        "toolName", toolName,
                        "input", input,
                        "status", "started"
                ))
                .build();
    }

    /**
     * Creates a tool completion event.
     */
    public static ArchflowEvent toolComplete(String toolName, Object result) {
        return builder()
                .domain(EventDomain.TOOL)
                .type(EventType.COMPLETE)
                .payload(Map.of(
                        "toolName", toolName,
                        "result", result,
                        "status", "completed"
                ))
                .build();
    }

    /**
     * Creates an error event.
     */
    public static ArchflowEvent error(String message, Throwable cause) {
        return builder()
                .domain(EventDomain.AUDIT)
                .type(EventType.ERROR)
                .payload(Map.of(
                        "message", message,
                        "cause", cause != null ? cause.getMessage() : null
                ))
                .build();
    }

    /**
     * Creates a thinking event for reasoning models.
     */
    public static ArchflowEvent thinking(String thought) {
        return builder()
                .domain(EventDomain.THINKING)
                .type(EventType.DELTA)
                .payload(Map.of("thought", thought))
                .build();
    }

    public EventEnvelope getEnvelope() {
        return envelope;
    }

    public EventData getData() {
        return data;
    }

    /**
     * Converts this event to JSON for SSE transmission.
     */
    public String toJson() {
        // Simple JSON representation - in production use ObjectMapper
        return String.format("{\"envelope\":{\"domain\":\"%s\",\"type\":\"%s\",\"id\":\"%s\",\"timestamp\":\"%s\"},\"data\":%s}",
                envelope.domain(), envelope.type(), envelope.id(), envelope.timestamp(),
                data.payload() != null ? data.payload().toString() : "{}");
    }

    /**
     * Event envelope containing metadata.
     */
    public record EventEnvelope(
            @JsonProperty("domain") String domain,
            @JsonProperty("type") String type,
            @JsonProperty("id") String id,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("executionId") String executionId
    ) {
        public EventEnvelope(String domain, String type, String id, Instant timestamp) {
            this(domain, type, id, timestamp, null, null);
        }
    }

    /**
     * Event data containing the actual payload.
     */
    public record EventData(
            @JsonProperty("content") String content,
            @JsonProperty("formId") String formId,
            @JsonProperty("form") FormData form,
            @JsonProperty("toolName") String toolName,
            @JsonProperty("input") Map<String, Object> input,
            @JsonProperty("result") Object result,
            @JsonProperty("status") String status,
            @JsonProperty("message") String message,
            @JsonProperty("cause") String cause,
            @JsonProperty("thought") String thought,
            @JsonProperty("payload") Map<String, Object> payload,
            @JsonProperty("metadata") Map<String, Object> metadata
    ) {
        public EventData(Map<String, Object> payload) {
            this(
                    payload != null ? (String) payload.get("content") : null,
                    payload != null ? (String) payload.get("formId") : null,
                    payload != null ? (FormData) payload.get("form") : null,
                    payload != null ? (String) payload.get("toolName") : null,
                    payload != null ? (Map<String, Object>) payload.get("input") : null,
                    payload != null ? payload.get("result") : null,
                    payload != null ? (String) payload.get("status") : null,
                    payload != null ? (String) payload.get("message") : null,
                    payload != null ? (String) payload.get("cause") : null,
                    payload != null ? (String) payload.get("thought") : null,
                    payload,
                    payload != null ? (Map<String, Object>) payload.get("metadata") : null
            );
        }
    }

    /**
     * Event domains.
     */
    public static class EventDomain {
        public static final String CHAT = "chat";
        public static final String INTERACTION = "interaction";
        public static final String THINKING = "thinking";
        public static final String TOOL = "tool";
        public static final String AUDIT = "audit";
    }

    /**
     * Event types.
     */
    public static class EventType {
        public static final String MESSAGE = "message";
        public static final String DELTA = "delta";
        public static final String FORM = "form";
        public static final String SUSPEND = "suspend";
        public static final String RESUME = "resume";
        public static final String START = "start";
        public static final String COMPLETE = "complete";
        public static final String ERROR = "error";
    }

    /**
     * Builder for constructing ArchflowEvent instances.
     */
    public static class Builder {
        private String domain;
        private String type;
        private String id;
        private Instant timestamp;
        private String correlationId;
        private String executionId;
        private Map<String, Object> payload;

        public Builder domain(String domain) {
            this.domain = domain;
            return this;
        }

        public Builder type(String type) {
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

        public Builder payload(Map<String, Object> payload) {
            this.payload = payload;
            return this;
        }

        public ArchflowEvent build() {
            if (domain == null || type == null) {
                throw new IllegalStateException("domain and type are required");
            }
            return new ArchflowEvent(this);
        }
    }
}
