package br.com.archflow.api.web.approval;

import br.com.archflow.api.approval.ApprovalController;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.config.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/archflow/approvals", "/api/approvals"})
public class SpringApprovalController {

    private final ApprovalController delegate;

    public SpringApprovalController(ApprovalController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/pending/count")
    public Map<String, Integer> pendingCount(@RequestParam(required = false) String tenantId) {
        return Map.of("count", delegate.listPending(tenantOrRequest(tenantId)).size());
    }

    @PostMapping("/{requestId}")
    public ApprovalResponse submitDecision(@PathVariable String requestId, @RequestBody ApprovalSubmitRequest request) {
        return delegate.submitDecision(requestId, request);
    }

    @GetMapping("/pending")
    public List<ApprovalResponse> listPending(@RequestParam(required = false) String tenantId) {
        return delegate.listPending(tenantOrRequest(tenantId));
    }

    /**
     * Sem parâmetro, a fila é a do tenant da requisição — o mesmo com que as execuções da UI, da API e
     * do MCP passaram a gravar suas aprovações (RCHFLW-13). Assim a UI deixa de inventar um tenant
     * ("default", que não era o de ninguém) e o servidor resolve. Antes, a ausência significava
     * "todos os tenants". Um valor explícito (inclusive "all") segue repassado como estava: quem pode
     * pedir o quê é o RCHFLW-39, fora deste ajuste.
     */
    private static String tenantOrRequest(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? TenantContext.currentTenantId() : tenantId;
    }

    @GetMapping("/{requestId}")
    public ApprovalResponse getApproval(@PathVariable String requestId) {
        return delegate.getApproval(requestId);
    }
}
