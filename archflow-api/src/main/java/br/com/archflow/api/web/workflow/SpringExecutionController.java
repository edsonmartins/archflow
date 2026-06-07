package br.com.archflow.api.web.workflow;

import br.com.archflow.engine.api.FlowEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * In-memory execution history controller for development and demos.
 */
@RestController
@RequestMapping("/api/executions")
public class SpringExecutionController {

    private final InMemoryWorkflowRuntimeStore store;
    private final FlowEngine flowEngine;

    public SpringExecutionController(InMemoryWorkflowRuntimeStore store, FlowEngine flowEngine) {
        this.store = store;
        this.flowEngine = flowEngine;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String workflowId) {
        return store.executions().stream()
                .filter(execution -> workflowId == null || workflowId.equals(execution.get("workflowId")))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        var execution = store.getExecution(id);
        return execution != null ? ResponseEntity.ok(execution) : ResponseEntity.notFound().build();
    }

    /**
     * Cancels a running execution (design-0005 step 5). The engine keys active
     * runs by flow id, which equals the executionId (see the execute path), so we
     * cancel by id and mark the record CANCELLED.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id) {
        var execution = store.getExecution(id);
        if (execution == null) {
            return ResponseEntity.notFound().build();
        }
        // Only running executions can be cancelled; terminal ones are returned
        // as-is (idempotent). The engine throws if the flow is no longer active,
        // so swallow the race where it finishes between the check and the cancel.
        if (!"RUNNING".equals(String.valueOf(execution.get("status")))) {
            return ResponseEntity.ok(execution);
        }
        try {
            flowEngine.cancel(id);
        } catch (RuntimeException alreadyFinished) {
            // ignore — fall through to mark the record cancelled
        }
        store.completeExecution(id, "CANCELLED", null);
        return ResponseEntity.ok(store.getExecution(id));
    }
}
