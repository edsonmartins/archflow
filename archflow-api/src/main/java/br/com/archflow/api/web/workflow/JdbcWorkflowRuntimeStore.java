package br.com.archflow.api.web.workflow;

import br.com.archflow.model.tenant.DefaultTenant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@link WorkflowRuntimeStore} durável sobre as tabelas {@code workflow_documents}
 * e {@code workflow_executions} (migration {@code V6_3__create_workflow_runtime.sql}).
 * Workflows do designer e histórico de execuções sobrevivem a restart — requisito
 * do {@code ProductionReadinessGuard}.
 *
 * <p>Documento e registro de execução são serializados inteiros como JSON (Jackson)
 * na coluna TEXT — mutações de execução (complete/step-record/duration) fazem
 * read-modify-write do JSON completo sob um lock por execução. Volume de
 * homologação: simplicidade &gt; otimização.
 *
 * <p>Upsert portável (PostgreSQL, H2): UPDATE primeiro e INSERT quando não existe
 * linha — mesmo padrão do {@code JdbcFlowRepository}.
 *
 * <p>Tenant (RCHFLW-73, migration {@code V6_12_2}): {@code workflow_documents.tenant_id} e
 * {@code workflow_executions.tenant_id} são a fonte da verdade e entram no {@code WHERE} de toda
 * leitura. O tenant fica fora do JSON do documento (que a UI edita e reenvia); no JSON da execução
 * ele é reescrito a partir da coluna a cada leitura, para que um registro antigo, gravado sem a
 * chave, diga o mesmo tenant que o filtro aplicou. Versões e implantações não têm coluna: pendem de
 * um workflow cujo id é único no store, e cada método checa o dono dele antes.
 */
public class JdbcWorkflowRuntimeStore implements WorkflowRuntimeStore {

    private static final Set<String> TERMINAL_STATUSES =
            Set.of("COMPLETED", "FAILED", "STOPPED", "CANCELLED");

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();
    // Serializa os read-modify-write concorrentes (callbacks do engine em virtual
    // threads) da MESMA execução dentro desta JVM — sem ele, dois recordStep
    // simultâneos perderiam o patch um do outro. Evicted quando a execução
    // termina (nenhum recordStep chega depois de um status terminal).
    private final Map<String, Object> executionLocks = new ConcurrentHashMap<>();

    public JdbcWorkflowRuntimeStore(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        this.dataSource = dataSource;
    }

    // ------------------------------------------------------------------ workflows

    @Override
    public Collection<Map<String, Object>> workflows(String tenantId) {
        String sql = "SELECT document FROM workflow_documents WHERE tenant_id = ?";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DefaultTenant.orDefault(tenantId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(fromJson(rs.getString("document")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list workflow documents", e);
        }
    }

    @Override
    public Map<String, Object> getWorkflow(String tenantId, String id) {
        String sql = "SELECT document FROM workflow_documents WHERE id = ? AND tenant_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, DefaultTenant.orDefault(tenantId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? fromJson(rs.getString("document")) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load workflow " + id, e);
        }
    }

