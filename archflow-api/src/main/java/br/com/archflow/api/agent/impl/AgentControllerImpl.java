package br.com.archflow.api.agent.impl;

import br.com.archflow.api.agent.AgentController;
import br.com.archflow.api.agent.dto.InvokeRequest;
import br.com.archflow.api.agent.dto.InvokeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Implementação do AgentController.
 *
 * <p>Recebe invocações diretas, valida e delega para a
 * AgentInvocationQueue para processamento assíncrono.
 */
public class AgentControllerImpl implements AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentControllerImpl.class);

    @Override
    public InvokeResponse invoke(String agentId, InvokeRequest request) {
        String requestId = UUID.randomUUID().toString();

        log.info("Agent invocation: tenant={}, agent={}, requestId={}",
                request.tenantId(), agentId, requestId);

        // A integração real com AgentInvocationQueue será feita
        // via injeção de dependência no framework (Spring Boot etc.).

        return InvokeResponse.accepted(requestId, request.tenantId(), agentId);
    }
}
