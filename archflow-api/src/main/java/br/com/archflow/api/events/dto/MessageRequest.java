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
 * @param operation Operação do componente a executar; ausente, vale a padrão dele. Sem este campo, o chat do
 *                  Agent lab só servia a componentes que declaram operação padrão — um assistente que só tem
 *                  {@code analyze}/{@code respond} sempre falhava (RCHFLW-194)
 */
public record MessageRequest(
        String tenantId,
        String sessionId,
        String agentId,
        String message,
        Map<String, Object> metadata,
        String operation
) {
    /** Sem operação: a padrão do componente. */
    public MessageRequest(String tenantId, String sessionId, String agentId, String message,
                          Map<String, Object> metadata) {
        this(tenantId, sessionId, agentId, message, metadata, null);
    }

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
        if (operation != null && operation.isBlank()) operation = null;
    }
}
