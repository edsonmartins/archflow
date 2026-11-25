package br.com.archflow.api.web.workflow;

import br.com.archflow.model.tenant.DefaultTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InMemoryWorkflowRuntimeStore")
class InMemoryWorkflowRuntimeStoreTest {

    private static final String T = DefaultTenant.ID;

    @Test
    @DisplayName("publish creates immutable sequential snapshots")
    void publishCreatesImmutableSequentialSnapshots() {
        var store = new InMemoryWorkflowRuntimeStore();
        var draft = new java.util.LinkedHashMap<String, Object>();
        draft.put("id", "wf-versioned");
        draft.put("metadata", new java.util.LinkedHashMap<>(
                java.util.Map.of("name", "Original")));
        draft.put("steps", java.util.List.of());
        store.putWorkflow(T, "wf-versioned", draft);

        var first = store.publishWorkflow(T, "wf-versioned", "initial");
        @SuppressWarnings("unchecked")
        var metadata = (java.util.Map<String, Object>) draft.get("metadata");
        metadata.put("name", "Changed");
        var second = store.publishWorkflow(T, "wf-versioned", "changed");

        assertThat(first.number()).isEqualTo(1);
        assertThat(second.number()).isEqualTo(2);
        assertThat(store.workflowVersions(T, "wf-versioned"))
                .extracting(WorkflowVersionRecord::id)
                .containsExactly(second.id(), first.id());
        @SuppressWarnings("unchecked")
        var firstMetadata =
                (java.util.Map<String, Object>) first.document().get("metadata");
        assertThat(firstMetadata.get("name")).isEqualTo("Original");
    }

    @Test
    @DisplayName("deployment can move back to an older version")
    void deploymentCanRollbackToOlderVersion() {
        var store = new InMemoryWorkflowRuntimeStore();
        store.putWorkflow(T, "wf-versioned", new java.util.LinkedHashMap<>(
                java.util.Map.of("id", "wf-versioned", "steps", java.util.List.of())));
        var first = store.publishWorkflow(T, "wf-versioned", "v1");
        var second = store.publishWorkflow(T, "wf-versioned", "v2");

        store.deployWorkflow(T, "wf-versioned", "production", second.id());
        var rollback = store.deployWorkflow(T, "wf-versioned", "production", first.id());

        assertThat(rollback.versionId()).isEqualTo(first.id());
        assertThat(store.getDeployment(T, "wf-versioned", "PRODUCTION").versionId())
                .isEqualTo(first.id());
    }

    @Test
    @DisplayName("execution records the pinned workflow version")
    void executionRecordsPinnedVersion() {
        var store = new InMemoryWorkflowRuntimeStore();

        var execution = store.createExecution(T, "wf-1", "Flow", "wfv-1");

        assertThat(execution.get("workflowVersionId")).isEqualTo("wfv-1");
        assertThat(store.getExecution(T, String.valueOf(execution.get("id")))
                .get("workflowVersionId")).isEqualTo("wfv-1");
    }

    @Test
    @DisplayName("createExecution persists a frontend-compatible running execution")
    void createExecutionPersistsRunningExecution() {
        var store = new InMemoryWorkflowRuntimeStore();

        Map<String, Object> execution = store.createExecution(T, "wf-demo-001", "Customer Support Agent");

        assertThat(execution.get("id")).isNotNull();
        assertThat(execution.get("workflowId")).isEqualTo("wf-demo-001");
        assertThat(execution.get("workflowName")).isEqualTo("Customer Support Agent");
        assertThat(execution.get("status")).isEqualTo("RUNNING");
        assertThat(execution.get("startedAt")).isNotNull();
        assertThat(execution.get("completedAt")).isNull();
        assertThat(execution.get("duration")).isNull();
        assertThat(store.getExecution(T, execution.get("id").toString())).isEqualTo(execution);
    }

    @Test
    @DisplayName("deleteWorkflow also removes related executions")
    void deleteWorkflowRemovesRelatedExecutions() {
        var store = new InMemoryWorkflowRuntimeStore();
        Map<String, Object> execution = store.createExecution(T, "wf-demo-001", "Customer Support Agent");

        store.deleteWorkflow(T, "wf-demo-001");

        assertThat(store.getWorkflow(T, "wf-demo-001")).isNull();
        assertThat(store.getExecution(T, execution.get("id").toString())).isNull();
        assertThat(store.executions(T, null, null)).noneMatch(item -> "wf-demo-001".equals(item.get("workflowId")));
    }

    @Test
    @DisplayName("markResumed flips a terminal record back to RUNNING and clears error/completedAt")
    void markResumedResetsToRunning() {
        var store = new InMemoryWorkflowRuntimeStore();
        String id = store.createExecution(T, "wf-r", "R").get("id").toString();
        store.completeExecution(id, "FAILED", "boom");
        assertThat(store.getExecution(T, id).get("status")).isEqualTo("FAILED");

        store.markResumed(id);

        var resumed = store.getExecution(T, id);
        assertThat(resumed.get("status")).isEqualTo("RUNNING");
        assertThat(resumed.get("error")).isNull();
        assertThat(resumed.get("completedAt")).isNull();
    }

    @Test
    @DisplayName("recordStep after a non-terminal PAUSED keeps the step index (updates in place)")
    void recordStepAfterPauseUpdatesInPlace() {
        var store = new InMemoryWorkflowRuntimeStore();
        String id = store.createExecution(T, "wf-p", "P").get("id").toString();
        store.recordStep(id, "step-1", Map.of("status", "RUNNING"));
        // PAUSED is not terminal: the index must survive so a resumed run patches
        // the same step record instead of appending a duplicate.
        store.completeExecution(id, "PAUSED", null);
        store.recordStep(id, "step-1", Map.of("status", "COMPLETED"));

        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) store.getExecution(T, id).get("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("status", "COMPLETED");
    }

