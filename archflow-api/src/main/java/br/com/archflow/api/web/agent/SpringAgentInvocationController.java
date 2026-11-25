package br.com.archflow.api.web.agent;

import br.com.archflow.api.agent.invocation.AgentInvocationLookup;
import br.com.archflow.api.config.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET /archflow/agents/invocations/{requestId}?tenantId=...} — o andamento e o resultado de uma
 * invocação enfileirada pelo invoke, pelo chat, por um gatilho ou por um evento (RCHFLW-80).
 *
 * <p>Fica num controller próprio, e não no {@link SpringAgentController}, para não misturar a
 * consulta com o contrato de invocação; o caminho {@code /invocations/{id}} não colide com
 * {@code POST /{agentId}/invoke}.</p>
 *
 * <p>O {@code tenantId} da consulta passa pela mesma regra do invoke ({@link TenantScope#forAction},
 * RCHFLW-103): sem ela, quem não pode invocar em outro tenant ainda leria o resultado de uma invocação
 * de lá só por saber o requestId.</p>
 */
@RestController
@RequestMapping("/archflow/agents/invocations")
public class SpringAgentInvocationController {

    private final AgentInvocationLookup lookup;
    private final TenantScope tenantScope;

    public SpringAgentInvocationController(AgentInvocationLookup lookup, TenantScope tenantScope) {
        this.lookup = lookup;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<?> get(@PathVariable String requestId, @RequestParam String tenantId) {
        return lookup.find(requestId, tenantScope.forAction(tenantId))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "invocation not found: " + requestId)));
    }
}