    /**
     * UPDATE restrito ao tenant; sem linha atualizada, o INSERT só acontece se o id estiver livre —
     * se for de outro tenant, {@link WorkflowOwnershipException}. A chave primária continua sendo a
     * última barreira numa corrida entre a checagem e o INSERT.
     */
    @Override
    public Map<String, Object> putWorkflow(String tenantId, String id, Map<String, Object> workflow) {
        String tenant = DefaultTenant.orDefault(tenantId);
        String payload = toJson(workflow);
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection conn = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE workflow_documents SET document = ?, updated_at = ? WHERE id = ? AND tenant_id = ?")) {
                ps.setString(1, payload);
                ps.setTimestamp(2, now);
                ps.setString(3, id);
                ps.setString(4, tenant);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                if (exists(conn, "SELECT 1 FROM workflow_documents WHERE id = ?", id)) {
                    throw new WorkflowOwnershipException(id);
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO workflow_documents (id, tenant_id, document, updated_at) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, id);
                    ps.setString(2, tenant);
                    ps.setString(3, payload);
                    ps.setTimestamp(4, now);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save workflow " + id, e);
        }
        return workflow;
    }

    @Override
    public boolean hasWorkflow(String tenantId, String id) {
        try (Connection conn = dataSource.getConnection()) {
            return owns(conn, tenantId, id);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check workflow " + id, e);
        }
    }

    @Override
    public void deleteWorkflow(String tenantId, String id) {
        try (Connection conn = dataSource.getConnection()) {
            // Checa o dono antes de tocar nas tabelas dependentes: versões e implantações não têm
            // tenant próprio, então apagá-las por workflow_id sem essa checagem apagaria as do outro.
            if (!owns(conn, tenantId, id)) {
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM workflow_deployments WHERE workflow_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM workflow_versions WHERE workflow_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM workflow_documents WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM workflow_executions WHERE workflow_id = ? AND tenant_id = ?")) {
                ps.setString(1, id);
                ps.setString(2, DefaultTenant.orDefault(tenantId));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete workflow " + id, e);
        }
    }

    private static boolean owns(Connection conn, String tenantId, String id) throws SQLException {
        return exists(conn, "SELECT 1 FROM workflow_documents WHERE id = ? AND tenant_id = ?",
                id, DefaultTenant.orDefault(tenantId));
    }

    private boolean owns(String tenantId, String id) {
        try (Connection conn = dataSource.getConnection()) {
            return owns(conn, tenantId, id);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check workflow " + id, e);
        }
    }

    private static boolean exists(Connection conn, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public List<WorkflowVersionRecord> workflowVersions(String tenantId, String workflowId) {
        if (!owns(tenantId, workflowId)) {
            return List.of();
        }
        String sql = """
                SELECT id, version_no, document, comment, created_at
                  FROM workflow_versions
                 WHERE workflow_id = ?
                 ORDER BY version_no DESC
                """;
        List<WorkflowVersionRecord> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readVersion(workflowId, rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list versions for workflow " + workflowId, e);
        }
    }

    @Override
    public WorkflowVersionRecord getWorkflowVersion(String tenantId, String workflowId, String versionId) {
        if (!owns(tenantId, workflowId)) {
            return null;
        }
        String sql = """
                SELECT id, version_no, document, comment, created_at
                  FROM workflow_versions
                 WHERE workflow_id = ? AND id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            ps.setString(2, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readVersion(workflowId, rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load workflow version " + versionId, e);
        }
    }

    @Override
    public WorkflowVersionRecord publishWorkflow(String tenantId, String workflowId, String comment) {
        Map<String, Object> draft = getWorkflow(tenantId, workflowId);
        if (draft == null) {
            return null;
        }
        String versionId = "wfv-" + UUID.randomUUID().toString().substring(0, 8);
        Instant createdAt = Instant.now();
        String sql = """
                INSERT INTO workflow_versions
                    (id, workflow_id, version_no, document, comment, created_at)
                SELECT ?, ?, COALESCE(MAX(version_no), 0) + 1, ?, ?, ?
                  FROM workflow_versions
                 WHERE workflow_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, versionId);
            ps.setString(2, workflowId);
            ps.setString(3, toJson(draft));
            ps.setString(4, comment);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            ps.setString(6, workflowId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to publish workflow " + workflowId, e);
        }
        return getWorkflowVersion(tenantId, workflowId, versionId);
    }

    @Override
    public WorkflowDeploymentRecord getDeployment(String tenantId, String workflowId, String environment) {
        if (!owns(tenantId, workflowId)) {
            return null;
        }
        String normalized = normalizeEnvironment(environment);
        String sql = """
                SELECT version_id, deployed_at
                  FROM workflow_deployments
                 WHERE workflow_id = ? AND environment = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            ps.setString(2, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new WorkflowDeploymentRecord(workflowId, normalized,
                                rs.getString("version_id"),
                                rs.getTimestamp("deployed_at").toInstant())
                        : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load workflow deployment", e);
        }
    }

    @Override
    public WorkflowDeploymentRecord deployWorkflow(
            String tenantId, String workflowId, String environment, String versionId) {
        if (getWorkflowVersion(tenantId, workflowId, versionId) == null) {
            return null;
        }
        String normalized = normalizeEnvironment(environment);
        Instant deployedAt = Instant.now();
        String update = """
                UPDATE workflow_deployments
                   SET version_id = ?, deployed_at = ?
                 WHERE workflow_id = ? AND environment = ?
                """;
        String insert = """
                INSERT INTO workflow_deployments
                    (version_id, deployed_at, workflow_id, environment)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.setString(1, versionId);
                ps.setTimestamp(2, Timestamp.from(deployedAt));
                ps.setString(3, workflowId);
                ps.setString(4, normalized);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    ps.setString(1, versionId);
                    ps.setTimestamp(2, Timestamp.from(deployedAt));
                    ps.setString(3, workflowId);
                    ps.setString(4, normalized);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to deploy workflow version " + versionId, e);
        }
        return new WorkflowDeploymentRecord(
                workflowId, normalized, versionId, deployedAt);
    }

    // ----------------------------------------------------------------- executions

    @Override
    public List<Map<String, Object>> executions(String tenantId, String workflowId, Integer limit) {
        String sql = workflowId == null
                ? "SELECT record, tenant_id FROM workflow_executions WHERE tenant_id = ?"
                : "SELECT record, tenant_id FROM workflow_executions WHERE tenant_id = ? AND workflow_id = ?";
        List<Map<String, Object>> records = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DefaultTenant.orDefault(tenantId));
            if (workflowId != null) {
                ps.setString(2, workflowId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(readExecution(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list workflow executions", e);
        }
        // Ordenação em Java pelo startedAt do JSON — espelha exatamente a
        // semântica (most-recent first) do in-memory, sem depender de funções
        // JSON específicas de banco. Volume de homologação.
        var stream = records.stream()
                .sorted(Comparator.comparing((Map<String, Object> exec) ->
                        String.valueOf(exec.getOrDefault("startedAt", ""))).reversed());
        if (limit != null && limit > 0) {
            stream = stream.limit(limit);
        }
        return stream.toList();
    }

    @Override
    public Map<String, Object> getExecution(String tenantId, String id) {
        Map<String, Object> execution = loadExecution(id);
        return execution != null
                && DefaultTenant.orDefault(tenantId).equals(execution.get(TENANT_KEY)) ? execution : null;
    }

    /** Leitura por id, sem tenant: só para as mutações internas (callbacks do motor). */
    private Map<String, Object> loadExecution(String id) {
        String sql = "SELECT record, tenant_id FROM workflow_executions WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                // A deserialização já produz uma cópia fresca — equivalente ao
                // snapshot defensivo do in-memory.
                return rs.next() ? readExecution(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load execution " + id, e);
        }
    }

    /** O tenant do registro vem da coluna, inclusive para JSON antigo gravado sem a chave. */
    private Map<String, Object> readExecution(ResultSet rs) throws SQLException {
        Map<String, Object> execution = fromJson(rs.getString("record"));
        execution.put(TENANT_KEY, rs.getString("tenant_id"));
        return execution;
    }

    @Override
    public Map<String, Object> createExecution(
            String tenantId, String workflowId, String workflowName, WorkflowVersionRecord version) {
        Map<String, Object> execution = newExecution(tenantId, workflowId, workflowName);
        WorkflowRuntimeStore.recordRanVersion(execution, version);
        saveExecution(String.valueOf(execution.get("id")), execution);
        return execution;
    }

    @Override
    public Map<String, Object> createExecution(String tenantId, String workflowId, String workflowName) {
        return createExecution(tenantId, workflowId, workflowName, null);
    }

    private static Map<String, Object> newExecution(String tenantId, String workflowId, String workflowName) {
        var executionId = "exec-" + UUID.randomUUID().toString().substring(0, 8);
        var execution = new LinkedHashMap<String, Object>();
        execution.put("id", executionId);
        execution.put(TENANT_KEY, DefaultTenant.orDefault(tenantId));
        execution.put("workflowId", workflowId);
        execution.put("workflowName", workflowName);
        execution.put("status", "RUNNING");
        execution.put("startedAt", Instant.now().toString());
        execution.put("completedAt", null);
        execution.put("duration", null);
        execution.put("error", null);
        return execution;
    }

    @Override
    public void markResumed(String id) {
        mutateExecution(id, execution -> {
            execution.put("status", "RUNNING");
            execution.put("completedAt", null);
            execution.put("error", null);
        });
    }

    @Override
    public void completeExecution(String id, String status, String error) {
        // A checagem roda sob o lock do read-modify-write: fora dele, a fila poderia gravar
        // STOPPED entre a leitura e a escrita e o PAUSED velho passaria mesmo assim. Ignorado,
        // nem regrava a linha — o desfecho fica intacto, inclusive o updated_at.
        boolean applied = mutateExecutionIf(id, execution -> {
            if (WorkflowRuntimeStore.isStaleStatus(execution.get("status"), status)) {
                return false;
            }
            execution.put("status", status);
            execution.put("completedAt", Instant.now().toString());
            execution.put("error", error);
            return true;
        });
        // Ignorado, o registro já é terminal: o lock que a escrita recriou também sai.
        if (!applied || TERMINAL_STATUSES.contains(status)) {
            executionLocks.remove(id);
        }
    }

    @Override
    public void recordDuration(String id, long durationMs) {
        mutateExecution(id, execution -> execution.put("duration", durationMs));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void recordStep(String executionId, String stepId, Map<String, Object> patch) {
        if (stepId == null) {
            return;
        }
        boolean[] terminal = {false};
        boolean applied = mutateExecutionIf(executionId, execution -> {
            var steps = (List<Map<String, Object>>) execution.computeIfAbsent(
                    "steps", k -> new ArrayList<Map<String, Object>>());
            Map<String, Object> existingStep = steps.stream()
                    .filter(existing -> stepId.equals(String.valueOf(existing.get("stepId"))))
                    .findFirst()
                    .orElse(null);
            // Sob o lock do read-modify-write, como em completeExecution. Ignorado, nem regrava a
            // linha: o desfecho do passo (e quem decidiu o portão) fica intacto.
            if (existingStep != null
                    && WorkflowRuntimeStore.isStaleStepStatus(existingStep.get("status"), patch.get("status"))) {
                terminal[0] = TERMINAL_STATUSES.contains(String.valueOf(execution.get("status")));
                return false;
            }
            Map<String, Object> step = existingStep;
            if (step == null) {
                step = new LinkedHashMap<>();
                step.put("stepId", stepId);
                steps.add(step);
            }
            step.putAll(patch);
            return true;
        });
        // O patch velho chegou depois do fim da execução: o lock que ele recriou também sai.
        if (!applied && terminal[0]) {
            executionLocks.remove(executionId);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Read-modify-write do JSON inteiro da execução sob o lock da execução.
     * No-op quando a execução não existe (mesma semântica do in-memory).
     */
    private void mutateExecution(String id, Consumer<Map<String, Object>> mutation) {
        mutateExecutionIf(id, execution -> {
            mutation.accept(execution);
            return true;
        });
    }

    /**
     * Como {@link #mutateExecution}, mas a mutação pode desistir ({@code false}) depois de ler o
     * registro sob o lock — e então nada é gravado. Devolve se a mutação foi gravada.
     */
    private boolean mutateExecutionIf(String id, Predicate<Map<String, Object>> mutation) {
        Object lock = executionLocks.computeIfAbsent(id, k -> new Object());
        synchronized (lock) {
            Map<String, Object> execution = loadExecution(id);
            if (execution == null || !mutation.test(execution)) {
                return false;
            }
            saveExecution(id, execution);
            return true;
        }
    }

    /**
     * O UPDATE não mexe em {@code tenant_id}: o dono de uma execução é decidido uma vez, no INSERT, e
     * um read-modify-write posterior não tem como trocá-lo.
     */
    private void saveExecution(String id, Map<String, Object> execution) {
        String updateSql =
                "UPDATE workflow_executions SET workflow_id = ?, record = ?, updated_at = ? WHERE id = ?";
        String insertSql = "INSERT INTO workflow_executions (workflow_id, record, updated_at, id, tenant_id) "
                + "VALUES (?, ?, ?, ?, ?)";
        String workflowId = Objects.toString(execution.get("workflowId"), "");
        Object tenant = execution.get(TENANT_KEY);
        String json = toJson(execution);
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection conn = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, workflowId);
                ps.setString(2, json);
                ps.setTimestamp(3, now);
                ps.setString(4, id);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, workflowId);
                    ps.setString(2, json);
                    ps.setTimestamp(3, now);
                    ps.setString(4, id);
                    ps.setString(5, DefaultTenant.orDefault(tenant == null ? null : tenant.toString()));
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save execution " + id, e);
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize workflow runtime record", e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize workflow runtime record", e);
        }
    }

    private WorkflowVersionRecord readVersion(String workflowId, ResultSet rs)
            throws SQLException {
        return new WorkflowVersionRecord(
                rs.getString("id"),
                workflowId,
                rs.getInt("version_no"),
                fromJson(rs.getString("document")),
                rs.getString("comment"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static String normalizeEnvironment(String environment) {
        return environment == null ? "PRODUCTION" : environment.toUpperCase(java.util.Locale.ROOT);
    }
}
