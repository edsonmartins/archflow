package br.com.archflow.api.agent.invocation;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;

import java.util.Objects;
import java.util.Optional;

/**
 * Responde "o que aconteceu com esta invocação": o resultado gravado; senão a requisição em execução
 * nesta réplica ({@code RUNNING}); senão a requisição ainda na fila ({@code QUEUED}).
 *
 * <p>O tenant da consulta precisa ser o que a invocação carregou. Divergente, a resposta é "não existe"
 * — a mesma de um id desconhecido, para que um requestId alheio não revele nem que existe. O tenant
 * vem da própria invocação, como no invoke; quem decide de onde ele vem é o modelo de tenant da API,
 * não esta consulta.</p>
 */
public class AgentInvocationLookup {

    private final AgentInvocationResultStore store;
    private final AgentInvocationQueue queue;
    private final AgentInvocationConsumer consumer;

    /** @param consumer {@code null} quando o consumidor está desligado nesta réplica */
    public AgentInvocationLookup(AgentInvocationResultStore store, AgentInvocationQueue queue,
                                 AgentInvocationConsumer consumer) {
        this.store = Objects.requireNonNull(store, "store");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.consumer = consumer;
    }

    public Optional<AgentInvocationResult> find(String requestId, String tenantId) {
        if (requestId == null || requestId.isBlank() || tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        Optional<AgentInvocationResult> recorded = store.find(requestId);
        if (recorded.isPresent()) {
            return recorded.filter(r -> tenantId.equals(r.tenantId()));
        }
        Optional<InvocationRequest> running = consumer == null ? Optional.empty() : consumer.inFlight(requestId);
        if (running.isPresent()) {
            return running.filter(r -> tenantId.equals(r.tenantId()))
                    .map(r -> AgentInvocationResult.running(r, 0, null));
        }
        return queue.find(requestId)
                .filter(r -> tenantId.equals(r.tenantId()))
                .map(AgentInvocationResult::queued);
    }
}
