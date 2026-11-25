package br.com.archflow.api.web.events;

import br.com.archflow.api.config.TenantScope;
import br.com.archflow.api.events.EventController;
import br.com.archflow.api.events.dto.MessageRequest;
import br.com.archflow.api.events.dto.MessageResponse;
import org.springframework.web.bind.annotation.*;

/**
 * Mensagem conversacional para um agente (o chat do Agent lab).
 *
 * <p>Mesma regra do invoke ({@link TenantScope#forAction}, RCHFLW-103): a mensagem roda no tenant da
 * identidade, outro tenant só com o papel ADMIN/SUPERADMIN, e com a autenticação desligada o
 * {@code tenantId} do corpo vale como antes.</p>
 */
@RestController
@RequestMapping("/archflow/events")
public class SpringEventController {

    private final EventController delegate;
    private final TenantScope tenantScope;

    public SpringEventController(EventController delegate, TenantScope tenantScope) {
        this.delegate = delegate;
        this.tenantScope = tenantScope;
    }

    @PostMapping("/message")
    public MessageResponse sendMessage(@RequestBody MessageRequest request) {
        String tenant = tenantScope.forAction(request.tenantId());
        MessageRequest daIdentidade = tenant == null || tenant.equals(request.tenantId())
                ? request
                : new MessageRequest(tenant, request.sessionId(), request.agentId(), request.message(),
                        request.metadata());
        return delegate.sendMessage(daIdentidade);
    }
}
