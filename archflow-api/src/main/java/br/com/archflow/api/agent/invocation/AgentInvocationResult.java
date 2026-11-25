package br.com.archflow.api.agent.invocation;

import br.com.archflow.agent.queue.InvocationRequest;

import java.time.Instant;
import java.util.Objects;

/**
 * O andamento de uma invocação de agente, consultável pelo {@code requestId} que o invoke, o chat, o
 * gatilho ou o evento devolveram ({@code GET /archflow/agents/invocations/{requestId}}).
 *
 * <p>Existe porque a resposta da fila é sempre {@code ACCEPTED}: sem um lugar para ver o que aconteceu
 * depois, "aceito" era tudo o que alguém chegava a saber (RCHFLW-80).</p>
 *
 * @param output   a saída do agente já convertida em dados JSON (mapa, lista, texto, número), para que a
 *                 consulta devolva o mesmo formato da memória e do banco
 * @param error    a mensagem da falha, quando {@link Status#FAILED}
 * @param attempts execuções iniciadas; mais de uma quer dizer que uma anterior morreu no meio
 */
public record AgentInvocationResult(
        String requestId,
        String tenantId,
        String agentId,
        Status status,
        Object output,
        String error,
        int attempts,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {

    /** QUEUED e RUNNING são transitórios; COMPLETED e FAILED, finais. */
    public enum Status {
        QUEUED, RUNNING, COMPLETED, FAILED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED;
        }
    }

    public AgentInvocationResult {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
    }

    /** Ainda na fila, sem consumidor: não há registro de resultado, só a requisição. */
    public static AgentInvocationResult queued(InvocationRequest request) {
        return new AgentInvocationResult(request.requestId(), request.tenantId(), request.agentId(),
                Status.QUEUED, null, null, 0, request.createdAt(), null, null);
    }

    public static AgentInvocationResult running(InvocationRequest request, int attempts, Instant startedAt) {
        return new AgentInvocationResult(request.requestId(), request.tenantId(), request.agentId(),
                Status.RUNNING, null, null, attempts, request.createdAt(), startedAt, null);
    }

    public AgentInvocationResult completed(Object output, Instant finishedAt) {
        return new AgentInvocationResult(requestId, tenantId, agentId, Status.COMPLETED, output, null,
                attempts, createdAt, startedAt, finishedAt);
    }

    public AgentInvocationResult failed(String error, Instant finishedAt) {
        return new AgentInvocationResult(requestId, tenantId, agentId, Status.FAILED, null, error,
                attempts, createdAt, startedAt, finishedAt);
    }
}
