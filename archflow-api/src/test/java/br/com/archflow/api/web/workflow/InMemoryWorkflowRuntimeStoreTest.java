package br.com.archflow.api.web.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemoryWorkflowRuntimeStore")
class InMemoryWorkflowRuntimeStoreTest {

    @Test
    @DisplayName("createExecution persists a frontend-compatible running execution")
    void createExecutionPersistsRunningExecution() {
        var store = new InMemoryWorkflowRuntimeStore();

        Map<String, Object> execution = store.createExecution("wf-demo-001", "Customer Support Agent");

        assertThat(execution.get("id")).isNotNull();
        assertThat(execution.get("workflowId")).isEqualTo("wf-demo-001");
        assertThat(execution.get("workflowName")).isEqualTo("Customer Support Agent");
        assertThat(execution.get("status")).isEqualTo("RUNNING");
        assertThat(execution.get("startedAt")).isNotNull();
        assertThat(execution.get("completedAt")).isNull();
        assertThat(execution.get("duration")).isNull();
        assertThat(store.getExecution(execution.get("id").toString())).isEqualTo(execution);
    }

    @Test
    @DisplayName("deleteWorkflow also removes related executions")
    void deleteWorkflowRemovesRelatedExecutions() {
        var store = new InMemoryWorkflowRuntimeStore();
        Map<String, Object> execution = store.createExecution("wf-demo-001", "Customer Support Agent");

        store.deleteWorkflow("wf-demo-001");

        assertThat(store.getWorkflow("wf-demo-001")).isNull();
        assertThat(store.getExecution(execution.get("id").toString())).isNull();
        assertThat(store.executions()).noneMatch(item -> "wf-demo-001".equals(item.get("workflowId")));
    }

    @Test
    @DisplayName("markResumed flips a terminal record back to RUNNING and clears error/completedAt")
    void markResumedResetsToRunning() {
        var store = new InMemoryWorkflowRuntimeStore();
        String id = store.createExecution("wf-r", "R").get("id").toString();
        store.completeExecution(id, "FAILED", "boom");
        assertThat(store.getExecution(id).get("status")).isEqualTo("FAILED");

        store.markResumed(id);

        var resumed = store.getExecution(id);
        assertThat(resumed.get("status")).isEqualTo("RUNNING");
        assertThat(resumed.get("error")).isNull();
        assertThat(resumed.get("completedAt")).isNull();
    }

    @Test
    @DisplayName("recordStep after a non-terminal PAUSED keeps the step index (updates in place)")
    void recordStepAfterPauseUpdatesInPlace() {
        var store = new InMemoryWorkflowRuntimeStore();
        String id = store.createExecution("wf-p", "P").get("id").toString();
        store.recordStep(id, "step-1", Map.of("status", "RUNNING"));
        // PAUSED is not terminal: the index must survive so a resumed run patches
        // the same step record instead of appending a duplicate.
        store.completeExecution(id, "PAUSED", null);
        store.recordStep(id, "step-1", Map.of("status", "COMPLETED"));

        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) store.getExecution(id).get("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("status", "COMPLETED");
    }

    @Test
    @DisplayName("recordStep after a terminal status rebuilds the index and updates in place (no duplicate)")
    void recordStepAfterTerminalUpdatesInPlace() {
        var store = new InMemoryWorkflowRuntimeStore();
        String id = store.createExecution("wf-t", "T").get("id").toString();
        store.recordStep(id, "step-1", Map.of("status", "RUNNING"));
        // COMPLETED is terminal → the step index is evicted. A late event for the
        // same step must still patch the existing row (index rebuilt from steps),
        // not append a duplicate.
        store.completeExecution(id, "COMPLETED", null);
        store.recordStep(id, "step-1", Map.of("status", "SKIPPED"));

        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) store.getExecution(id).get("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("status", "SKIPPED");
    }

    @Test
    @DisplayName("executions(workflowId, limit) filters by workflow and caps the result")
    void executionsFilterAndLimit() {
        var store = new InMemoryWorkflowRuntimeStore();
        store.createExecution("wf-a", "A");
        store.createExecution("wf-a", "A");
        store.createExecution("wf-a", "A");
        store.createExecution("wf-b", "B");

        assertThat(store.executions("wf-a", null)).hasSize(3)
                .allMatch(e -> "wf-a".equals(e.get("workflowId")));
        assertThat(store.executions("wf-a", 2)).hasSize(2);
        assertThat(store.executions("wf-b", null)).hasSize(1);
        // limit <= 0 is ignored (returns all matches).
        assertThat(store.executions("wf-a", 0)).hasSize(3);
    }
}
