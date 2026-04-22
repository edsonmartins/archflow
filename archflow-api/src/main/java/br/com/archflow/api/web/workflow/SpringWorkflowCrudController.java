package br.com.archflow.api.web.workflow;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * In-memory CRUD controller for workflows.
 *
 * <p>Serves the frontend's workflow list/editor. Stores workflow JSON documents
 * in memory — suitable for development and demos. In production, replace with
 * a database-backed implementation.
 */
@RestController
@RequestMapping("/api/workflows")
public class SpringWorkflowCrudController {

    private final InMemoryWorkflowRuntimeStore store;

    public SpringWorkflowCrudController(InMemoryWorkflowRuntimeStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return store.workflows().stream()
                .map(this::toSummary)
                .sorted(Comparator.comparing((Map<String, Object> m) ->
                        m.getOrDefault("updatedAt", "").toString()).reversed())
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        var wf = store.getWorkflow(id);
        return wf != null ? ResponseEntity.ok(wf) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        var created = new LinkedHashMap<>(body);
        var id = "wf-" + UUID.randomUUID().toString().substring(0, 8);
        created.put("id", id);
        created.put("status", "draft");
        created.put("updatedAt", Instant.now().toString());
        created.putIfAbsent("steps", List.of());
        created.putIfAbsent("configuration", Map.of());
        store.putWorkflow(id, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (!store.hasWorkflow(id)) return ResponseEntity.notFound().build();
        var updated = new LinkedHashMap<>(body);
        updated.put("id", id);
        updated.put("updatedAt", Instant.now().toString());
        updated.putIfAbsent("status", "draft");
        updated.putIfAbsent("steps", List.of());
        updated.putIfAbsent("configuration", Map.of());
        store.putWorkflow(id, updated);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!store.hasWorkflow(id)) return ResponseEntity.notFound().build();
        store.deleteWorkflow(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<Map<String, Object>> execute(@PathVariable String id,
                                                        @RequestBody(required = false) Map<String, Object> input) {
        var workflow = store.getWorkflow(id);
        if (workflow == null) return ResponseEntity.notFound().build();
        var execution = store.createExecution(id, workflowName(workflow));
        return ResponseEntity.ok(Map.of(
                "executionId", execution.get("id"),
                "status", execution.get("status"),
                "workflowId", id,
                "startedAt", execution.get("startedAt")
        ));
    }

    // ── helpers ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> toSummary(Map<String, Object> wf) {
        var meta = (Map<String, Object>) wf.getOrDefault("metadata", Map.of());
        var steps = wf.getOrDefault("steps", List.of());
        int stepCount = steps instanceof List<?> l ? l.size() : 0;
        return Map.of(
                "id", wf.getOrDefault("id", ""),
                "name", meta.getOrDefault("name", "Untitled"),
                "description", meta.getOrDefault("description", ""),
                "version", meta.getOrDefault("version", "1.0.0"),
                "status", wf.getOrDefault("status", "draft"),
                "updatedAt", wf.getOrDefault("updatedAt", ""),
                "stepCount", stepCount
        );
    }

    @SuppressWarnings("unchecked")
    private String workflowName(Map<String, Object> workflow) {
        var metadata = workflow.get("metadata");
        if (metadata instanceof Map<?, ?> meta) {
            var name = meta.get("name");
            return name != null ? name.toString() : "Untitled";
        }
        return "Untitled";
    }
}
