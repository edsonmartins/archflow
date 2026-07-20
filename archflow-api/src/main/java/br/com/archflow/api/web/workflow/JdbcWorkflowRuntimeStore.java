package br.com.archflow.api.web.workflow;

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
    public Collection<Map<String, Object>> workflows() {
        String sql = "SELECT document FROM workflow_documents";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(fromJson(rs.getString("document")));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list workflow documents", e);
        }
    }

    @Override
    public Map<String, Object> getWorkflow(String id) {
        String sql = "SELECT document FROM workflow_documents WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? fromJson(rs.getString("document")) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load workflow " + id, e);
        }
    }

    @Override
    public Map<String, Object> putWorkflow(String id, Map<String, Object> workflow) {
        String updateSql = "UPDATE workflow_documents SET document = ?, updated_at = ? WHERE id = ?";
        String insertSql = "INSERT INTO workflow_documents (id, document, updated_at) VALUES (?, ?, ?)";
        upsert(updateSql, insertSql, id, toJson(workflow),
                "Failed to save workflow " + id);
        return workflow;
    }

    @Override
    public boolean hasWorkflow(String id) {
        String sql = "SELECT 1 FROM workflow_documents WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check workflow " + id, e);
        }
    }

    @Override
    public void deleteWorkflow(String id) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM workflow_documents WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM workflow_executions WHERE workflow_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete workflow " + id, e);
        }
    }

    // ----------------------------------------------------------------- executions

    @Override
    public List<Map<String, Object>> executions(String workflowId, Integer limit) {
        String sql = workflowId == null
                ? "SELECT record FROM workflow_executions"
                : "SELECT record FROM workflow_executions WHERE workflow_id = ?";
        List<Map<String, Object>> records = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (workflowId != null) {
                ps.setString(1, workflowId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(fromJson(rs.getString("record")));
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
    public Map<String, Object> getExecution(String id) {
        String sql = "SELECT record FROM workflow_executions WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                // A deserialização já produz uma cópia fresca — equivalente ao
                // snapshot defensivo do in-memory.
                return rs.next() ? fromJson(rs.getString("record")) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load execution " + id, e);
        }
    }

    @Override
    public Map<String, Object> putExecution(String id, Map<String, Object> execution) {
        saveExecution(id, execution);
        return execution;
    }

    @Override
    public Map<String, Object> createExecution(String workflowId, String workflowName) {
        var executionId = "exec-" + UUID.randomUUID().toString().substring(0, 8);
        var execution = new LinkedHashMap<String, Object>();
        execution.put("id", executionId);
        execution.put("workflowId", workflowId);
        execution.put("workflowName", workflowName);
        execution.put("status", "RUNNING");
        execution.put("startedAt", Instant.now().toString());
        execution.put("completedAt", null);
        execution.put("duration", null);
        execution.put("error", null);
        saveExecution(executionId, execution);
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
        mutateExecution(id, execution -> {
            execution.put("status", status);
            execution.put("completedAt", Instant.now().toString());
            execution.put("error", error);
        });
        if (TERMINAL_STATUSES.contains(status)) {
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
        mutateExecution(executionId, execution -> {
            var steps = (List<Map<String, Object>>) execution.computeIfAbsent(
                    "steps", k -> new ArrayList<Map<String, Object>>());
            Map<String, Object> step = steps.stream()
                    .filter(existing -> stepId.equals(String.valueOf(existing.get("stepId"))))
                    .findFirst()
                    .orElseGet(() -> {
                        var created = new LinkedHashMap<String, Object>();
                        created.put("stepId", stepId);
                        steps.add(created);
                        return created;
                    });
            step.putAll(patch);
        });
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Read-modify-write do JSON inteiro da execução sob o lock da execução.
     * No-op quando a execução não existe (mesma semântica do in-memory).
     */
    private void mutateExecution(String id, Consumer<Map<String, Object>> mutation) {
        Object lock = executionLocks.computeIfAbsent(id, k -> new Object());
        synchronized (lock) {
            Map<String, Object> execution = getExecution(id);
            if (execution == null) {
                return;
            }
            mutation.accept(execution);
            saveExecution(id, execution);
        }
    }

    private void saveExecution(String id, Map<String, Object> execution) {
        String updateSql =
                "UPDATE workflow_executions SET workflow_id = ?, record = ?, updated_at = ? WHERE id = ?";
        String insertSql =
                "INSERT INTO workflow_executions (workflow_id, record, updated_at, id) VALUES (?, ?, ?, ?)";
        String workflowId = Objects.toString(execution.get("workflowId"), "");
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
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save execution " + id, e);
        }
    }

    /** Upsert portável para documentos: UPDATE primeiro, INSERT quando 0 linhas. */
    private void upsert(String updateSql, String insertSql, String id, String payload,
                        String errorMessage) {
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection conn = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, payload);
                ps.setTimestamp(2, now);
                ps.setString(3, id);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, id);
                    ps.setString(2, payload);
                    ps.setTimestamp(3, now);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(errorMessage, e);
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
}
