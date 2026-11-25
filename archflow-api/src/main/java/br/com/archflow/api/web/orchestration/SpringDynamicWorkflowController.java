package br.com.archflow.api.web.orchestration;

import br.com.archflow.api.config.TenantContext;
import br.com.archflow.api.orchestration.DynamicWorkflowRequest;
import br.com.archflow.api.orchestration.DynamicWorkflowResponse;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Fires a dynamic multi-agent workflow (ADR-0002): decompose the goal, fan out
 * to catalog-routed agents, adversarially verify and loop until convergence,
 * bounded by an optional token budget.
 *
 * <p>O {@code tenantId} do corpo escolhe a chave de LLM do planejador (a do tenant antes da da
 * plataforma) e os modelos permitidos (RCHFLW-79). Com JWT que traz o tenant, um corpo que nomeia
 * outro é recusado (403) — a mesma regra do {@code QpAgentController}; sem tenant no corpo vale o da
 * requisição ({@link TenantContext}).
 */
@RestController
@RequestMapping("/api/orchestration")
public class SpringDynamicWorkflowController {

    private final DynamicWorkflowService service;

    public SpringDynamicWorkflowController(DynamicWorkflowService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody DynamicWorkflowRequest request) {
        String doCorpo = request.tenantId() != null && !request.tenantId().isBlank()
                ? request.tenantId().trim() : null;
        var doToken = TenantContext.authenticatedTenantId();
        if (doCorpo != null && doToken.isPresent() && !doToken.get().equals(doCorpo)) {
            // Aceitar o do corpo contra o do token deixaria um usuário do tenant A rodar na chave do B.
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "tenantId do corpo difere do tenant do token"));
        }
        String tenantId = doCorpo != null ? doCorpo : TenantContext.currentTenantId();
        DynamicWorkflowResponse resposta = service.run(new DynamicWorkflowRequest(request.goal(),
                request.decomposePrompt(), request.maxSubtasks(), request.voters(), request.minAgree(),
                request.maxRounds(), request.concurrency(), request.budgetTokens(), tenantId));
        return ResponseEntity.ok(resposta);
    }
}
