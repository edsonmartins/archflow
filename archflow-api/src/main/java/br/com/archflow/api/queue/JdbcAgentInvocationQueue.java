package br.com.archflow.api.queue;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * {@link AgentInvocationQueue} durável sobre a tabela {@code agent_invocations}
 * (migration {@code V6_2__create_agent_invocations.sql}). Invocações pendentes
 * sobrevivem a restart — requisito do {@code ProductionReadinessGuard}.
 *
 * <p>O {@code poll()} usa claim atômico portável (H2/PostgreSQL): seleciona a
 * requisição mais antiga e a remove por id; se outra instância a consumiu no
 * intervalo ({@code DELETE} afeta 0 linhas), tenta a próxima.
 */
public class JdbcAgentInvocationQueue implements AgentInvocationQueue {

    private static final Logger logger = Logger.getLogger(JdbcAgentInvocationQueue.class.getName());
    private static final int DEFAULT_MAX_RECURSION_DEPTH = 10;
    private static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    private static final int CLAIM_ATTEMPTS = 3;

    private final DataSource dataSource;
    private final int maxRecursionDepth;
    private final int queueCapacity;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcAgentInvocationQueue(DataSource dataSource) {
        this(dataSource, DEFAULT_MAX_RECURSION_DEPTH, DEFAULT_QUEUE_CAPACITY);
    }

    public JdbcAgentInvocationQueue(DataSource dataSource, int maxRecursionDepth, int queueCapacity) {
        this.dataSource = dataSource;
        this.maxRecursionDepth = maxRecursionDepth;
        this.queueCapacity = queueCapacity;
    }

    @Override
    public void submit(InvocationRequest request) {
        if (request.recursionDepth() > maxRecursionDepth) {
            throw new MaxRecursionDepthException(request.recursionDepth(), maxRecursionDepth);
        }

        String sql = """
            INSERT INTO agent_invocations
                (id, tenant_id, agent_id, payload, parent_execution_id, recursion_depth, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = dataSource.getConnection()) {
            // Guarda de capacidade na MESMA conexão do INSERT, com um teste
            // limitado (para no limite) em vez de COUNT(*) full-scan da tabela.
            if (atCapacity(conn)) {
                throw new RuntimeException("Invocation queue is full (capacity=" + queueCapacity + ")");
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, request.requestId());
                ps.setString(2, request.tenantId());
                ps.setString(3, request.agentId());
                ps.setString(4, mapper.writeValueAsString(request.payload()));
                ps.setString(5, request.parentExecutionId());
                ps.setInt(6, request.recursionDepth());
                ps.setTimestamp(7, Timestamp.from(request.createdAt()));
                ps.executeUpdate();
            }
            logger.info(() -> String.format(
                    "Invocation queued (jdbc): tenant=%s, agent=%s, depth=%d, requestId=%s",
                    request.tenantId(), request.agentId(), request.recursionDepth(), request.requestId()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue agent invocation " + request.requestId(), e);
        }
    }

    /**
     * {@code true} se já existe uma linha na posição {@code queueCapacity} —
     * ou seja, a fila está cheia. Para no limite ({@code OFFSET cap LIMIT 1}),
     * sem varrer a tabela inteira como faria um {@code COUNT(*)}.
     */
    private boolean atCapacity(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM agent_invocations OFFSET ? LIMIT 1")) {
            ps.setInt(1, queueCapacity);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public Optional<InvocationRequest> poll() {
        String selectSql = """
            SELECT id, tenant_id, agent_id, payload, parent_execution_id, recursion_depth, created_at
            FROM agent_invocations ORDER BY created_at, id LIMIT 1
            """;
        String deleteSql = "DELETE FROM agent_invocations WHERE id = ?";

        try (Connection conn = dataSource.getConnection()) {
            for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
                InvocationRequest candidate;
                try (PreparedStatement ps = conn.prepareStatement(selectSql);
                     ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    candidate = mapRow(rs);
                }
                try (PreparedStatement del = conn.prepareStatement(deleteSql)) {
                    del.setString(1, candidate.requestId());
                    if (del.executeUpdate() == 1) {
                        return Optional.of(candidate);
                    }
                    // Outra instância consumiu esta requisição; tenta a próxima.
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new RuntimeException("Failed to poll agent invocation queue", e);
        }
    }

    @Override
    public int size() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM agent_invocations");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to size agent invocation queue", e);
        }
    }

    @Override
    public int getMaxRecursionDepth() {
        return maxRecursionDepth;
    }

    @Override
    public List<InvocationRequest> pending() {
        String sql = """
            SELECT id, tenant_id, agent_id, payload, parent_execution_id, recursion_depth, created_at
            FROM agent_invocations ORDER BY created_at, id
            """;
        List<InvocationRequest> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list pending agent invocations", e);
        }
    }

    @SuppressWarnings("unchecked")
    private InvocationRequest mapRow(ResultSet rs) throws Exception {
        String payloadJson = rs.getString("payload");
        Map<String, Object> payload = payloadJson == null || payloadJson.isBlank()
                ? Map.of()
                : mapper.readValue(payloadJson, Map.class);
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new InvocationRequest(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("agent_id"),
                payload,
                rs.getString("parent_execution_id"),
                rs.getInt("recursion_depth"),
                createdAt != null ? createdAt.toInstant() : Instant.now());
    }
}
