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
        return getState("SYSTEM", flowId);
    }

    @Override
    public void saveAuditLog(String flowId, AuditLog log) {
        saveAuditLog("SYSTEM", flowId, log);
    }

    @Override
    public void saveState(String tenantId, String flowId, FlowState state) {
        String sql = """
            INSERT INTO flow_states (tenant_id, flow_id, status, current_step_id, variables, metrics, error, updated_at)
            VALUES (?, ?, ?, ?, ?::json, ?::json, ?::json, ?)
            ON CONFLICT (tenant_id, flow_id) DO UPDATE SET
                status = EXCLUDED.status,
                current_step_id = EXCLUDED.current_step_id,
                variables = EXCLUDED.variables,
                metrics = EXCLUDED.metrics,
                error = EXCLUDED.error,
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
            ps.setTimestamp(8, Timestamp.from(Instant.now()));

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

    @SuppressWarnings("unchecked")
    private FlowState mapRowToFlowState(ResultSet rs) throws SQLException {
        return FlowState.builder()
                .tenantId(rs.getString("tenant_id"))
                .flowId(rs.getString("flow_id"))
                .status(FlowStatus.valueOf(rs.getString("status")))
                .currentStepId(rs.getString("current_step_id"))
                .variables(fromJson(rs.getString("variables"), Map.class))
                .build();
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Erro ao serializar para JSON", e);
            return null;
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
