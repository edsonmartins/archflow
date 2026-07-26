package br.com.archflow.api.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Store durável de laços suspensos (tabela {@code mcp_agent_states}, migration
 * V6_5).
 *
 * <p>O estado vai inteiro numa coluna JSON: ele é a fronteira de durabilidade do
 * laço, e é exatamente o objeto que um orquestrador externo persistiria no lugar
 * desta tabela. Espalhá-lo em colunas amarraria o schema à forma interna do laço
 * e tornaria essa troca uma migração.
 */
public final class JdbcMcpAgentStateStore implements McpAgentStateStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcMcpAgentStateStore.class);

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public JdbcMcpAgentStateStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public void save(McpAgentState state) {
        Objects.requireNonNull(state, "state");
        String sql = """
                INSERT INTO mcp_agent_states (run_id, tenant_id, request_id, state, suspended_at)
                VALUES (?, ?, ?, ?::json, ?)
                ON CONFLICT (run_id) DO UPDATE SET
                    tenant_id = EXCLUDED.tenant_id,
                    request_id = EXCLUDED.request_id,
                    state = EXCLUDED.state,
                    suspended_at = EXCLUDED.suspended_at
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.runId());
            ps.setString(2, state.tenantId() != null ? state.tenantId() : "SYSTEM");
            ps.setString(3, state.pending() != null ? state.pending().requestId() : null);
            ps.setString(4, mapper.writeValueAsString(state));
            ps.setTimestamp(5, Timestamp.from(
                    state.suspendedAt() != null ? state.suspendedAt() : Instant.now()));
            ps.executeUpdate();
        } catch (Exception e) {
            // Falhar alto: se o estado não foi gravado, o laço NÃO está suspenso de
            // forma durável — devolver "suspenso" ao chamador seria mentir, e a
            // decisão humana depois não encontraria nada para retomar.
            throw new IllegalStateException(
                    "Falha ao persistir o laço suspenso " + state.runId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<McpAgentState> findByRunId(String runId) {
        return findBy("run_id", runId);
    }

    @Override
    public Optional<McpAgentState> findByRequestId(String requestId) {
        return findBy("request_id", requestId);
    }

    private Optional<McpAgentState> findBy(String column, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        // `column` vem só das duas chamadas acima, com literais — nunca de entrada
        // externa; os VALORES seguem parametrizados.
        String sql = "SELECT state FROM mcp_agent_states WHERE " + column + " = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(read(rs.getString("state"))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Falha ao carregar laço suspenso por " + column + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM mcp_agent_states WHERE run_id = ?")) {
            ps.setString(1, runId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Falha ao remover laço suspenso " + runId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<McpAgentState> findPendingByTenant(String tenantId) {
        boolean allTenants = tenantId == null || tenantId.isBlank()
                || "all".equalsIgnoreCase(tenantId);
        String sql = allTenants
                ? "SELECT state FROM mcp_agent_states WHERE request_id IS NOT NULL "
                        + "ORDER BY suspended_at"
                : "SELECT state FROM mcp_agent_states WHERE request_id IS NOT NULL "
                        + "AND tenant_id = ? ORDER BY suspended_at";
        List<McpAgentState> found = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (!allTenants) {
                ps.setString(1, tenantId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    McpAgentState state = read(rs.getString("state"));
                    if (state != null) {
                        found.add(state);
                    }
                }
            }
            return found;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Falha ao listar laços suspensos: " + e.getMessage(), e);
        }
    }

    /**
     * Um registro ilegível não pode derrubar a fila inteira — o operador precisa
     * ver os demais. Volta {@code null} com warning, e o chamador o descarta.
     */
    private McpAgentState read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, McpAgentState.class);
        } catch (Exception e) {
            log.warn("Laço suspenso ilegível descartado da leitura: {}", e.getMessage());
            return null;
        }
    }
}