    @Test
    @DisplayName("recordStep after a terminal status rebuilds the index and updates in place (no duplicate)")
    void recordStepAfterTerminalUpdatesInPlace() {
        var store = new InMemoryWorkflowRuntimeStore();
        String id = store.createExecution(T, "wf-t", "T").get("id").toString();
        store.recordStep(id, "step-1", Map.of("status", "RUNNING"));
        // COMPLETED is terminal → the step index is evicted. A late event for the
        // same step must still patch the existing row (index rebuilt from steps),
        // not append a duplicate.
        store.completeExecution(id, "COMPLETED", null);
        store.recordStep(id, "step-1", Map.of("status", "SKIPPED"));

        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) store.getExecution(T, id).get("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("status", "SKIPPED");
    }

    @Test
    @DisplayName("executions(workflowId, limit) filters by workflow and caps the result")
    void executionsFilterAndLimit() {
        var store = new InMemoryWorkflowRuntimeStore();
        store.createExecution(T, "wf-a", "A");
        store.createExecution(T, "wf-a", "A");
        store.createExecution(T, "wf-a", "A");
        store.createExecution(T, "wf-b", "B");

        assertThat(store.executions(T, "wf-a", null)).hasSize(3)
                .allMatch(e -> "wf-a".equals(e.get("workflowId")));
        assertThat(store.executions(T, "wf-a", 2)).hasSize(2);
        assertThat(store.executions(T, "wf-b", null)).hasSize(1);
        // limit <= 0 is ignored (returns all matches).
        assertThat(store.executions(T, "wf-a", 0)).hasSize(3);
    }

    // ── tenant (RCHFLW-73) ──────────────────────────────────────────────────

    @Test
    @DisplayName("workflow de outro tenant é inexistente para leitura, versões, implantação e delete")
    void workflowDeOutroTenantEhInexistente() {
        var store = new InMemoryWorkflowRuntimeStore();
        store.putWorkflow("acme", "wf-acme", new java.util.LinkedHashMap<>(Map.of("id", "wf-acme")));
        var version = store.publishWorkflow("acme", "wf-acme", "v1");
        store.deployWorkflow("acme", "wf-acme", "PRODUCTION", version.id());

        assertThat(store.workflows("globex")).isEmpty();
        assertThat(store.getWorkflow("globex", "wf-acme")).isNull();
        assertThat(store.hasWorkflow("globex", "wf-acme")).isFalse();
        assertThat(store.workflowVersions("globex", "wf-acme")).isEmpty();
        assertThat(store.getWorkflowVersion("globex", "wf-acme", version.id())).isNull();
        assertThat(store.publishWorkflow("globex", "wf-acme", "v2")).isNull();
        assertThat(store.getDeployment("globex", "wf-acme", "PRODUCTION")).isNull();
        assertThat(store.deployWorkflow("globex", "wf-acme", "PRODUCTION", version.id())).isNull();
        store.deleteWorkflow("globex", "wf-acme");

        assertThat(store.workflows("acme")).extracting(wf -> wf.get("id")).containsExactly("wf-acme");
        assertThat(store.workflowVersions("acme", "wf-acme")).hasSize(1);
        assertThat(store.getDeployment("acme", "wf-acme", "PRODUCTION").versionId()).isEqualTo(version.id());
    }

    @Test
    @DisplayName("gravar um id de outro tenant falha, em vez de mover o fluxo de tenant")
    void putNaoMoveOFluxoDeTenant() {
        var store = new InMemoryWorkflowRuntimeStore();
        store.putWorkflow("acme", "wf-acme", new java.util.LinkedHashMap<>(Map.of("v", 1)));

        assertThatThrownBy(() -> store.putWorkflow("globex", "wf-acme", Map.of("v", 2)))
                .isInstanceOf(WorkflowRuntimeStore.WorkflowOwnershipException.class);
        assertThat(store.getWorkflow("acme", "wf-acme")).containsEntry("v", 1);
    }

    @Test
    @DisplayName("execução grava o tenant e só é lida, listada por ele")
    void execucaoPertenceAoTenant() {
        var store = new InMemoryWorkflowRuntimeStore();
        String id = store.createExecution("acme", "wf-acme", "A", "wfv-1").get("id").toString();

        assertThat(store.getExecution("acme", id)).containsEntry("tenantId", "acme")
                .containsEntry("workflowVersionId", "wfv-1");
        assertThat(store.getExecution("globex", id)).isNull();
        assertThat(store.executions("globex", null, null)).extracting(e -> e.get("id")).doesNotContain(id);
        assertThat(store.executions("globex", "wf-acme", null)).isEmpty();
        assertThat(store.executions("acme", null, null)).extracting(e -> e.get("id")).containsExactly(id);
    }

    @Test
    @DisplayName("tenant nulo vale o tenant padrão — onde estão o fluxo e a execução de demonstração —, nunca todos")
    void tenantNuloEhOPadrao() {
        var store = new InMemoryWorkflowRuntimeStore();
        store.putWorkflow("acme", "wf-acme", new java.util.LinkedHashMap<>(Map.of("id", "wf-acme")));

        assertThat(store.workflows(null)).extracting(wf -> wf.get("id")).containsExactly("wf-demo-001");
        assertThat(store.getWorkflow(null, "wf-demo-001")).isNotNull();
        assertThat(store.getWorkflow(null, "wf-acme")).isNull();
        assertThat(store.getExecution(null, "exec-demo-001")).containsEntry("tenantId", DefaultTenant.ID);
        assertThat(store.workflows("acme")).extracting(wf -> wf.get("id")).containsExactly("wf-acme");
    }
}
