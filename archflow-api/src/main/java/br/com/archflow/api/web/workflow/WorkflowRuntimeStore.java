package br.com.archflow.api.web.workflow;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Runtime store dos workflows do designer: documentos de workflow (JSON-like
 * {@code Map<String,Object>}) e registros de execução com seus steps.
 *
 * <p>Contrato compartilhado pelos controllers ({@code SpringWorkflowCrudController},
 * {@code SpringExecutionController}, YAML, AG-UI, MCP server) e pelos listeners de
 * lifecycle do engine ({@code StepRecordingListener}). Implementações:
 * {@link InMemoryWorkflowRuntimeStore} (dev/test) e {@link JdbcWorkflowRuntimeStore}
 * (durável, sob {@code archflow.persistence.jdbc.enabled=true}).
 *
 * <p>Semântica comum exigida das implementações:
 * <ul>
 *   <li>leituras de execução retornam cópias/snapshots seguros para serialização
 *       concorrente (nunca a estrutura interna viva);</li>
 *   <li>{@link #executions(String, Integer)} ordena most-recent-first por
 *       {@code startedAt} e aplica {@code limit} apenas quando {@code > 0};</li>
 *   <li>mutações de execução ({@link #completeExecution}, {@link #recordStep},
 *       {@link #recordDuration}, {@link #markResumed}) são no-op quando a
 *       execução não existe.</li>
 * </ul>
 */
public interface WorkflowRuntimeStore {

    // ------------------------------------------------------------------ workflows

    /** All stored workflow documents. */
    Collection<Map<String, Object>> workflows();

    /** The workflow document, or {@code null} when absent. */
    Map<String, Object> getWorkflow(String id);

    /** Creates/replaces the workflow document; returns it. */
    Map<String, Object> putWorkflow(String id, Map<String, Object> workflow);

    boolean hasWorkflow(String id);

    /** Removes the workflow and every execution recorded for it. */
    void deleteWorkflow(String id);

    // ----------------------------------------------------------------- executions

    /** All execution records, most-recent first. */
    default List<Map<String, Object>> executions() {
        return executions(null, null);
    }

    /**
     * Execution records (most-recent first by {@code startedAt}), optionally
     * filtered by {@code workflowId} and capped at {@code limit} (when > 0).
     */
    List<Map<String, Object>> executions(String workflowId, Integer limit);

    /** Snapshot of the execution record, or {@code null} when absent. */
    Map<String, Object> getExecution(String id);

    /** Creates/replaces an execution record; returns it. */
    Map<String, Object> putExecution(String id, Map<String, Object> execution);

    /** Creates a new RUNNING execution record for the workflow and returns it. */
    Map<String, Object> createExecution(String workflowId, String workflowName);

    /** Marks a resumed execution RUNNING again (counterpart of completeExecution). */
    void markResumed(String id);

    /** Marks a running execution terminal (status + completedAt + optional error). */
    void completeExecution(String id, String status, String error);

    /** Records the wall-clock duration of an execution (set by the lifecycle listener). */
    void recordDuration(String id, long durationMs);

    /**
     * Appends/updates a per-step record inside the execution's {@code steps}
     * list, keyed by {@code stepId}. No-op when the execution is unknown or
     * {@code stepId} is {@code null}.
     */
    void recordStep(String executionId, String stepId, Map<String, Object> patch);
}
