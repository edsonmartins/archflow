package br.com.archflow.api.agent.dto;

import java.time.Instant;

/**
 * Resposta da invocação direta de um agente.
 *
 * @param requestId  ID único da requisição
 * @param tenantId   ID do tenant
 * @param agentId    ID do agente invocado
 * @param status     Status (ACCEPTED, COMPLETED, FAILED)
 * @param timestamp  Timestamp
 */
public record InvokeResponse(
        String requestId,
        String tenantId,
        String agentId,
        String status,
        Instant timestamp
) {
    public static InvokeResponse accepted(String requestId, String tenantId, String agentId) {
        return new InvokeResponse(requestId, tenantId, agentId, "ACCEPTED", Instant.now());
    }
}
