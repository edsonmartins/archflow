package br.com.archflow.api.events.impl;

import br.com.archflow.api.events.EventController;
import br.com.archflow.api.events.dto.MessageRequest;
import br.com.archflow.api.events.dto.MessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Implementação do EventController.
 *
 * <p>Recebe mensagens conversacionais, valida o request e delega
 * para o AgentTriggerRuntime montar o ExecutionContext e acionar
 * o agente adequado.
 */
public class EventControllerImpl implements EventController {
    private static final Logger log = LoggerFactory.getLogger(EventControllerImpl.class);

    @Override
    public MessageResponse sendMessage(MessageRequest request) {
        String requestId = UUID.randomUUID().toString();
        String sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();

        log.info("Received message event: tenant={}, session={}, agent={}, requestId={}",
                request.tenantId(), sessionId, request.agentId(), requestId);

        // O acoplamento real com FlowEngine/AgentExecutor será feito
        // via injeção de dependência no framework (Spring Boot etc.).
        // Aqui definimos apenas o contrato da API.

        return MessageResponse.accepted(requestId, request.tenantId(), sessionId, request.agentId());
    }
}
