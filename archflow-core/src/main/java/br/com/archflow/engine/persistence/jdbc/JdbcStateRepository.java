package br.com.archflow.engine.persistence.jdbc;

import br.com.archflow.engine.persistence.StateRepository;
import br.com.archflow.model.flow.AuditLog;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementação JDBC do StateRepository com suporte a multi-tenancy.
 * Usa chave composta (tenant_id, flow_id) para isolamento entre tenants.
 */
public class JdbcStateRepository implements StateRepository {
    private static final Logger logger = Logger.getLogger(JdbcStateRepository.class.getName());

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcStateRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    @Override
    public void saveState(String flowId, FlowState state) {
        String tenantId = state.getTenantId() != null ? state.getTenantId() : "SYSTEM";
        saveState(tenantId, flowId, state);
    }

    @Override
    public FlowState getState(String flowId) {
        // saveState keys by the state's real tenantId; a fixed-tenant lookup
        // here would never find flows of any tenant other than SYSTEM.
        // flowId is unique per execution, so a cross-tenant lookup is safe.
        String sql = "SELECT * FROM flow_states WHERE flow_id = ? ORDER BY updated_at DESC LIMIT 1";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, flowId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRowToFlowState(rs);
                }
            }
            return null;

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Erro ao recuperar estado: flow=" + flowId, e);
            throw new RuntimeException("Failed to get flow state", e);
        }
    }

    @Override
    public void saveAuditLog(String flowId, AuditLog log) {
        saveAuditLog("SYSTEM", flowId, log);
    }

    @Override
    public void saveState(String tenantId, String flowId, FlowState state) {
        String sql = """
            INSERT INTO flow_states (tenant_id, flow_id, status, current_step_id, variables, metrics, error, execution_paths, updated_at)
            VALUES (?, ?, ?, ?, ?::json, ?::json, ?::json, ?::json, ?)
            ON CONFLICT (tenant_id, flow_id) DO UPDATE SET
                status = EXCLUDED.status,
                current_step_id = EXCLUDED.current_step_id,
                variables = EXCLUDED.variables,
                metrics = EXCLUDED.metrics,
                error = EXCLUDED.error,
                execution_paths = EXCLUDED.execution_paths,
                updated_at = EXCLUDED.updated_at
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, tenantId);
            ps.setString(2, flowId);
            ps.setString(3, state.getStatus() != null ? state.getStatus().name() : null);
            ps.setString(4, state.getCurrentStepId());
            ps.setString(5, toJson(state.getVariables()));
            ps.setString(6, toJson(state.getMetrics()));
            ps.setString(7, toJson(state.getError()));
            ps.setString(8, toJson(state.getExecutionPaths()));
            ps.setTimestamp(9, Timestamp.from(Instant.now()));

            ps.executeUpdate();
            logger.fine("Estado salvo para tenant=" + tenantId + ", flow=" + flowId);

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Erro ao salvar estado: tenant=" + tenantId + ", flow=" + flowId, e);
            throw new RuntimeException("Failed to save flow state", e);
        }
    }

    @Override
    public FlowState getState(String tenantId, String flowId) {
        String sql = "SELECT * FROM flow_states WHERE tenant_id = ? AND flow_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, tenantId);
            ps.setString(2, flowId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRowToFlowState(rs);
                }
            }
            return null;

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Erro ao recuperar estado: tenant=" + tenantId + ", flow=" + flowId, e);
            throw new RuntimeException("Failed to get flow state", e);
        }
    }

    @Override
    public void saveAuditLog(String tenantId, String flowId, AuditLog log) {
        String sql = "INSERT INTO audit_logs (tenant_id, flow_id, state_snapshot, created_at) VALUES (?, ?, ?::json, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, tenantId);
            ps.setString(2, flowId);
            ps.setString(3, toJson(log));
            ps.setTimestamp(4, Timestamp.from(log.getTimestamp() != null ? log.getTimestamp() : Instant.now()));

            ps.executeUpdate();

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Erro ao salvar audit log: tenant=" + tenantId + ", flow=" + flowId, e);
            throw new RuntimeException("Failed to save audit log", e);
        }
    }

    @Override
    public List<FlowState> getStatesByTenant(String tenantId) {
        String sql = "SELECT * FROM flow_states WHERE tenant_id = ? ORDER BY updated_at DESC";
        List<FlowState> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, tenantId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRowToFlowState(rs));
                }
            }
            return results;

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Erro ao listar estados do tenant: " + tenantId, e);
            throw new RuntimeException("Failed to list flow states for tenant", e);
        }
    }

    @Override
    public List<FlowState> getStatesByStatus(String status) {
        String sql = "SELECT * FROM flow_states WHERE status = ? ORDER BY updated_at DESC";
        List<FlowState> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, status);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRowToFlowState(rs));
                }
            }
            return results;

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Erro ao listar estados por status: " + status, e);
            throw new RuntimeException("Failed to list flow states by status", e);
        }
    }

    @SuppressWarnings("unchecked")
    private FlowState mapRowToFlowState(ResultSet rs) throws SQLException {
        // metrics/error/executionPaths são reconstruídos com degradação graciosa
        // (fromJson devolve null com warning se o payload não desserializar).
        return FlowState.builder()
                .tenantId(rs.getString("tenant_id"))
                .flowId(rs.getString("flow_id"))
                .status(FlowStatus.valueOf(rs.getString("status")))
                .currentStepId(rs.getString("current_step_id"))
                .variables(fromJson(rs.getString("variables"), Map.class))
                .metrics(fromJson(rs.getString("metrics"),
                        br.com.archflow.model.flow.FlowMetrics.class))
                .error(fromJson(rs.getString("error"),
                        br.com.archflow.model.error.ExecutionError.class))
                .executionPaths(readExecutionPaths(rs))
                .build();
    }

    /**
     * Lê a coluna acrescentada em V1_3. Tolera a ausência da coluna para não
     * quebrar um deployment cujo schema ainda não migrou — o resto do estado
     * continua utilizável, que é preferível a não carregar o fluxo.
     */
    private List<br.com.archflow.model.flow.ExecutionPath> readExecutionPaths(ResultSet rs) {
        try {
            String json = rs.getString("execution_paths");
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class,
                            br.com.archflow.model.flow.ExecutionPath.class));
        } catch (SQLException e) {
            logger.log(Level.FINE, "Coluna execution_paths ausente (schema anterior a V1_3)");
            return null;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Erro ao desserializar execution_paths", e);
            return null;
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            // Gravar null silenciosamente faria variáveis sumirem do estado
            // durável; falha alto para o chamador decidir (o engine loga e
            // não derruba o fluxo).
            throw new IllegalStateException(
                    "Estado do fluxo contém valor não serializável para JSON: " + e.getMessage(), e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Erro ao deserializar JSON", e);
            return null;
        }
    }
}
