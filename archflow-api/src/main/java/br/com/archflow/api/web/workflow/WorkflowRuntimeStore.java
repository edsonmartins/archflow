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

    /** Status de execução que ainda dependem do fluxo: em andamento ou parada à espera (aprovação). */
    Set<String> ACTIVE_EXECUTION_STATUSES = Set.of("RUNNING", "PAUSED");

    /**
     * O desfecho de {@link #deleteWorkflow}.
     *
     * @param outcome          o que aconteceu
     * @param activeExecutions os ids das execuções em andamento ou pausadas que impediram a exclusão; vazia nos
     *                         outros desfechos
     */
    record Deletion(Outcome outcome, List<String> activeExecutions) {

        public enum Outcome { DELETED, NOT_FOUND, HAS_ACTIVE_EXECUTIONS }

        public Deletion {
            activeExecutions = activeExecutions == null ? List.of() : List.copyOf(activeExecutions);
        }

        public static Deletion deleted() {
            return new Deletion(Outcome.DELETED, List.of());
        }

        public static Deletion notFound() {
            return new Deletion(Outcome.NOT_FOUND, List.of());
        }

        public static Deletion blockedBy(List<String> activeExecutions) {
            return new Deletion(Outcome.HAS_ACTIVE_EXECUTIONS, activeExecutions);
        }
    }

    /**
     * Removes the tenant's workflow with its versions and deployments — unless an execution of it is RUNNING or
     * PAUSED ({@link #ACTIVE_EXECUTION_STATUSES}); {@link Deletion.Outcome#NOT_FOUND} for another tenant's.
     *
     * <p>As execuções do fluxo <b>ficam</b> (RCHFLW-122): a lista, o detalhe e as decisões de aprovação
     * continuam consultáveis, com o nome que o registro guardou ({@code workflowName}). Apagá-las levava
     * junto a resposta a "quem autorizou isto". O fluxo em si some — da lista e do {@link #getWorkflow} —,
     * sem exclusão lógica.</p>
     *
     * <p>A recusa por execução ativa é decidida aqui, junto com a exclusão, e não por uma consulta antes dela:
     * a execução criada entre as duas escaparia da checagem. Cada implementação serializa a checagem e a
     * exclusão com o {@link #createExecution} do mesmo fluxo — o in-memory sob o mesmo monitor, o JDBC sob a
     * trava da linha do documento, na mesma transação. Uma execução cujo {@code createExecution} vem depois da
     * exclusão continua possível (quem executa leu o documento antes) e roda pelo snapshot do
     * {@code FlowRepository}.</p>
     */
    Deletion deleteWorkflow(String tenantId, String id);

    /** Os ids das execuções em andamento ou pausadas entre {@code executions}, na ordem dada. */
    static List<String> activeExecutionIds(Collection<Map<String, Object>> executions) {
        return executions.stream()
                .filter(e -> ACTIVE_EXECUTION_STATUSES.contains(String.valueOf(e.get("status"))))
                .map(e -> String.valueOf(e.get("id")))
                .toList();
    }

    /** Immutable snapshots, newest version first; empty when the workflow is not the tenant's. */
    List<WorkflowVersionRecord> workflowVersions(String tenantId, String workflowId);

    WorkflowVersionRecord getWorkflowVersion(String tenantId, String workflowId, String versionId);

    /** Creates the next immutable version from the current draft; {@code null} when not the tenant's. */
    WorkflowVersionRecord publishWorkflow(String tenantId, String workflowId, String comment);

    WorkflowDeploymentRecord getDeployment(String tenantId, String workflowId, String environment);

    /** Selects an existing immutable version for the environment; {@code null} when not the tenant's. */
    WorkflowDeploymentRecord deployWorkflow(
            String tenantId, String workflowId, String environment, String versionId);

    /**
     * O número da versão implantada em PRODUCTION de cada fluxo do tenant que tem uma — a que o {@code /execute}
     * roda (RCHFLW-132). Fluxo sem implantação, ou com implantação que aponta para versão inexistente, fica fora
     * do mapa: roda o rascunho.
     *
     * <p>Existe para a lista de fluxos não pagar uma implantação + uma versão (com o documento inteiro) por
     * fluxo a cada carga da lista e do Dashboard. Esta implementação faz exatamente isso e serve para o store
     * em memória, onde cada chamada é um acesso a mapa; o JDBC sobrescreve com uma consulta só.</p>
     */
    default Map<String, Integer> productionVersionNumbers(String tenantId) {
        Map<String, Integer> numbers = new java.util.HashMap<>();
        for (Map<String, Object> wf : workflows(tenantId)) {
            Object id = wf.get("id");
            if (id == null) {
                continue;
            }
            WorkflowDeploymentRecord deployment = getDeployment(tenantId, id.toString(), "PRODUCTION");
            WorkflowVersionRecord version = deployment == null
                    ? null
                    : getWorkflowVersion(tenantId, id.toString(), deployment.versionId());
            if (version != null) {
                numbers.put(id.toString(), version.number());
            }
        }
        return numbers;
    }

    // ----------------------------------------------------------------- executions

    /**
     * The tenant's execution records (most-recent first by {@code startedAt}), optionally
     * filtered by {@code workflowId} and capped at {@code limit} (when > 0).
     */
    List<Map<String, Object>> executions(String tenantId, String workflowId, Integer limit);

    /** Snapshot of the execution record, or {@code null} when absent or owned by another tenant. */
    Map<String, Object> getExecution(String tenantId, String id);

    /**
     * Creates a new RUNNING execution record for the tenant's workflow and returns it. Sem versão: a
     * execução roda o rascunho (o documento salvo) — é o que fazem o AG-UI do editor e o MCP server, que
     * leem o fluxo por {@link #getWorkflow}. Equivale a {@code createExecution(..., null)}.
     */
    Map<String, Object> createExecution(String tenantId, String workflowId, String workflowName);

    /**
     * Creates an execution pinned to the immutable workflow version used — {@code null} quando o que
     * rodou foi o rascunho. Ver {@link #recordRanVersion}.
     */
    Map<String, Object> createExecution(
            String tenantId, String workflowId, String workflowName, WorkflowVersionRecord version);

    /**
     * Grava no registro da execução o que rodou: {@code workflowDraft} (sempre presente) e, para uma
     * versão publicada, {@code workflowVersionId} e {@code workflowVersionNumber}.
     *
     * <p>O detalhe da execução precisa dizer qual versão rodou (RCHFLW-89): o editor roda o rascunho e a
     * lista/API roda a versão em produção, e o mesmo fluxo dava resultados diferentes sem nenhuma tela
     * dizer por quê. A marca do rascunho é explícita porque um registro antigo, sem informação nenhuma,
     * seria indistinguível de uma execução do rascunho — e o número fica no registro para a tela não
     * precisar adivinhá-lo pela lista de versões.</p>
     */
    static void recordRanVersion(Map<String, Object> execution, WorkflowVersionRecord version) {
        execution.put("workflowDraft", version == null);
        execution.put("workflowVersionId", version == null ? null : version.id());
        if (version != null) {
            execution.put("workflowVersionNumber", version.number());
        }
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
     * O erro a gravar no fim da execução (RCHFLW-130): o que o chamador passou, em frase legível, ou — numa
     * FAILED sem erro — o dos passos que falharam.
     *
     * <p>Quando um passo falha, o motor não lança: devolve um {@code FlowResult} FAILED, e os chamadores
     * ({@code /execute}, AG-UI, retomada, aprovação) só passam o erro de uma exceção. A execução ficava
     * "Falhou" com a coluna Erro em branco, e o motivo só aparecia abrindo o passo no detalhe. O passo já
     * gravou o erro dele ({@code StepRecordingListener}) antes de o fluxo terminar; ler daqui vale para todo
     * chamador, inclusive um novo, e é a mesma regra do invoke do VendaX ({@code AgentFlowRunner.errosDe}): as
     * mensagens unidas por "; ". A frase do provedor de LLM sai sem o corpo HTTP cru
     * ({@link br.com.archflow.api.flow.ErroDoProvedorLegivel}).</p>
     *
     * @param execution o registro, lido sob o lock da escrita
     * @param status    o status que vai ser gravado
     * @param error     o erro que o chamador passou (pode ser {@code null})
     */
    static String failureSummary(Map<String, Object> execution, String status, String error) {
        if (error != null && !error.isBlank()) {
            return br.com.archflow.api.flow.ErroDoProvedorLegivel.paraUsuario(error);
        }
        if (!"FAILED".equals(status) || !(execution.get("steps") instanceof List<?> steps)) {
            return error;
        }
        String fromSteps = steps.stream()
                .filter(step -> step instanceof Map<?, ?> m && "FAILED".equals(String.valueOf(m.get("status"))))
                .map(step -> ((Map<?, ?>) step).get("error"))
                .filter(e -> e != null && !e.toString().isBlank())
                .map(e -> br.com.archflow.api.flow.ErroDoProvedorLegivel.paraUsuario(e.toString()))
                .distinct()
                .collect(java.util.stream.Collectors.joining("; "));
        return fromSteps.isEmpty() ? error : fromSteps;
    }

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

    /**
     * Records the wall-clock duration of an execution (set by the lifecycle listener), as
     * {@link #executionDuration}: o tempo desde o {@code startedAt} do registro, não só o da última
     * travessia do motor.
     */
    void recordDuration(String id, long durationMs);

    /**
     * A duração a gravar quando o motor termina: do {@code startedAt} da execução até agora. O
     * {@code durationMs} do motor mede só a travessia que terminou; numa execução que pausou numa
     * aprovação e foi retomada, era o trecho depois da retomada — o detalhe mostrava 1,6 s para uma
     * execução de 28 s (RCHFLW-99). Sem {@code startedAt} legível no registro, vale o do motor, que é o
     * que havia antes.
     *
     * @param execution   o registro da execução
     * @param traversalMs a duração que o motor mediu
     * @param now         o instante do fim
     */
    static long executionDuration(Map<String, Object> execution, long traversalMs, java.time.Instant now) {
        Object startedAt = execution.get("startedAt");
        if (startedAt == null) {
            return traversalMs;
        }
        try {
            long total = java.time.Duration.between(java.time.Instant.parse(startedAt.toString()), now).toMillis();
            return Math.max(total, traversalMs);
        } catch (java.time.format.DateTimeParseException e) {
            return traversalMs;
        }
    }

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
     * O id de workflow já pertence a outro tenant. Só acontece por corrida ou defeito — os
     * controllers verificam antes e respondem 404 —, por isso é exceção e não retorno: gravar por
     * cima moveria o fluxo de um tenant para outro.
     */
    class WorkflowOwnershipException extends IllegalStateException {
        public WorkflowOwnershipException(String workflowId) {
            super("Workflow " + workflowId + " belongs to another tenant");
        }
    }

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
