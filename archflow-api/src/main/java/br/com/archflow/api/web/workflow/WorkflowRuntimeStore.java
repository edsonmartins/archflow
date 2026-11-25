package br.com.archflow.api.web.workflow;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Immutable snapshots, newest version first. */
    List<WorkflowVersionRecord> workflowVersions(String workflowId);

    WorkflowVersionRecord getWorkflowVersion(String workflowId, String versionId);

    /** Creates the next immutable version from the current draft. */
    WorkflowVersionRecord publishWorkflow(String workflowId, String comment);

    WorkflowDeploymentRecord getDeployment(String workflowId, String environment);

    /** Selects an existing immutable version for the environment. */
    WorkflowDeploymentRecord deployWorkflow(
            String workflowId, String environment, String versionId);

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

    /** Creates an execution pinned to the immutable workflow version used. */
    default Map<String, Object> createExecution(
            String workflowId, String workflowName, String workflowVersionId) {
        Map<String, Object> execution = createExecution(workflowId, workflowName);
        execution.put("workflowVersionId", workflowVersionId);
        putExecution(String.valueOf(execution.get("id")), execution);
        return execution;
    }

    /** Marks a resumed execution RUNNING again (counterpart of completeExecution). */
    void markResumed(String id);

    /**
     * Marks a running execution terminal (status + completedAt + optional error).
     *
     * <p>Ignorada — status, {@code completedAt} e erro ficam como estão — quando
     * {@link #isStaleStatus} diz que ela apagaria um desfecho já gravado.</p>
     */
    void completeExecution(String id, String status, String error);

    /**
     * {@code true} quando gravar {@code incoming} sobre {@code current} trocaria um desfecho
     * conhecido (COMPLETED, FAILED, STOPPED) por um status velho (PAUSED, CANCELLED).
     *
     * <p>Duas mãos escrevem o fim de uma execução com aprovação: quem a disparou (/execute,
     * AG-UI, MCP) grava o status em que a travessia original desenrolou, e a fila de aprovações
     * grava o desfecho da decisão (RCHFLW-40). Quando a decisão chega antes de a travessia
     * original terminar de desenrolar — um aprovador automático decide em milissegundos —, o
     * callback de quem disparou chega por último com PAUSED (o portão pausou) ou CANCELLED (a
     * recusa parou a travessia viva) e apagava o STOPPED com o motivo que a fila tinha gravado:
     * o registro divergia para sempre do {@code FlowState}. Na outra ordem não há o que barrar:
     * o desfecho chega depois e vence.</p>
     *
     * <p>A regra é restrita a propósito. Nenhum caminho legítimo grava PAUSED sobre um desfecho:
     * toda retomada passa antes por {@link #markResumed} (fila e {@code /resume}), que volta o
     * registro a RUNNING. E CANCELLED sobre um desfecho é o cancelar que perdeu a corrida para o
     * fim da execução — o motor já não tinha o que cancelar, e o registro passaria a dizer algo
     * que o estado não diz. COMPLETED, FAILED ou STOPPED sobre outro desfecho seguem gravados
     * como antes.</p>
     */
    static boolean isStaleStatus(Object current, String incoming) {
        return current != null
                && Set.of("COMPLETED", "FAILED", "STOPPED").contains(String.valueOf(current))
                && Set.of("PAUSED", "CANCELLED").contains(incoming);
    }

    /** Records the wall-clock duration of an execution (set by the lifecycle listener). */
    void recordDuration(String id, long durationMs);

    /**
     * Appends/updates a per-step record inside the execution's {@code steps}
     * list, keyed by {@code stepId}. No-op when the execution is unknown or
     * {@code stepId} is {@code null}.
     *
     * <p>Ignorado por inteiro — o passo fica como está — quando {@link #isStaleStepStatus} diz que
     * o patch apagaria o desfecho já gravado do passo.</p>
     */
    void recordStep(String executionId, String stepId, Map<String, Object> patch);

    /**
     * {@code true} quando gravar o status {@code incoming} sobre o {@code current} de um passo
     * trocaria um desfecho dele (COMPLETED, FAILED, SKIPPED, REJECTED) por PAUSED.
     *
     * <p>É a mesma corrida de {@link #isStaleStatus}, no nível do passo. O portão de aprovação grava
     * PAUSED depois que o step devolve, mas o pedido já está na fila desde o
     * {@code requestApproval}: um aprovador automático que decide nesse intervalo faz a fila gravar
     * REJECTED com quem recusou (ou a retomada gravar RUNNING/COMPLETED) antes, e o PAUSED velho
     * chegava por último — a execução STOPPED e o portão "aguardando aprovação" para sempre, sem
     * o decisor.</p>
     *
     * <p>Nenhum caminho legítimo grava PAUSED sobre um desfecho: toda execução de um passo começa
     * pelo {@code onStepStarted}, que grava RUNNING — inclusive o mesmo portão de novo num laço.
     * Qualquer outro status segue gravado como antes.</p>
     */
    static boolean isStaleStepStatus(Object current, Object incoming) {
        return current != null
                && "PAUSED".equals(incoming)
                && Set.of("COMPLETED", "FAILED", "SKIPPED", "REJECTED").contains(String.valueOf(current));
    }
}
