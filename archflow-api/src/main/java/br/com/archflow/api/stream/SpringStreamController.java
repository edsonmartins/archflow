package br.com.archflow.api.stream;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamEmitter;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stream SSE por tenant/sessão em {@code GET /api/stream/{tenantId}/{sessionId}}
 * — o endpoint que o frontend ({@code src/services/event-stream.ts}) consome
 * para chat, live events e notificações.
 *
 * <p>Cada conexão registra um {@link EventStreamEmitter} no
 * {@link EventStreamRegistry} sob a chave {@code tenantId:sessionId}; qualquer
 * produtor (ex.: {@code ConversationReplyService}) publica via
 * {@code registry.broadcast(tenantId + ":" + sessionId, event)}.
 *
 * <p>O payload segue o envelope esperado pelo frontend:
 * {@code {"envelope": {domain, type, id, timestamp, ...}, "data": {...}, "metadata": {...}}}
 * com domain/type em minúsculas ({@link ArchflowDomain#getValue()}).
 */
@RestController
@RequestMapping("/api/stream")
public class SpringStreamController implements StreamController {

    private static final Logger log = LoggerFactory.getLogger(SpringStreamController.class);

    /** Sem timeout do lado do servlet — o registry cuida de heartbeat/expiração. */
    private static final long NO_TIMEOUT = 0L;

    private final EventStreamRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpringStreamController(EventStreamRegistry registry) {
        this.registry = registry;
    }

    @Override
    @GetMapping(value = "/{tenantId}/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String tenantId, @PathVariable String sessionId) {
        SseEmitter sse = new SseEmitter(NO_TIMEOUT);
        EventStreamEmitter emitter = registry.createEmitter(tenantId, sessionId);

        emitter.onSend(event -> {
            try {
                sse.send(SseEmitter.event().data(toJson(event), MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // Cliente desconectou (ou pipe quebrou): encerra e desregistra.
                log.debug("SSE send failed for {}:{} — closing emitter: {}",
                        tenantId, sessionId, e.getMessage());
                registry.unregister(emitter.getEmitterId());
                try {
                    sse.complete();
                } catch (Exception ignored) {
                    // já fechado
                }
            }
        });

        Runnable cleanup = () -> registry.unregister(emitter.getEmitterId());
        sse.onCompletion(cleanup);
        sse.onTimeout(() -> {
            cleanup.run();
            sse.complete();
        });
        sse.onError(t -> cleanup.run());

        emitter.send(ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.CONNECTED)
                .tenantId(tenantId)
                .executionId(tenantId + ":" + sessionId)
                .data(Map.of("tenantId", tenantId, "sessionId", sessionId))
                .build());

        return sse;
    }

    private String toJson(ArchflowEvent event) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("domain", event.getDomain().getValue());
        envelope.put("type", event.getType().getValue());
        envelope.put("id", event.getId());
        envelope.put("timestamp", event.getTimestamp().toString());
        if (event.getCorrelationId() != null) {
            envelope.put("correlationId", event.getCorrelationId());
        }
        if (event.getExecutionId() != null) {
            envelope.put("executionId", event.getExecutionId());
        }
        if (event.getTenantId() != null) {
            envelope.put("tenantId", event.getTenantId());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("envelope", envelope);
        payload.put("data", event.getData());
        if (!event.getMetadata().isEmpty()) {
            payload.put("metadata", event.getMetadata());
        }
        return objectMapper.writeValueAsString(payload);
    }
}
