package br.com.archflow.api.web.approval;

import br.com.archflow.api.approval.ApprovalController;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/archflow/approvals", "/api/approvals"})
public class SpringApprovalController {

    private static final Logger log = LoggerFactory.getLogger(SpringApprovalController.class);

    /**
     * Decisor registrado quando o servidor roda sem autenticação e o pedido chega sem token
     * (dev). Explícito de propósito: "me" ou um nome vindo do corpo pareceria uma pessoa.
     */
    static final String ANONYMOUS_DECIDER = "anonymous (auth disabled)";

    private final ApprovalController delegate;

    public SpringApprovalController(ApprovalController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/pending/count")
    public Map<String, Integer> pendingCount(@RequestParam(required = false) String tenantId) {
        return Map.of("count", delegate.listPending(tenantId).size());
    }

    /**
     * Decide um pedido em nome de <b>quem está autenticado</b>.
     *
     * <p>O decisor vinha do corpo ({@code responderId}): a UI mandava sempre "me" e qualquer
     * usuário autenticado podia gravar a decisão em nome de outro — o estado durável respondia
     * "quem autorizou isto" com o que o cliente quis (RCHFLW-38). Agora ele sai do token, pelos
     * atributos que o {@link JwtAuthenticationFilter} preenche (o mesmo caminho do
     * {@code AuditTrail}), e o {@code responderId} do corpo é ignorado. O nome de usuário vem
     * antes do subject porque é o que uma pessoa reconhece ao ler quem decidiu.</p>
     */
    @PostMapping("/{requestId}")
    public ApprovalResponse submitDecision(@PathVariable String requestId,
                                           @RequestBody ApprovalSubmitRequest request,
                                           HttpServletRequest http) {
        String decider = authenticatedDecider(http);
        String claimed = request.responderId();
        if (claimed != null && !claimed.isBlank() && !claimed.equals(decider) && !"me".equals(claimed)) {
            log.warn("Decisão de {} trazia responderId '{}' no corpo; registrada em nome de '{}'",
                    requestId, claimed, decider);
        }
        ApprovalSubmitRequest asAuthenticated = new ApprovalSubmitRequest(request.tenantId(),
                request.decision(), request.editedPayload(), decider, request.comment());
        return delegate.submitDecision(requestId, asAuthenticated);
    }

    @GetMapping("/pending")
    public List<ApprovalResponse> listPending(@RequestParam(required = false) String tenantId) {
        return delegate.listPending(tenantId);
    }

    @GetMapping("/{requestId}")
    public ApprovalResponse getApproval(@PathVariable String requestId,
                                        @RequestParam(required = false) String flowId) {
        // Com flowId, um pedido já decidido devolve quem decidiu em vez de 404 (RCHFLW-38).
        return delegate.getApproval(requestId, flowId);
    }

    /** Nome de usuário do token, senão o subject, senão o marcador de servidor sem autenticação. */
    static String authenticatedDecider(HttpServletRequest http) {
        Object username = http != null ? http.getAttribute(JwtAuthenticationFilter.ATTR_USERNAME) : null;
        if (username instanceof String s && !s.isBlank()) {
            return s;
        }
        Object userId = http != null ? http.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID) : null;
        if (userId instanceof String s && !s.isBlank()) {
            return s;
        }
        return ANONYMOUS_DECIDER;
    }
}
