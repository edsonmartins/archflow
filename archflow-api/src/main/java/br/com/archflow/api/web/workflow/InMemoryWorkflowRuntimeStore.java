package br.com.archflow.api.web.workflow;

import br.com.archflow.model.tenant.DefaultTenant;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory {@link WorkflowRuntimeStore} (dev/test): tudo se perde no
 * restart. Registrado por {@code ArchflowBeanConfiguration} apenas quando
 * {@code archflow.persistence.jdbc.enabled} está desligado — em prod o
 * {@link JdbcWorkflowRuntimeStore} durável assume.
 *
 * <p>Cada documento é guardado junto com o tenant dono ({@link StoredWorkflow}) em vez de ganhar uma
 * chave {@code tenantId} dentro do JSON: o documento é o que a UI edita e devolve no PUT, então um
 * campo ali seria um tenant escolhido pelo cliente. O registro de execução, que a UI só lê, leva a
 * chave {@link #TENANT_KEY}.
 */
public class InMemoryWorkflowRuntimeStore implements WorkflowRuntimeStore {

    /** Documento + tenant dono, trocados juntos (atômico no {@code compute} do mapa). */
    private record StoredWorkflow(String tenantId, Map<String, Object> document) {
    }

    private final Map<String, StoredWorkflow> workflows = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> executions = new ConcurrentHashMap<>();
    private final Map<String, List<WorkflowVersionRecord>> versions = new ConcurrentHashMap<>();
    private final Map<String, WorkflowDeploymentRecord> deployments = new ConcurrentHashMap<>();
    // Per-execution stepId → step-record index so lifecycle events patch in
    // O(1) instead of rescanning the growing steps list on every event.
    private final Map<String, Map<String, Map<String, Object>>> stepIndexes = new ConcurrentHashMap<>();

    public InMemoryWorkflowRuntimeStore() {
        seedDemoWorkflow();
    }

    public Collection<Map<String, Object>> workflows(String tenantId) {
        String tenant = DefaultTenant.orDefault(tenantId);
        return workflows.values().stream()
                .filter(stored -> stored.tenantId().equals(tenant))
                .map(StoredWorkflow::document)
                .toList();
    }

    public Map<String, Object> getWorkflow(String tenantId, String id) {
        StoredWorkflow stored = owned(tenantId, id);
        return stored == null ? null : stored.document();
    }

    public Map<String, Object> putWorkflow(String tenantId, String id, Map<String, Object> workflow) {
        String tenant = DefaultTenant.orDefault(tenantId);
        workflows.compute(id, (key, current) -> {
            if (current != null && !current.tenantId().equals(tenant)) {
                throw new WorkflowOwnershipException(id);
            }
            return new StoredWorkflow(tenant, workflow);
        });
        return workflow;
    }

    public boolean hasWorkflow(String tenantId, String id) {
        return owned(tenantId, id) != null;
    }

    /**
     * Sob o monitor do store, o mesmo do {@link #createExecution}: a checagem das execuções ativas e a exclusão
     * não deixam uma execução nova entrar entre as duas.
     */
    public synchronized Deletion deleteWorkflow(String tenantId, String id) {
        if (owned(tenantId, id) == null) {
            return Deletion.notFound();
        }
        List<String> ativas = WorkflowRuntimeStore.activeExecutionIds(executions(tenantId, id, null));
        if (!ativas.isEmpty()) {
            return Deletion.blockedBy(ativas);
        }
        workflows.remove(id);
        versions.remove(id);
        deployments.keySet().removeIf(key -> key.startsWith(id + ":"));
        // As execuções ficam (RCHFLW-122), como no JDBC: o histórico e as decisões continuam consultáveis.
        return Deletion.deleted();
    }

    /** O workflow quando existe e é do tenant; {@code null} nos dois outros casos, indistintos. */
    private StoredWorkflow owned(String tenantId, String id) {
        StoredWorkflow stored = id == null ? null : workflows.get(id);
        return stored != null && stored.tenantId().equals(DefaultTenant.orDefault(tenantId)) ? stored : null;
    }

    public List<WorkflowVersionRecord> workflowVersions(String tenantId, String workflowId) {
        if (owned(tenantId, workflowId) == null) {
            return List.of();
        }
        return versions.getOrDefault(workflowId, List.of()).stream()
                .sorted(Comparator.comparingInt(WorkflowVersionRecord::number).reversed())
                .toList();
    }

    public WorkflowVersionRecord getWorkflowVersion(String tenantId, String workflowId, String versionId) {
        if (owned(tenantId, workflowId) == null) {
            return null;
        }
        return versions.getOrDefault(workflowId, List.of()).stream()
                .filter(version -> version.id().equals(versionId))
                .findFirst().orElse(null);
    }

    public synchronized WorkflowVersionRecord publishWorkflow(
            String tenantId, String workflowId, String comment) {
        StoredWorkflow stored = owned(tenantId, workflowId);
        if (stored == null) {
            return null;
        }
        Map<String, Object> draft = stored.document();
        List<WorkflowVersionRecord> workflowVersions =
                versions.computeIfAbsent(workflowId, ignored -> new ArrayList<>());
        int number = workflowVersions.stream()
                .mapToInt(WorkflowVersionRecord::number).max().orElse(0) + 1;
        var version = new WorkflowVersionRecord(
                "wfv-" + UUID.randomUUID().toString().substring(0, 8),
                workflowId,
                number,
                deepCopy(draft),
                comment,
                Instant.now());
        workflowVersions.add(version);
        return version;
    }

    public WorkflowDeploymentRecord getDeployment(String tenantId, String workflowId, String environment) {
        if (owned(tenantId, workflowId) == null) {
            return null;
        }
        return deployments.get(deploymentKey(workflowId, environment));
    }

    public WorkflowDeploymentRecord deployWorkflow(
            String tenantId, String workflowId, String environment, String versionId) {
        if (getWorkflowVersion(tenantId, workflowId, versionId) == null) {
            return null;
        }
        String normalized = normalizeEnvironment(environment);
        var deployment = new WorkflowDeploymentRecord(
                workflowId, normalized, versionId, Instant.now());
        deployments.put(deploymentKey(workflowId, normalized), deployment);
        return deployment;
    }

    /**
     * Returns execution records (most-recent first), optionally filtered by
     * {@code workflowId} and capped at {@code limit}.
     *
     * <p>Each record is deep-copied via {@link #snapshot} <em>under its monitor
     * first</em>, then filtered/sorted/limited on the immutable copies. Reading
     * the raw {@link LinkedHashMap}s directly here would race
     * {@code recordStep}, which structurally adds the {@code "steps"} key while
     * holding the per-record lock — an unsynchronized {@code get} against that
     * put can throw or misread.</p>
     */
    public List<Map<String, Object>> executions(String tenantId, String workflowId, Integer limit) {
        String tenant = DefaultTenant.orDefault(tenantId);
        var stream = executions.values().stream()
                .map(InMemoryWorkflowRuntimeStore::snapshot)
                .filter(exec -> tenant.equals(tenantOf(exec)))
                .filter(exec -> workflowId == null || workflowId.equals(exec.get("workflowId")))
                .sorted(Comparator.comparing((Map<String, Object> exec) ->
                        exec.getOrDefault("startedAt", "").toString()).reversed());
        if (limit != null && limit > 0) {
            stream = stream.limit(limit);
        }
        return stream.toList();
    }

    public Map<String, Object> getExecution(String tenantId, String id) {
        var execution = id == null ? null : executions.get(id);
        if (execution == null) {
            return null;
        }
        var copy = snapshot(execution);
        return DefaultTenant.orDefault(tenantId).equals(tenantOf(copy)) ? copy : null;
    }

    /** Registro sem a chave (anterior ao tenant) é do tenant padrão — a mesma regra do JDBC. */
    private static String tenantOf(Map<String, Object> execution) {
        Object tenant = execution.get(TENANT_KEY);
        return DefaultTenant.orDefault(tenant == null ? null : tenant.toString());
    }

    /**
     * Copies an execution record under its monitor so readers (Jackson
     * serialization in the controllers) never iterate a map/steps list
     * that an engine lifecycle callback is mutating concurrently.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(Map<String, Object> execution) {
        synchronized (execution) {
            var copy = new LinkedHashMap<>(execution);
            if (copy.get("steps") instanceof List<?> steps) {
                var stepsCopy = new ArrayList<Map<String, Object>>(steps.size());
                for (Object step : steps) {
                    stepsCopy.add(new LinkedHashMap<>((Map<String, Object>) step));
                }
                copy.put("steps", stepsCopy);
            }
            return copy;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map) {
                copy.put(key, deepCopy((Map<String, Object>) map));
            } else if (value instanceof List<?> list) {
                copy.put(key, list.stream().map(item -> {
                    if (item instanceof Map<?, ?> map) {
                        return deepCopy((Map<String, Object>) map);
                    }
                    return item;
                }).toList());
            } else {
                copy.put(key, value);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static String deploymentKey(String workflowId, String environment) {
        return workflowId + ":" + normalizeEnvironment(environment);
    }

    private static String normalizeEnvironment(String environment) {
        return environment == null ? "PRODUCTION" : environment.toUpperCase(Locale.ROOT);
    }

    /** Marks a resumed execution RUNNING again (locked counterpart of completeExecution). */
    public void markResumed(String id) {
        var execution = executions.get(id);
        if (execution == null) {
            return;
        }
        synchronized (execution) {
            execution.put("status", "RUNNING");
            execution.put("completedAt", null);
            execution.put("error", null);
        }
    }

    /**
     * Marks a running execution terminal (status + completedAt + optional error). Um status velho
     * que chega depois do desfecho não o apaga — ver {@link WorkflowRuntimeStore#isStaleStatus}.
     */
    public void completeExecution(String id, String status, String error) {
        var execution = executions.get(id);
        if (execution == null) {
            return;
        }
        synchronized (execution) {
            // A checagem fica sob o mesmo lock da escrita: fora dele, a fila poderia gravar
            // STOPPED entre a leitura e a escrita e o PAUSED velho passaria mesmo assim.
            if (WorkflowRuntimeStore.isStaleStatus(execution.get("status"), status)) {
                return;
            }
            execution.put("status", status);
            execution.put("completedAt", Instant.now().toString());
            execution.put("error", WorkflowRuntimeStore.failureSummary(execution, status, error));
        }
        // The per-step index only accelerates lifecycle patches WHILE a run
        // streams; a terminally-finished run receives no more recordStep calls,
        // so drop it to bound memory (the steps themselves stay on the record).
        // A PAUSED/AWAITING run may still be resumed and record more steps, so
        // only evict on a genuinely terminal status — evicting early would make
        // a post-resume recordStep re-create duplicate step entries.
        if (TERMINAL_STATUSES.contains(status)) {
            stepIndexes.remove(id);
        }
    }

    private static final Set<String> TERMINAL_STATUSES =
            Set.of("COMPLETED", "FAILED", "STOPPED", "CANCELLED");

    /** Records the wall-clock duration of an execution (set by the lifecycle listener). */
    public void recordDuration(String id, long durationMs) {
        var execution = executions.get(id);
        if (execution == null) {
            return;
        }
        synchronized (execution) {
            execution.put("duration", WorkflowRuntimeStore.executionDuration(execution, durationMs, Instant.now()));
        }
    }

    /**
     * Appends/updates a per-step record inside the execution's {@code steps} list,
     * keyed by stepId. Called from engine lifecycle callbacks (virtual threads),
     * hence the per-execution synchronization.
     */
    @SuppressWarnings("unchecked")
    public void recordStep(String executionId, String stepId, Map<String, Object> patch) {
        var execution = executions.get(executionId);
        if (execution == null || stepId == null) {
            return;
        }
        synchronized (execution) {
            var steps = (List<Map<String, Object>>) execution.computeIfAbsent(
                    "steps", k -> new ArrayList<Map<String, Object>>());
            // Rebuild the index from the steps already on the record when it's
            // absent (first event, or after a terminal eviction / resume). This
            // keeps a late or post-resume patch updating the existing step in
            // place instead of appending a duplicate row.
            var index = stepIndexes.computeIfAbsent(executionId, k -> {
                var rebuilt = new LinkedHashMap<String, Map<String, Object>>();
                for (Map<String, Object> existing : steps) {
                    Object sid = existing.get("stepId");
                    if (sid != null) {
                        rebuilt.put(sid.toString(), existing);
                    }
                }
                return rebuilt;
            });
            var step = index.computeIfAbsent(stepId, k -> {
                var created = new LinkedHashMap<String, Object>();
                created.put("stepId", stepId);
                steps.add(created);
                return created;
            });
            // Sob o lock da escrita, como em completeExecution: a fila poderia gravar o desfecho
            // do portão entre uma checagem de fora e esta escrita.
            if (WorkflowRuntimeStore.isStaleStepStatus(step.get("status"), patch.get("status"))) {
                return;
            }
            step.putAll(patch);
        }
    }

    /** {@code synchronized} como o {@link #deleteWorkflow}: a execução nasce antes ou depois da exclusão. */
    public synchronized Map<String, Object> createExecution(
            String tenantId, String workflowId, String workflowName, WorkflowVersionRecord version) {
        var executionId = "exec-" + UUID.randomUUID().toString().substring(0, 8);
        var startedAt = Instant.now().toString();
        var execution = new LinkedHashMap<String, Object>();
        execution.put("id", executionId);
        execution.put(TENANT_KEY, DefaultTenant.orDefault(tenantId));
        execution.put("workflowId", workflowId);
        execution.put("workflowName", workflowName);
        execution.put("status", "RUNNING");
        execution.put("startedAt", startedAt);
        execution.put("completedAt", null);
        execution.put("duration", null);
        execution.put("error", null);
        // Antes de publicar o registro no mapa: ninguém o vê sem a marca do que rodou.
        WorkflowRuntimeStore.recordRanVersion(execution, version);
        executions.put(executionId, execution);
        return execution;
    }

    public Map<String, Object> createExecution(String tenantId, String workflowId, String workflowName) {
        return createExecution(tenantId, workflowId, workflowName, null);
    }

    /**
     * O fluxo de demonstração é do tenant padrão — o tenant de quem entra no dev sem personificar
     * ninguém, que é onde ele sempre apareceu. Personificando outro tenant, ele some, como deve.
     */
    private void seedDemoWorkflow() {
        var demo = new HashMap<String, Object>();
        demo.put("id", "wf-demo-001");
        demo.put("status", "active");
        demo.put("updatedAt", Instant.now().toString());
        demo.put("metadata", Map.of(
                "name", "Customer Support Agent",
                "description", "AI-powered customer support workflow with escalation",
                "version", "1.0.0",
                "category", "support",
                "tags", List.of("ai", "support", "agent")
        ));
        demo.put("steps", List.of(
                Map.of("id", "step-1", "type", "AGENT", "name", "Classify Intent"),
                Map.of("id", "step-2", "type", "TOOL", "name", "Search Knowledge Base"),
                Map.of("id", "step-3", "type", "AGENT", "name", "Generate Response")
        ));
        demo.put("configuration", Map.of());
        workflows.put("wf-demo-001", new StoredWorkflow(DefaultTenant.ID, demo));

        var seededExecution = new LinkedHashMap<>(
                createExecution(DefaultTenant.ID, "wf-demo-001", "Customer Support Agent"));
        executions.values().removeIf(execution -> "wf-demo-001".equals(execution.get("workflowId")));
        seededExecution.put("id", "exec-demo-001");
        seededExecution.put("status", "COMPLETED");
        seededExecution.put("completedAt", Instant.now().toString());
        seededExecution.put("duration", 1840);
        executions.put("exec-demo-001", seededExecution);
    }
}
