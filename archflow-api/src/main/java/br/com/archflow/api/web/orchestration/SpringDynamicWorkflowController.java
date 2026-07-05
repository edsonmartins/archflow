package br.com.archflow.api.web.orchestration;

import br.com.archflow.api.orchestration.DynamicWorkflowRequest;
import br.com.archflow.api.orchestration.DynamicWorkflowResponse;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fires a dynamic multi-agent workflow (ADR-0002): decompose the goal, fan out
 * to catalog-routed agents, adversarially verify and loop until convergence,
 * bounded by an optional token budget.
 */
@RestController
@RequestMapping("/api/orchestration")
public class SpringDynamicWorkflowController {

    private final DynamicWorkflowService service;

    public SpringDynamicWorkflowController(DynamicWorkflowService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public ResponseEntity<DynamicWorkflowResponse> run(@RequestBody DynamicWorkflowRequest request) {
        return ResponseEntity.ok(service.run(request));
    }
}
