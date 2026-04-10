package br.com.archflow.api.events.dto;

import java.util.Map;

/**
 * Request para o endpoint de mensagem conversacional.
 *
 * @param tenantId  ID do tenant (obrigatório)
 * @param sessionId ID da sessão de conversa
 * @param agentId   ID do agente a acionar
 * @param message   Conteúdo da mensagem
 * @param metadata  Metadados opcionais
 */
public record MessageRequest(
        String tenantId,
        String sessionId,
        String agentId,
        String message,
        Map<String, Object> metadata
) {
    public MessageRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        if (metadata == null) metadata = Map.of();
    }
}
