package br.com.archflow.api.events;

import br.com.archflow.api.events.dto.MessageRequest;
import br.com.archflow.api.events.dto.MessageResponse;

/**
 * Controller para gatilhos conversacionais do ArchFlow.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /archflow/events/message — Recebe uma mensagem com tenantId + sessionId,
 *       monta ExecutionContext e aciona o agente configurado.</li>
 * </ul>
 *
 * <p>Este é o gatilho Tipo 1 (Conversacional) do AgentTriggerRuntime.
 */
public interface EventController {

    /**
     * Recebe uma mensagem conversacional e aciona o agente.
     *
     * @param request Dados da mensagem (tenantId, sessionId, agentId, message)
     * @return Resposta com requestId e status
     */
    MessageResponse sendMessage(MessageRequest request);
}
