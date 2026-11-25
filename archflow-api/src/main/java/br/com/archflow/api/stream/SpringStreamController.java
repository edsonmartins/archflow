package br.com.archflow.api.stream;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamEmitter;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.config.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 *
 * <p><b>Só o próprio tenant (RCHFLW-49).</b> O {@code {tenantId}} do caminho tem de ser o tenant
 * efetivo da requisição ({@link TenantContext#currentTenantId()}: o do JWT ou, para um superadmin,
 * o da personificação já verificada pelo {@code ImpersonationFilter}); qualquer outro valor recebe
 * {@code 403} e nenhum emitter é registrado. Antes o caminho ia direto para o registry, e qualquer
 * usuário autenticado assinava "{@code <outro tenant>:<conversa>}" e lia em tempo real as respostas
 * do chat de outro tenant. O canal administrativo ({@code __admin__:<tenant>}, alimentado pelo
 * ingest e pelos eventos de flow) só abre para o próprio tenant: {@code /__admin__/<meu tenant>}.
 *
 * <p>{@code GET /api/stream/{sessionId}} é a forma sem tenant: o servidor usa o tenant efetivo. É a
 * que a UI usa — ela não tem como saber o tenant de um token sem a claim, e era por isso que
 * inventava {@code "default"}.
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

    /**
     * Prefixo do canal administrativo, {@code __admin__:<tenant>} — o mesmo literal que
     * {@code RegistryFlowLifecycleListener} e {@code EventIngestControllerImpl} usam ao publicar.
     */
    public static final String ADMIN_CHANNEL_TENANT = "__admin__";

    @Override
    @GetMapping(value = "/{tenantId}/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String tenantId, @PathVariable String sessionId) {
        return assinar(tenantId, sessionId, TenantContext.currentTenantId());
    }

    /**
     * Stream do tenant da própria requisição — o servidor resolve o tenant, o cliente não o informa.
     *
     * <p>Passa pela mesma conferência da rota com tenant: o tenant efetivo pode ser o id reservado
     * {@code "__admin__"} (superadmin personificando-o, ou {@code X-Tenant-Id} com a auth desligada),
     * e aí {@code /api/stream/<B>} cairia em {@code __admin__:<B>}, o canal administrativo de B, que
     * a rota de dois segmentos já recusa.
     */
    @GetMapping(value = "/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamDoProprioTenant(@PathVariable String sessionId) {
        String effective = TenantContext.currentTenantId();
        return assinar(effective, sessionId, effective);
    }

    private ResponseEntity<SseEmitter> assinar(String tenantId, String sessionId, String effective) {
        if (!podeAssinar(tenantId, sessionId, effective)) {
            // Sem corpo: a rota só produz text/event-stream, e o status basta para o cliente parar.
            // O log registra a tentativa — ler o canal de outro tenant não é engano inocente.
            log.warn("Rejected SSE subscription to another tenant's channel: path={}:{} effectiveTenant={}",
                    tenantId, sessionId, effective);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(open(tenantId, sessionId));
    }

    /**
     * O canal "{@code tenant:sessão}" é aberto só para o tenant efetivo. O administrativo é a
     * exceção de forma, não de tenant: o tenant dele está no segundo segmento, que tem de ser o
     * efetivo. Um tenant efetivo igual ao id reservado (superadmin personificando
     * {@code "__admin__"}) cai no mesmo ramo e só alcança {@code __admin__:__admin__} — sem isso, a
     * regra geral deixaria assinar {@code __admin__:<qualquer tenant>}.
     */
    static boolean podeAssinar(String tenantId, String sessionId, String effective) {
        if (ADMIN_CHANNEL_TENANT.equals(tenantId)) {
            return effective.equals(sessionId);
        }
        return effective.equals(tenantId);
    }

    private SseEmitter open(String tenantId, String sessionId) {
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
