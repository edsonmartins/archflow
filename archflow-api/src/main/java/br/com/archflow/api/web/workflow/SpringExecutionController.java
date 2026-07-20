package br.com.archflow.api.web.workflow;

import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowState;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory execution history controller for development and demos.
 */
@RestController
@RequestMapping("/api/executions")
public class SpringExecutionController {

    private final WorkflowRuntimeStore store;
    private final FlowEngine flowEngine;
    private final StateManager stateManager;

    public SpringExecutionController(WorkflowRuntimeStore store, FlowEngine flowEngine,
                                     StateManager stateManager) {
        this.store = store;
        this.flowEngine = flowEngine;
        this.stateManager = stateManager;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String workflowId,
                                          @RequestParam(required = false) Integer limit) {
        // Filtering + limiting happen inside the store on the raw records, so a
        // bounded poll only deep-copies the survivors, not the whole history.
        return store.executions(workflowId, limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        var execution = store.getExecution(id);
        if (execution == null) {
            return ResponseEntity.notFound().build();
        }
        // Enrich with the materialized dynamic tree, if any (design-0005 step 4).
        FlowState state = stateManager.loadState(id);
        if (state != null && state.getExecutionPaths() != null && !state.getExecutionPaths().isEmpty()) {
            Map<String, Object> enriched = new LinkedHashMap<>(execution);
            enriched.put("executionPaths", state.getExecutionPaths());
            return ResponseEntity.ok(enriched);
        }
        return ResponseEntity.ok(execution);
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

    /**
     * Resumes a paused/awaiting execution (design-0005 step 5). Pre-checks the
     * saved state so obviously non-resumable runs get a fast 409; the engine
     * additionally requires the flow to be registered (done at execute time).
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String id) {
        var execution = store.getExecution(id);
        if (execution == null) {
            return ResponseEntity.notFound().build();
        }
        FlowState state = stateManager.loadState(id);
        if (state == null) {
            return ResponseEntity.status(409).body(Map.<String, Object>of("error", "no saved state to resume"));
        }
        if (state.getStatus() == null || state.getStatus().isFinal()) {
            return ResponseEntity.status(409).body(
                    Map.<String, Object>of("error", "execution is in a final state and cannot be resumed"));
        }

        ExecutionContext ctx = new DefaultExecutionContext(
                state.getTenantId(), "runner", id,
                MessageWindowChatMemory.builder().maxMessages(20).build());
        // Mark RUNNING BEFORE wiring the completion callback. resumeFlow runs on a
        // virtual thread, so a fast-failing resume could complete the record
        // terminally first; if markResumed then ran, it would overwrite the status
        // back to RUNNING and erase the error, leaving the run stuck forever.
        store.markResumed(id);
        flowEngine.resumeFlow(id, ctx).whenComplete((result, err) ->
                store.completeExecution(id, statusOf(result, err), errorOf(err)));

        return ResponseEntity.ok(store.getExecution(id));
    }

    private static String statusOf(FlowResult result, Throwable err) {
        if (err != null || result == null) {
            return "FAILED";
        }
        return result.getStatus() != null ? result.getStatus().name() : "COMPLETED";
    }

    private static String errorOf(Throwable err) {
        if (err == null) {
            return null;
        }
        Throwable cause = err.getCause() != null ? err.getCause() : err;
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
