package br.com.archflow.api.triggers.store;

import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ScheduledTriggerStore} durável sobre a tabela {@code scheduled_triggers} (migration
 * {@code V6_15__create_scheduled_triggers.sql}), par do Quartz em {@code JobStoreTX}: os dois sobrevivem
 * ao restart juntos (RCHFLW-187).
 *
 * <p>A troca de configuração é um {@code UPDATE ... WHERE updated_at = ?}: a versão é conferida pelo
 * banco, na mesma instrução que grava, e vale também entre réplicas. O payload vai como texto JSON, como em
 * {@code agent_invocations}.</p>
 */
public class JdbcScheduledTriggerStore implements ScheduledTriggerStore {

    /** SQLState de violação de chave única (PostgreSQL e H2). */
    private static final String UNIQUE_VIOLATION = "23505";
    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() { };
    private static final String COLUMNS = "id, name, cron_expression, tenant_id, agent_id, payload, enabled, "
            + "created_at, updated_at, last_fired_at, last_error, last_request_id, last_status";

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcScheduledTriggerStore(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public List<ScheduledTriggerDto> list() {
        String sql = "SELECT " + COLUMNS + " FROM scheduled_triggers ORDER BY created_at, id";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<ScheduledTriggerDto> out = new ArrayList<>();
            while (rs.next()) {
                out.add(read(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list scheduled triggers: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ScheduledTriggerDto> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        try (Connection conn = dataSource.getConnection()) {
            return find(conn, id);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read scheduled trigger " + id + ": " + e.getMessage(), e);
        }
    }

    private Optional<ScheduledTriggerDto> find(Connection conn, String id) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM scheduled_triggers WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public boolean insert(ScheduledTriggerDto t) {
        String sql = "INSERT INTO scheduled_triggers (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.id());
            ps.setString(2, t.name());
            ps.setString(3, t.cronExpression());
            ps.setString(4, t.tenantId());
            ps.setString(5, t.agentId());
            ps.setString(6, json(t.payload()));
            ps.setBoolean(7, t.enabled());
            ps.setTimestamp(8, Timestamp.from(t.createdAt()));
            ps.setTimestamp(9, Timestamp.from(t.updatedAt() != null ? t.updatedAt() : t.createdAt()));
            ps.setTimestamp(10, timestamp(t.lastFiredAt()));
            ps.setString(11, t.lastError());
            ps.setString(12, t.lastRequestId());
            ps.setString(13, t.lastStatus());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return false;
            }
            throw new IllegalStateException("Failed to save scheduled trigger " + t.id() + ": " + e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>O {@code UPDATE} e a releitura do gatilho gravado vão na mesma transação. Antes a releitura vinha
     * depois do commit: se ela falhava, o método lançava com a edição já gravada, e o controller devolvia ao
     * Quartz o cron anterior — banco com o cron novo, Quartz com o velho, e o cliente recebendo erro de uma
     * edição salva. Agora a falha da releitura desfaz o {@code UPDATE}: lançar quer dizer "nada gravado".</p>
     */
    @Override
    public Optional<ScheduledTriggerDto> updateConfig(ScheduledTriggerDto c, Instant expectedUpdatedAt) {
        String sql = "UPDATE scheduled_triggers SET name = ?, cron_expression = ?, tenant_id = ?, agent_id = ?, "
                + "payload = ?, enabled = ?, updated_at = ? WHERE id = ? AND updated_at = ?";
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int linhas;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, c.name());
                    ps.setString(2, c.cronExpression());
                    ps.setString(3, c.tenantId());
                    ps.setString(4, c.agentId());
                    ps.setString(5, json(c.payload()));
                    ps.setBoolean(6, c.enabled());
                    ps.setTimestamp(7, Timestamp.from(c.updatedAt()));
                    ps.setString(8, c.id());
                    ps.setTimestamp(9, timestamp(expectedUpdatedAt));
                    linhas = ps.executeUpdate();
                }
                if (linhas == 0) {
                    conn.rollback();
                    return Optional.empty();
                }
                Optional<ScheduledTriggerDto> gravado = find(conn, c.id());
                conn.commit();
                return gravado;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update scheduled trigger " + c.id() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM scheduled_triggers WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete scheduled trigger " + id + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void recordLastRun(String id, Instant firedAt, String error, String requestId, String status) {
        String sql = "UPDATE scheduled_triggers SET last_fired_at = ?, last_error = ?, last_request_id = ?, "
                + "last_status = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, timestamp(firedAt));
            ps.setString(2, error);
            ps.setString(3, requestId);
            ps.setString(4, status);
            ps.setString(5, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record the last run of trigger " + id + ": " + e.getMessage(),
                    e);
        }
    }

    @Override
    public void recordOutcome(String id, String requestId, String status, String error) {
        String sql = "UPDATE scheduled_triggers SET last_status = ?, last_error = ? "
                + "WHERE id = ? AND last_request_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, error);
            ps.setString(3, id);
            ps.setString(4, requestId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record the outcome of trigger " + id + ": " + e.getMessage(),
                    e);
        }
    }

    private ScheduledTriggerDto read(ResultSet rs) throws SQLException {
        return new ScheduledTriggerDto(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("cron_expression"),
                rs.getString("tenant_id"),
                rs.getString("agent_id"),
                payload(rs.getString("payload")),
                rs.getBoolean("enabled"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("last_fired_at")),
                rs.getString("last_error"),
                rs.getString("last_request_id"),
                rs.getString("last_status"),
                instant(rs.getTimestamp("updated_at")),
                null,
                null);
    }

    private String json(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload is not serialisable as JSON: " + e.getOriginalMessage(), e);
        }
    }

    private Map<String, Object> payload(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : mapper.readValue(json, PAYLOAD);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored payload of a scheduled trigger is not valid JSON", e);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
