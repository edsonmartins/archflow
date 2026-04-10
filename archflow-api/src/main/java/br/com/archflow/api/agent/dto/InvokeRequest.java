package br.com.archflow.api.agent.dto;

import java.util.Map;

/**
 * Request para invocação direta de um agente (gatilho sob demanda).
 *
 * @param tenantId  ID do tenant (obrigatório)
 * @param sessionId ID da sessão (opcional — gerado se ausente)
 * @param payload   Dados passados ao agente via ExecutionContext.variables
 */
public record InvokeRequest(
        String tenantId,
        String sessionId,
        Map<String, Object> payload
) {
    public InvokeRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (payload == null) payload = Map.of();
    }
}
