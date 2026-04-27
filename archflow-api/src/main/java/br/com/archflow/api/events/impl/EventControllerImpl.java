package br.com.archflow.api.events.impl;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.events.EventController;
import br.com.archflow.api.events.dto.MessageRequest;
import br.com.archflow.api.events.dto.MessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementação do EventController.
 *
 * <p>Recebe mensagens conversacionais, valida o request e enfileira
 * a invocação correspondente na {@link AgentInvocationQueue}. A
 * mensagem e o sessionId são propagados via {@code payload} da
 * {@link InvocationRequest}.
 *
 * <p>Se a fila não for injetada, o endpoint devolve
 * {@link IllegalStateException} — antes retornava 202 sem enfileirar,
 * o que mascarava configuração incorreta e descartava mensagens.
 */
public class EventControllerImpl implements EventController {
    private static final Logger log = LoggerFactory.getLogger(EventControllerImpl.class);

    private final AgentInvocationQueue invocationQueue;

    /** @deprecated Use {@link #EventControllerImpl(AgentInvocationQueue)}. */
    @Deprecated
    public EventControllerImpl() {
        this(null);
    }

    public EventControllerImpl(AgentInvocationQueue invocationQueue) {
        this.invocationQueue = invocationQueue;
    }

    @Override
    public MessageResponse sendMessage(MessageRequest request) {
        Objects.requireNonNull(request, "request");
        if (invocationQueue == null) {
            log.error("AgentInvocationQueue is not wired — refusing message for agent={}",
                    request.agentId());
            throw new IllegalStateException(
                    "AgentInvocationQueue is not configured; wire a bean before accepting events");
        }

        String requestId = UUID.randomUUID().toString();
        String sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();

        Map<String, Object> payload = new HashMap<>();
        payload.put("message", request.message());
        payload.put("sessionId", sessionId);
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            payload.put("metadata", request.metadata());
        }

        InvocationRequest invocation = new InvocationRequest(
                requestId, request.tenantId(), request.agentId(), payload,
                null, 0, Instant.now());
        invocationQueue.submit(invocation);

        log.info("Message event accepted: tenant={}, session={}, agent={}, requestId={}",
                request.tenantId(), sessionId, request.agentId(), requestId);

        return MessageResponse.accepted(requestId, request.tenantId(), sessionId, request.agentId());
    }
}
