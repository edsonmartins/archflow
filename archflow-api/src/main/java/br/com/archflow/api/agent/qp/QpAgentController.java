package br.com.archflow.api.agent.qp;

import br.com.archflow.api.config.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoint headless do agente QP: dada uma mensagem do cliente, roda o loop de
 * tool-calling contra o VendaX Core (MCP) e devolve o resultado + o quote.
 *
 * <p>{@code POST /api/agents/qp/quote}. O {@code tenantId} do corpo é opcional;
 * quando ausente, usa o tenant da requisição ({@link TenantContext}). Com um JWT
 * que traz {@code tenantId}, um corpo que nomeia outro tenant é recusado (403).
 */
@RestController
@RequestMapping("/api/agents/qp")
public class QpAgentController {

    private final QpAgentService service;

    public QpAgentController(QpAgentService service) {
        this.service = service;
    }

    @PostMapping("/quote")
    public ResponseEntity<?> quote(@RequestBody QpAgentService.QpRequest body) {
        String doCorpo = body.tenantId() != null && !body.tenantId().isBlank() ? body.tenantId().trim() : null;
        var doToken = TenantContext.authenticatedTenantId();
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

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleUnconfigured(IllegalStateException ex) {
        // VendaX Core não configurado para o tenant, ou handshake falhou.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", ex.getMessage()));
    }
}
