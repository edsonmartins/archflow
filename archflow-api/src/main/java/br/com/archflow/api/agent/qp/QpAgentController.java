package br.com.archflow.api.agent.qp;

import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.config.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Optional;

/**
 * Endpoint headless do agente QP: dada uma mensagem do cliente, roda o loop de
 * tool-calling contra o VendaX Core (MCP) e devolve o resultado + o quote.
 *
 * <p>{@code POST /api/agents/qp/quote}. O {@code tenantId} do corpo é opcional;
 * quando ausente, usa o tenant da requisição ({@link TenantContext}). Com um JWT que traz
 * {@code tenantId}, um corpo que nomeia outro tenant é recusado (403). Com a autenticação ligada,
 * o token sem a claim também prende o corpo: ao tenant efetivo da requisição — o padrão, ou o que o
 * {@link br.com.archflow.api.config.ImpersonationFilter} deixou personificar.
 */
@RestController
@RequestMapping("/api/agents/qp")
public class QpAgentController {

    private final QpAgentService service;
    private final boolean authEnabled;

    public QpAgentController(QpAgentService service,
                             @Value("${archflow.security.auth.enabled:false}") boolean authEnabled) {
        this.service = service;
        this.authEnabled = authEnabled;
    }

    @PostMapping("/quote")
    public ResponseEntity<?> quote(@RequestBody QpAgentService.QpRequest body) {
        String doCorpo = body.tenantId() != null && !body.tenantId().isBlank() ? body.tenantId().trim() : null;
        Optional<String> doToken = TenantContext.authenticatedTenantId();
        if (doToken.isEmpty() && authEnabled && autenticadoPorJwt()) {
            // Token sem a claim não é "livre": sem isto um usuário do tenant padrão nomeava no corpo
            // qualquer tenant e rodava na chave dele (QA2 P5). Vale o tenant efetivo, não o padrão cru —
            // é o que o ImpersonationFilter gravou: o padrão, ou o tenant que um admin personifica, e
            // este o corpo pode repetir. Só com auth ligada: em dev o corpo escolhia o tenant e continua.
            doToken = Optional.of(TenantContext.currentTenantId());
        }
        if (doCorpo != null && doToken.isPresent() && !doToken.get().equals(doCorpo)) {
            // O tenant escolhe a chave de LLM (chave do tenant antes da da plataforma): aceitar o do
            // corpo contra o do token deixaria um usuário do tenant A rodar na chave do tenant B.
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "tenantId do corpo difere do tenant do token"));
        }
        String tenantId = doCorpo != null ? doCorpo : TenantContext.currentTenantId();
        var request = new QpAgentService.QpRequest(
                tenantId, body.clienteRef(), body.vendedorRef(),
                body.modoEntrada(), body.entrada(), body.itensJaNoPedido());
        return ResponseEntity.ok(service.quote(request));
    }

    /** {@code true} quando o filtro JWT validou um token de usuário nesta requisição. */
    private static boolean autenticadoPorJwt() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra
                && sra.getRequest().getAttribute(JwtAuthenticationFilter.ATTR_USER_ID) != null;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleUnconfigured(IllegalStateException ex) {
        // VendaX Core não configurado para o tenant, ou handshake falhou.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", ex.getMessage()));
    }
}
