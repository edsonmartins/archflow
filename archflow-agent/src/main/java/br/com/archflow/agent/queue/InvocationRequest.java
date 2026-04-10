package br.com.archflow.agent.queue;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Requisição de invocação de agente na fila.
 *
 * @param requestId        ID único da requisição
 * @param tenantId         ID do tenant (obrigatório)
 * @param agentId          ID do agente a invocar
 * @param payload          Dados para o agente
 * @param parentExecutionId ID da execução pai (para invocações agente-para-agente)
 * @param recursionDepth   Profundidade atual de recursão
 * @param createdAt        Timestamp de criação
 */
public record InvocationRequest(
        String requestId,
        String tenantId,
        String agentId,
        Map<String, Object> payload,
        String parentExecutionId,
        int recursionDepth,
        Instant createdAt
) {
    public InvocationRequest {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(agentId, "agentId is required");
        if (requestId == null) requestId = UUID.randomUUID().toString();
        if (payload == null) payload = Map.of();
        if (createdAt == null) createdAt = Instant.now();
        if (recursionDepth < 0) recursionDepth = 0;
    }

    /**
     * Cria uma requisição raiz (sem pai, profundidade 0).
     */
    public static InvocationRequest root(String tenantId, String agentId, Map<String, Object> payload) {
        return new InvocationRequest(null, tenantId, agentId, payload, null, 0, null);
    }

    /**
     * Cria uma requisição filha (invocação agente-para-agente).
     */
    public InvocationRequest childInvocation(String childAgentId, Map<String, Object> childPayload) {
        return new InvocationRequest(null, tenantId, childAgentId, childPayload,
                requestId, recursionDepth + 1, null);
    }
}
