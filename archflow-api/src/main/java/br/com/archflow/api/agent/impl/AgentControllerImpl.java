package br.com.archflow.api.agent.impl;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.agent.AgentController;
import br.com.archflow.api.agent.dto.InvokeRequest;
import br.com.archflow.api.agent.dto.InvokeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementação do AgentController.
 *
 * <p>Recebe invocações diretas, valida e delega para a
 * {@link AgentInvocationQueue} para processamento assíncrono.
 *
 * <p>Se a fila não for injetada, o controller devolve
 * {@link IllegalStateException} em runtime — isso é proposital:
 * antes devolvíamos 202 Accepted sem enfileirar, o que mascarava
 * configuração incorreta e descartava silenciosamente as invocações.
 */
public class AgentControllerImpl implements AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentControllerImpl.class);

    private final AgentInvocationQueue invocationQueue;

    /** @deprecated Use {@link #AgentControllerImpl(AgentInvocationQueue)}. */
    @Deprecated
    public AgentControllerImpl() {
        this(null);
    }

    public AgentControllerImpl(AgentInvocationQueue invocationQueue) {
        this.invocationQueue = invocationQueue;
    }

    @Override
    public InvokeResponse invoke(String agentId, InvokeRequest request) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(request, "request");

        if (invocationQueue == null) {
            log.error("AgentInvocationQueue is not wired — refusing invocation for agent={}",
                    agentId);
            throw new IllegalStateException(
                    "AgentInvocationQueue is not configured; wire a bean before accepting invocations");
        }

        String requestId = UUID.randomUUID().toString();
        String sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();
        Map<String, Object> payload = new HashMap<>(request.payload());
        payload.putIfAbsent("sessionId", sessionId);

        InvocationRequest invocation = new InvocationRequest(
                requestId, request.tenantId(), agentId, payload,
                null, 0, Instant.now());
        invocationQueue.submit(invocation);

        log.info("Agent invocation accepted: tenant={}, agent={}, requestId={}",
                request.tenantId(), agentId, requestId);

        return InvokeResponse.accepted(requestId, request.tenantId(), agentId);
    }
}
