package br.com.archflow.api.web.agent;

import br.com.archflow.api.agent.AgentController;
import br.com.archflow.api.agent.dto.InvokeRequest;
import br.com.archflow.api.agent.dto.InvokeResponse;
import br.com.archflow.api.config.TenantScope;
import org.springframework.web.bind.annotation.*;

/**
 * Invocação sob demanda de um agente (o "Invocar" do Agent lab).
 *
 * <p>A invocação roda no tenant da identidade ({@link TenantScope#forAction}, RCHFLW-103): o
 * {@code tenantId} do corpo vinha do formulário e era usado como veio, e qualquer autenticado acionava
 * o agente em nome de outro tenant. Outro tenant só com o papel ADMIN/SUPERADMIN (403 sem ele); com a
 * autenticação desligada (dev), o valor do corpo vale como antes.</p>
 */
@RestController
@RequestMapping("/archflow/agents")
public class SpringAgentController {

    private final AgentController delegate;
    private final TenantScope tenantScope;

    public SpringAgentController(AgentController delegate, TenantScope tenantScope) {
        this.delegate = delegate;
        this.tenantScope = tenantScope;
    }

    @PostMapping("/{agentId}/invoke")
    public InvokeResponse invoke(@PathVariable String agentId, @RequestBody InvokeRequest request) {
        String tenant = tenantScope.forAction(request.tenantId());
        InvokeRequest daIdentidade = tenant == null || tenant.equals(request.tenantId())
                ? request
                : new InvokeRequest(tenant, request.sessionId(), request.payload());
        return delegate.invoke(agentId, daIdentidade);
    }
}
