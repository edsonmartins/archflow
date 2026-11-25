package br.com.archflow.api.web.orchestration;

import br.com.archflow.api.config.TenantContext;
import br.com.archflow.api.config.TenantScope;
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
 * plataforma) e os modelos permitidos (RCHFLW-79). Com a auth ligada, a orquestração roda no tenant
 * da requisição ({@link TenantContext}: a claim do JWT, o tenant padrão de um token sem claim ou a
 * personificação já conferida pelo filtro) e um corpo que nomeia outro é recusado (403), inclusive
 * para admin — quem age em outro tenant personifica, não escreve o id no corpo (RCHFLW-39). Antes só
 * se comparava com a claim, e um token sem ela (o login de hoje não a emite) rodava na chave do
 * tenant que o corpo quisesse. Com a auth desligada (dev) vale o do corpo, como antes.
 */
@RestController
@RequestMapping("/api/orchestration")
public class SpringDynamicWorkflowController {

    private final DynamicWorkflowService service;
    private final TenantScope tenantScope;

    public SpringDynamicWorkflowController(DynamicWorkflowService service, TenantScope tenantScope) {
        this.service = service;
        this.tenantScope = tenantScope;
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody DynamicWorkflowRequest request) {
        String doCorpo = request.tenantId() != null && !request.tenantId().isBlank()
                ? request.tenantId().trim() : null;
        String daRequisicao = TenantContext.currentTenantId();
        if (tenantScope.authEnabled() && doCorpo != null && !doCorpo.equals(daRequisicao)) {
            // Aceitar o do corpo contra o da identidade deixaria um usuário do tenant A rodar na chave
            // do B.
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "tenantId do corpo difere do tenant do token"));
        }
        String tenantId = doCorpo != null ? doCorpo : daRequisicao;
        DynamicWorkflowResponse resposta = service.run(new DynamicWorkflowRequest(request.goal(),
                request.decomposePrompt(), request.maxSubtasks(), request.voters(), request.minAgree(),
                request.maxRounds(), request.concurrency(), request.budgetTokens(), tenantId));
        return ResponseEntity.ok(resposta);
    }
}
