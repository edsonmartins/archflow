package br.com.archflow.api.web.approval;

import br.com.archflow.api.approval.ApprovalController;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.config.TenantContext;
import br.com.archflow.api.config.TenantScope;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * HTTP da fila de aprovações. O tenant de cada operação vem da identidade ({@link TenantScope}): o
 * {@code tenantId} da query ou do corpo só é conferido — {@code "all"} ou outro tenant exigem o papel
 * admin (403 sem ele), e um pedido fora do recorte é 404, como um id desconhecido (RCHFLW-39).
 */
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
    private final TenantScope tenantScope;

    public SpringApprovalController(ApprovalController delegate, TenantScope tenantScope) {
        this.delegate = delegate;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/pending/count")
    public Map<String, Integer> pendingCount(@RequestParam(required = false) String tenantId) {
        return Map.of("count", delegate.listPending(tenantOrRequest(tenantId)).size());
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
     *
     * <p>O {@code tenantId} do corpo (a UI manda o da própria pendência) é conferido pelo
     * {@link TenantScope}: o pedido só é decidido se for do tenant resultante (RCHFLW-39). Com a auth
     * desligada ele continua ignorado na busca, como antes ({@link TenantScope#forItemLookup}).</p>
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
        return delegate.submitDecision(requestId, asAuthenticated, tenantScope.forItemLookup(request.tenantId()));
    }

    @GetMapping("/pending")
    public List<ApprovalResponse> listPending(@RequestParam(required = false) String tenantId) {
        return delegate.listPending(tenantOrRequest(tenantId));
    }

    /**
     * Sem parâmetro, a fila é a do tenant da requisição — o mesmo com que as execuções da UI, da API e
     * do MCP passaram a gravar suas aprovações (RCHFLW-13). Um valor explícito passa pelo
     * {@link TenantScope}: "all" ou outro tenant só para admin (RCHFLW-39). Com a auth desligada o
     * valor explícito segue como veio, e a ausência continua sendo o tenant da requisição.
     */
    private String tenantOrRequest(String tenantId) {
        String recorte = tenantScope.forListing(tenantId);
        return recorte == null ? TenantContext.currentTenantId() : recorte;
    }

    /**
     * @param tenantId opcional, conferido como o da fila: o superadmin que abre um pedido da visão
     *                 "all" manda "all"; sem ele, só pedidos do tenant da requisição são encontrados.
     *                 Com a auth desligada não entra na busca, como antes do parâmetro existir
     */
    @GetMapping("/{requestId}")
    public ApprovalResponse getApproval(@PathVariable String requestId,
                                        @RequestParam(required = false) String flowId,
                                        @RequestParam(required = false) String tenantId) {
        // Com flowId, um pedido já decidido devolve quem decidiu em vez de 404 (RCHFLW-38).
        return delegate.getApproval(requestId, flowId, tenantScope.forItemLookup(tenantId));
    }

    /**
     * Pedido desconhecido, já decidido ou de outro tenant: 404 com o motivo. Antes a exceção saía
     * como 500 — e a fila agora depende de "não encontrado" para não revelar pedidos alheios.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", String.valueOf(e.getMessage()), "status", 404));
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
