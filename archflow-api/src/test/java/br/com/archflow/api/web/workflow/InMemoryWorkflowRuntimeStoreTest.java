package br.com.archflow.api.web.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
