package br.com.archflow.api.web.workflow;

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

    public SpringExecutionController(InMemoryWorkflowRuntimeStore store) {
        this.store = store;
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
}
