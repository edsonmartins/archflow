package br.com.archflow.api.agent;

import br.com.archflow.api.agent.dto.InvokeRequest;
import br.com.archflow.api.agent.dto.InvokeResponse;

/**
 * Controller para invocação direta de agentes (gatilho sob demanda).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /archflow/agents/{agentId}/invoke — Invoca um agente diretamente
 *       com tenantId e payload. O request é enfileirado na AgentInvocationQueue.</li>
 * </ul>
 *
 * <p>Este é o gatilho Tipo 4 (Demanda) do AgentTriggerRuntime.
 */
public interface AgentController {

    /**
     * Invoca um agente diretamente.
     *
     * @param agentId ID do agente a invocar
     * @param request Dados da invocação (tenantId, payload)
     * @return Resposta com requestId e status
     */
    InvokeResponse invoke(String agentId, InvokeRequest request);
}
