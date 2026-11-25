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
import java.time.Clock;
import java.time.Duration;
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
 * <p>O consumidor usa {@link #claim}: um {@code UPDATE} condicional (portável H2/PostgreSQL) marca a
 * linha mais antiga livre com {@code claimed_by}/{@code claimed_until}; se outra instância a pegou no
 * intervalo (o {@code UPDATE} afeta 0 linhas), tenta a próxima. A linha só sai da tabela no
 * {@link #complete}, depois de o resultado gravado — um consumidor que morre no meio deixa o lease
 * vencer e a invocação volta para a fila em vez de sumir (migration {@code V6_13}).</p>
 *
 * <p>"Livre" é sem lease ou com lease vencido. O {@code poll()} destrutivo continua existindo (é o
 * contrato antigo da interface), mas também só enxerga linhas livres: retirar uma invocação que outro
 * consumidor está executando a faria rodar duas vezes.</p>
 *
 * <p>O relógio é o da aplicação, nos dois lados (gravar e comparar o lease), para que réplicas
 * concordem sem depender do fuso/relógio do banco; a folga do lease (minutos) absorve o desvio entre
 * réplicas.</p>
 */
public class JdbcAgentInvocationQueue implements AgentInvocationQueue {

    private static final Logger logger = Logger.getLogger(JdbcAgentInvocationQueue.class.getName());
    private static final int DEFAULT_MAX_RECURSION_DEPTH = 10;
    private static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    private static final int CLAIM_ATTEMPTS = 3;

    private static final String COLUMNS =
            "id, tenant_id, agent_id, payload, parent_execution_id, recursion_depth, created_at";
    private static final String FREE = "(claimed_until IS NULL OR claimed_until < ?)";

    private final DataSource dataSource;
    private final int maxRecursionDepth;
    private final int queueCapacity;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcAgentInvocationQueue(DataSource dataSource) {
        this(dataSource, DEFAULT_MAX_RECURSION_DEPTH, DEFAULT_QUEUE_CAPACITY);
    }

    public JdbcAgentInvocationQueue(DataSource dataSource, int maxRecursionDepth, int queueCapacity) {
        this(dataSource, maxRecursionDepth, queueCapacity, Clock.systemUTC());
    }

    /** @param clock relógio dos leases; os testes o avançam para simular um consumidor que morreu */
    public JdbcAgentInvocationQueue(DataSource dataSource, int maxRecursionDepth, int queueCapacity,
                                    Clock clock) {
        this.dataSource = dataSource;
        this.maxRecursionDepth = maxRecursionDepth;
        this.queueCapacity = queueCapacity;
        this.clock = clock;
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
     * ou seja, a fila está cheia. Para no limite ({@code LIMIT 1 OFFSET cap} — nessa
     * ordem, que o H2 também aceita), sem varrer a tabela inteira como faria um
     * {@code COUNT(*)}.
     */
    private boolean atCapacity(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM agent_invocations LIMIT 1 OFFSET ?")) {
            ps.setInt(1, queueCapacity);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public Optional<InvocationRequest> poll() {
        String selectSql = "SELECT " + COLUMNS + " FROM agent_invocations WHERE " + FREE
                + " ORDER BY created_at, id LIMIT 1";
        String deleteSql = "DELETE FROM agent_invocations WHERE id = ? AND " + FREE;

        try (Connection conn = dataSource.getConnection()) {
            for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
                Timestamp now = Timestamp.from(clock.instant());
                InvocationRequest candidate;
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    ps.setTimestamp(1, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.empty();
                        }
                        candidate = mapRow(rs);
                    }
                }
                try (PreparedStatement del = conn.prepareStatement(deleteSql)) {
                    del.setString(1, candidate.requestId());
                    del.setTimestamp(2, now);
                    if (del.executeUpdate() == 1) {
                        return Optional.of(candidate);
                    }
                    // Outra instância consumiu (ou reivindicou) esta requisição; tenta a próxima.
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new RuntimeException("Failed to poll agent invocation queue", e);
        }
    }

    @Override
    public Optional<InvocationRequest> claim(String consumerId, Duration lease) {
        String selectSql = "SELECT " + COLUMNS + " FROM agent_invocations WHERE " + FREE
                + " ORDER BY created_at, id LIMIT 1";
        String claimSql = "UPDATE agent_invocations SET claimed_by = ?, claimed_until = ? WHERE id = ? AND "
                + FREE;

        try (Connection conn = dataSource.getConnection()) {
            for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
                Instant now = clock.instant();
                InvocationRequest candidate;
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    ps.setTimestamp(1, Timestamp.from(now));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.empty();
                        }
                        candidate = mapRow(rs);
                    }
                }
                // O UPDATE condicional é o claim: só um consumidor vê "1 linha" para o mesmo lease livre.
                try (PreparedStatement up = conn.prepareStatement(claimSql)) {
                    up.setString(1, consumerId);
                    up.setTimestamp(2, Timestamp.from(now.plus(lease)));
                    up.setString(3, candidate.requestId());
                    up.setTimestamp(4, Timestamp.from(now));
                    if (up.executeUpdate() == 1) {
                        return Optional.of(candidate);
                    }
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new RuntimeException("Failed to claim agent invocation", e);
        }
    }

    @Override
    public boolean renew(String requestId, String consumerId, Duration lease) {
        // Só o dono do lease renova: quem perdeu o lease não pode tomá-lo de volta de quem o pegou.
        String sql = "UPDATE agent_invocations SET claimed_until = ? WHERE id = ? AND claimed_by = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(clock.instant().plus(lease)));
            ps.setString(2, requestId);
            ps.setString(3, consumerId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to renew lease of agent invocation " + requestId, e);
        }
    }

    @Override
    public void complete(String requestId, String consumerId) {
        // Por id e dono: se o lease venceu e outro consumidor reivindicou, a linha é dele agora.
        String sql = "DELETE FROM agent_invocations WHERE id = ? AND claimed_by = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestId);
            ps.setString(2, consumerId);
            if (ps.executeUpdate() == 0) {
                logger.warning(() -> "Invocation " + requestId + " completed by " + consumerId
                        + " but its lease belongs to another consumer (or it was already removed)");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to complete agent invocation " + requestId, e);
        }
    }

    @Override
    public void release(String requestId, String consumerId) {
        // Por dono, como o complete: se o lease já venceu e outra réplica a pegou, não é mais nossa.
        String sql = "UPDATE agent_invocations SET claimed_by = NULL, claimed_until = NULL "
                + "WHERE id = ? AND claimed_by = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestId);
            ps.setString(2, consumerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to release agent invocation " + requestId, e);
        }
    }

    @Override
    public Optional<InvocationRequest> find(String requestId) {
        String sql = "SELECT " + COLUMNS + " FROM agent_invocations WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to find agent invocation " + requestId, e);
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

    /** As requisições livres (sem lease ou com lease vencido); as em execução não estão "pendentes". */
    @Override
    public List<InvocationRequest> pending() {
        String sql = "SELECT " + COLUMNS + " FROM agent_invocations WHERE " + FREE + " ORDER BY created_at, id";
        List<InvocationRequest> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(clock.instant()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
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
