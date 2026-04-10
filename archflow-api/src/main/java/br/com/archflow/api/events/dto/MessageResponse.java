package br.com.archflow.api.events.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Resposta do endpoint de mensagem conversacional.
 *
 * @param requestId  ID único da requisição
 * @param tenantId   ID do tenant
 * @param sessionId  ID da sessão
 * @param agentId    ID do agente acionado
 * @param status     Status da execução (ACCEPTED, PROCESSING, COMPLETED, FAILED)
 * @param response   Resposta do agente (quando síncrono)
 * @param timestamp  Timestamp da resposta
 */
public record MessageResponse(
        String requestId,
        String tenantId,
        String sessionId,
        String agentId,
        String status,
        String response,
        Instant timestamp
) {
    public static MessageResponse accepted(String requestId, String tenantId,
                                            String sessionId, String agentId) {
        return new MessageResponse(requestId, tenantId, sessionId, agentId,
                "ACCEPTED", null, Instant.now());
    }
}
