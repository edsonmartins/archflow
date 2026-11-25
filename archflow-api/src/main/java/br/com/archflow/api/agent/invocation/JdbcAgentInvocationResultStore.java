package br.com.archflow.api.agent.invocation;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Resultados de invocação na tabela {@code agent_invocation_results} (migration {@code V6_13}).
 *
 * <p>O {@code save} é um upsert portável (H2/PostgreSQL): {@code UPDATE} e, se nenhuma linha existia,
 * {@code INSERT}. Dois consumidores só gravariam o mesmo {@code requestId} se um lease vencesse no meio
 * da execução; nesse caso vale a última gravação, e o {@code INSERT} que perder a corrida da chave
 * primária vira {@code UPDATE}.</p>
 */
public class JdbcAgentInvocationResultStore implements AgentInvocationResultStore {

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public JdbcAgentInvocationResultStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void save(AgentInvocationResult r) {
        String update = """
            UPDATE agent_invocation_results
               SET tenant_id = ?, agent_id = ?, status = ?, output = ?, error = ?, attempts = ?,
                   created_at = ?, started_at = ?, finished_at = ?
             WHERE request_id = ?
            """;
        String insert = """
            INSERT INTO agent_invocation_results
                (tenant_id, agent_id, status, output, error, attempts, created_at, started_at, finished_at,
                 request_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = dataSource.getConnection()) {
            String output = r.output() == null ? null : mapper.writeValueAsString(r.output());
            if (execute(conn, update, r, output) == 1) {
                return;
            }
            try {
                execute(conn, insert, r, output);
            } catch (SQLException duplicate) {
                // Outro consumidor inseriu entre o UPDATE e o INSERT: a linha existe agora.
                if (execute(conn, update, r, output) != 1) {
                    throw duplicate;
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save agent invocation result " + r.requestId(), e);
        }
    }

    private static int execute(Connection conn, String sql, AgentInvocationResult r, String output)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.tenantId());
            ps.setString(2, r.agentId());
            ps.setString(3, r.status().name());
            ps.setString(4, output);
            ps.setString(5, r.error());
            ps.setInt(6, r.attempts());
            ps.setTimestamp(7, timestamp(r.createdAt() == null ? Instant.now() : r.createdAt()));
            ps.setTimestamp(8, timestamp(r.startedAt()));
            ps.setTimestamp(9, timestamp(r.finishedAt()));
            ps.setString(10, r.requestId());
            return ps.executeUpdate();
        }
    }

    @Override
    public Optional<AgentInvocationResult> find(String requestId) {
        String sql = """
            SELECT request_id, tenant_id, agent_id, status, output, error, attempts,
                   created_at, started_at, finished_at
              FROM agent_invocation_results WHERE request_id = ?
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String output = rs.getString("output");
                return Optional.of(new AgentInvocationResult(
                        rs.getString("request_id"),
                        rs.getString("tenant_id"),
                        rs.getString("agent_id"),
                        AgentInvocationResult.Status.valueOf(rs.getString("status")),
                        output == null ? null : mapper.readValue(output, Object.class),
                        rs.getString("error"),
                        rs.getInt("attempts"),
                        instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("finished_at"))));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to find agent invocation result " + requestId, e);
        }
    }

    @Override
    public int purgeFinishedBefore(Instant cutoff) {
        // finished_at só existe nos finais; é a coluna do índice da V6_13, que existe para esta varredura.
        String sql = "DELETE FROM agent_invocation_results WHERE finished_at IS NOT NULL AND finished_at < ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to purge agent invocation results", e);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
