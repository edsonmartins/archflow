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
 * <p><b>Tenant (RCHFLW-73).</b> Todo workflow e toda execução pertencem a um tenant, e toda leitura ou
 * alteração vinda de uma requisição recebe o tenant de quem pede — que o chamador resolve no servidor
 * ({@code TenantContext}), nunca do corpo. Antes nenhum método recebia tenant: ao entrar no tenant B,
 * o usuário via, editava, executava e apagava os fluxos e as execuções do tenant A. A regra:
 * <ul>
 *   <li>um workflow ou execução de outro tenant é tratado como <em>inexistente</em> ({@code null},
 *       lista vazia, {@code false}, no-op) — o controller responde 404, sem revelar que o id existe;</li>
 *   <li>o tenant é parâmetro obrigatório, e não filtro opcional: um {@code null} vale o tenant padrão
 *       ({@link br.com.archflow.model.tenant.DefaultTenant#ID}), nunca "todos". Ver todos os tenants
 *       não é operação deste store — o superadmin age sobre outro tenant só por personificação;</li>
 *   <li>o id do workflow continua único no store inteiro (é chave primária). Versões e implantações
 *       pendem do workflow, então herdam o dono dele sem coluna própria;</li>
 *   <li>a execução nasce com o tenant em que foi criada e o grava no próprio registro
 *       ({@code "tenantId"});</li>
 *   <li>as mutações por id de execução ({@link #completeExecution}, {@link #recordStep},
 *       {@link #recordDuration}, {@link #markResumed}) não recebem tenant de propósito: quem as chama
 *       são os callbacks do motor, em threads sem requisição, sobre um id que o próprio servidor criou
 *       depois de uma leitura já filtrada.</li>
 * </ul>
 * Dado anterior ao tenant (linhas sem a coluna, registros sem a chave) pertence ao tenant padrão —
 * é o que a migration {@code V6_12_2} grava e o que as leituras assumem.
 *
 * <p>Semântica comum exigida das implementações:
 * <ul>
 *   <li>leituras de execução retornam cópias/snapshots seguros para serialização
 *       concorrente (nunca a estrutura interna viva);</li>
 *   <li>{@link #executions(String, String, Integer)} ordena most-recent-first por
 *       {@code startedAt} e aplica {@code limit} apenas quando {@code > 0};</li>
 *   <li>mutações de execução ({@link #completeExecution}, {@link #recordStep},
 *       {@link #recordDuration}, {@link #markResumed}) são no-op quando a
 *       execução não existe.</li>
 * </ul>
 */
public interface WorkflowRuntimeStore {

    /** Chave do registro de execução que guarda o tenant dono dela. */
    String TENANT_KEY = "tenantId";

    // ------------------------------------------------------------------ workflows

    /** The tenant's workflow documents. */
    Collection<Map<String, Object>> workflows(String tenantId);

    /** The workflow document, or {@code null} when absent or owned by another tenant. */
    Map<String, Object> getWorkflow(String tenantId, String id);

    /**
     * Creates/replaces the tenant's workflow document; returns it.
     *
     * @throws WorkflowOwnershipException when the id already belongs to another tenant — replacing it
     *         would move another tenant's workflow into this one
     */
    Map<String, Object> putWorkflow(String tenantId, String id, Map<String, Object> workflow);

    /** True only when the workflow exists AND belongs to the tenant. */
    boolean hasWorkflow(String tenantId, String id);

    /** Removes the tenant's workflow and every execution recorded for it; no-op for another tenant's. */
    void deleteWorkflow(String tenantId, String id);

    /** Immutable snapshots, newest version first; empty when the workflow is not the tenant's. */
    List<WorkflowVersionRecord> workflowVersions(String tenantId, String workflowId);

    WorkflowVersionRecord getWorkflowVersion(String tenantId, String workflowId, String versionId);

    /** Creates the next immutable version from the current draft; {@code null} when not the tenant's. */
    WorkflowVersionRecord publishWorkflow(String tenantId, String workflowId, String comment);

    WorkflowDeploymentRecord getDeployment(String tenantId, String workflowId, String environment);

    /** Selects an existing immutable version for the environment; {@code null} when not the tenant's. */
    WorkflowDeploymentRecord deployWorkflow(
            String tenantId, String workflowId, String environment, String versionId);

    // ----------------------------------------------------------------- executions

    /**
     * The tenant's execution records (most-recent first by {@code startedAt}), optionally
     * filtered by {@code workflowId} and capped at {@code limit} (when > 0).
     */
    List<Map<String, Object>> executions(String tenantId, String workflowId, Integer limit);

    /** Snapshot of the execution record, or {@code null} when absent or owned by another tenant. */
    Map<String, Object> getExecution(String tenantId, String id);

    /** Creates a new RUNNING execution record for the tenant's workflow and returns it. */
    Map<String, Object> createExecution(String tenantId, String workflowId, String workflowName);

    /** Creates an execution pinned to the immutable workflow version used. */
    Map<String, Object> createExecution(
            String tenantId, String workflowId, String workflowName, String workflowVersionId);

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

    /**
     * O id de workflow já pertence a outro tenant. Só acontece por corrida ou defeito — os
     * controllers verificam antes e respondem 404 —, por isso é exceção e não retorno: gravar por
     * cima moveria o fluxo de um tenant para outro.
     */
    class WorkflowOwnershipException extends IllegalStateException {
        public WorkflowOwnershipException(String workflowId) {
            super("Workflow " + workflowId + " belongs to another tenant");
        }
    }
}
