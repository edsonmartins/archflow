package br.com.archflow.observability.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC-based implementation of AuditRepository for persistent audit logging.
 *
 * <p>This implementation stores audit events in a relational database.
 * Requires the af_audit_log table to be created (see V4_1__CreateAuditLogTable.sql).
 *
 * <p>Usage:</p>
 * <pre>
 * DataSource dataSource = ...; // HikariCP or other
 * AuditRepository repository = new JdbcAuditRepository(dataSource);
 * AuditLogger.initialize(repository);
 * </pre>
 */
public class JdbcAuditRepository implements AuditRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditRepository.class);

    /**
     * Tolera caractere de controle cru dentro de string: as linhas gravadas antes de
     * {@link #escapeJson} escapar todos eles (um nome de fluxo colado com U+000B, por
     * exemplo) não são JSON estrito, e sem isto o contexto inteiro voltava em {@link #RAW_CONTEXT_KEY}
     * em vez das chaves separadas.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    /** A chave em que um contexto que não é JSON volta inteiro (ver {@link #deserializeContext}). */
    public static final String RAW_CONTEXT_KEY = "_raw";

    private static final String INSERT_SQL =
            "INSERT INTO af_audit_log (id, timestamp, action_code, success, user_id, username, " +
            "resource_type, resource_id, error_message, ip_address, user_agent, session_id, " +
            "trace_id, context, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_BY_ID_SQL =
            "SELECT id, timestamp, action_code, success, user_id, username, resource_type, " +
            "resource_id, error_message, ip_address, user_agent, session_id, trace_id, context " +
            "FROM af_audit_log WHERE id = ?";

    private static final String SELECT_BY_USER_SQL =
            "SELECT id, timestamp, action_code, success, user_id, username, resource_type, " +
            "resource_id, error_message, ip_address, user_agent, session_id, trace_id, context " +
            "FROM af_audit_log WHERE user_id = ? ORDER BY timestamp DESC LIMIT ?";

    private static final String SELECT_BY_ACTION_SQL =
            "SELECT id, timestamp, action_code, success, user_id, username, resource_type, " +
            "resource_id, error_message, ip_address, user_agent, session_id, trace_id, context " +
            "FROM af_audit_log WHERE action_code = ? ORDER BY timestamp DESC LIMIT ?";

    private static final String SELECT_BY_RESOURCE_SQL =
            "SELECT id, timestamp, action_code, success, user_id, username, resource_type, " +
            "resource_id, error_message, ip_address, user_agent, session_id, trace_id, context " +
            "FROM af_audit_log WHERE resource_type = ? AND resource_id = ? ORDER BY timestamp DESC LIMIT ?";

    private static final String SELECT_BY_TIME_RANGE_SQL =
            "SELECT id, timestamp, action_code, success, user_id, username, resource_type, " +
            "resource_id, error_message, ip_address, user_agent, session_id, trace_id, context " +
            "FROM af_audit_log WHERE timestamp >= ? AND timestamp < ? ORDER BY timestamp DESC LIMIT ?";

    private static final String DELETE_OLDER_THAN_SQL =
            "DELETE FROM af_audit_log WHERE timestamp < ?";

    private static final String COUNT_QUERY_SQL =
            "SELECT COUNT(*) FROM af_audit_log WHERE 1=1";

    private static final String SELECT_QUERY_SQL =
            "SELECT id, timestamp, action_code, success, user_id, username, resource_type, " +
            "resource_id, error_message, ip_address, user_agent, session_id, trace_id, context " +
            "FROM af_audit_log WHERE 1=1";

    private final DataSource dataSource;

    public JdbcAuditRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
    }

    @Override
    public void save(AuditEvent event) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {

            ps.setString(1, event.getId());
            ps.setTimestamp(2, Timestamp.from(event.getTimestamp()));
            ps.setString(3, event.getAction().getCode());
            ps.setBoolean(4, event.isSuccess());
            ps.setString(5, event.getUserId());
            ps.setString(6, event.getUsername());
            ps.setString(7, event.getResourceType());
            ps.setString(8, event.getResourceId());
            ps.setString(9, event.getErrorMessage());
            ps.setString(10, event.getIpAddress());
            ps.setString(11, event.getUserAgent());
            ps.setString(12, event.getSessionId());
            ps.setString(13, event.getTraceId());
            ps.setString(14, serializeContext(event.getContext()));
            ps.setTimestamp(15, Timestamp.from(Instant.now()));

            ps.executeUpdate();
            log.debug("Saved audit event: {}", event.getId());

        } catch (SQLException e) {
            log.error("Failed to save audit event: {}", event.getId(), e);
            throw new RuntimeException("Failed to save audit event", e);
        }
    }

    @Override
    public void saveAll(List<AuditEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {

            for (AuditEvent event : events) {
                ps.setString(1, event.getId());
                ps.setTimestamp(2, Timestamp.from(event.getTimestamp()));
                ps.setString(3, event.getAction().getCode());
                ps.setBoolean(4, event.isSuccess());
                ps.setString(5, event.getUserId());
                ps.setString(6, event.getUsername());
                ps.setString(7, event.getResourceType());
                ps.setString(8, event.getResourceId());
                ps.setString(9, event.getErrorMessage());
                ps.setString(10, event.getIpAddress());
                ps.setString(11, event.getUserAgent());
                ps.setString(12, event.getSessionId());
                ps.setString(13, event.getTraceId());
                ps.setString(14, serializeContext(event.getContext()));
                ps.setTimestamp(15, Timestamp.from(Instant.now()));
                ps.addBatch();
            }

            ps.executeBatch();
            log.debug("Saved {} audit events", events.size());

        } catch (SQLException e) {
            log.error("Failed to save audit events", e);
            throw new RuntimeException("Failed to save audit events", e);
        }
    }

    @Override
    public Optional<AuditEvent> findById(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID_SQL)) {

            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                return Optional.of(mapRowToEvent(rs));
            }
            return Optional.empty();

        } catch (SQLException e) {
            log.error("Failed to find audit event by id: {}", id, e);
            throw new RuntimeException("Failed to find audit event", e);
        }
    }

    @Override
    public List<AuditEvent> findByUserId(String userId, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_USER_SQL)) {

            ps.setString(1, userId);
            ps.setInt(2, limit > 0 ? limit : Integer.MAX_VALUE);

            return mapRowsToList(ps);

        } catch (SQLException e) {
            log.error("Failed to find audit events by user: {}", userId, e);
            throw new RuntimeException("Failed to find audit events", e);
        }
    }

    @Override
    public List<AuditEvent> findByAction(AuditAction action, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_ACTION_SQL)) {

            ps.setString(1, action.getCode());
            ps.setInt(2, limit > 0 ? limit : Integer.MAX_VALUE);

            return mapRowsToList(ps);

        } catch (SQLException e) {
            log.error("Failed to find audit events by action: {}", action, e);
            throw new RuntimeException("Failed to find audit events", e);
        }
    }

    @Override
    public List<AuditEvent> findByResource(String resourceType, String resourceId, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_RESOURCE_SQL)) {

            ps.setString(1, resourceType);
            ps.setString(2, resourceId);
            ps.setInt(3, limit > 0 ? limit : Integer.MAX_VALUE);

            return mapRowsToList(ps);

        } catch (SQLException e) {
            log.error("Failed to find audit events by resource: {}/{}", resourceType, resourceId, e);
            throw new RuntimeException("Failed to find audit events", e);
        }
    }

    @Override
    public List<AuditEvent> findByTimeRange(Instant start, Instant end, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_TIME_RANGE_SQL)) {

            ps.setTimestamp(1, Timestamp.from(start));
            ps.setTimestamp(2, Timestamp.from(end));
            ps.setInt(3, limit > 0 ? limit : Integer.MAX_VALUE);

            return mapRowsToList(ps);

        } catch (SQLException e) {
            log.error("Failed to find audit events by time range", e);
            throw new RuntimeException("Failed to find audit events", e);
        }
    }

    @Override
    public List<AuditEvent> query(AuditQuery query) {
        StringBuilder sql = new StringBuilder(SELECT_QUERY_SQL);
        List<Object> params = new ArrayList<>();

        if (query.getUserId() != null) {
            sql.append(" AND user_id = ?");
            params.add(query.getUserId());
        }
        if (query.getAction() != null) {
            sql.append(" AND action_code = ?");
            params.add(query.getAction().getCode());
        }
        if (query.getResourceType() != null) {
            sql.append(" AND resource_type = ?");
            params.add(query.getResourceType());
        }
        if (query.getResourceId() != null) {
            sql.append(" AND resource_id = ?");
            params.add(query.getResourceId());
        }
        if (query.getSuccess() != null) {
            sql.append(" AND success = ?");
            params.add(query.getSuccess());
        }
        if (query.getStartTime() != null) {
            sql.append(" AND timestamp >= ?");
            params.add(Timestamp.from(query.getStartTime()));
        }
        if (query.getEndTime() != null) {
            sql.append(" AND timestamp < ?");
            params.add(Timestamp.from(query.getEndTime()));
        }
        if (query.getIpAddress() != null) {
            sql.append(" AND ip_address = ?");
            params.add(query.getIpAddress());
        }
        if (query.getActor() != null) {
            sql.append(" AND (user_id = ? OR username = ?)");
            params.add(query.getActor());
            params.add(query.getActor());
        }
        appendContextFilter(query, sql, params);

        // Sort
        sql.append(" ORDER BY ").append(query.getSortBy());
        sql.append(query.isSortDescending() ? " DESC" : " ASC");
        // Desempate pelo id: sem ele, linhas com o mesmo timestamp não têm ordem definida e quem lê
        // por páginas (LIMIT/OFFSET — a tela e o export do CSV) pode repetir uma e pular outra.
        if (!"id".equals(query.getSortBy())) {
            sql.append(", id").append(query.isSortDescending() ? " DESC" : " ASC");
        }

        // Limit
        sql.append(" LIMIT ?");
        params.add(query.getLimit());

        // Offset
        if (query.getOffset() > 0) {
            sql.append(" OFFSET ?");
            params.add(query.getOffset());
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            return mapRowsToList(ps);

        } catch (SQLException e) {
            log.error("Failed to query audit events", e);
            throw new RuntimeException("Failed to query audit events", e);
        }
    }

    @Override
    public long count(AuditQuery query) {
        StringBuilder sql = new StringBuilder(COUNT_QUERY_SQL);
        List<Object> params = new ArrayList<>();

        if (query.getUserId() != null) {
            sql.append(" AND user_id = ?");
            params.add(query.getUserId());
        }
        if (query.getAction() != null) {
            sql.append(" AND action_code = ?");
            params.add(query.getAction().getCode());
        }
        if (query.getResourceType() != null) {
            sql.append(" AND resource_type = ?");
            params.add(query.getResourceType());
        }
        if (query.getResourceId() != null) {
            sql.append(" AND resource_id = ?");
            params.add(query.getResourceId());
        }
        if (query.getSuccess() != null) {
            sql.append(" AND success = ?");
            params.add(query.getSuccess());
        }
        if (query.getStartTime() != null) {
            sql.append(" AND timestamp >= ?");
            params.add(Timestamp.from(query.getStartTime()));
        }
        if (query.getEndTime() != null) {
            sql.append(" AND timestamp < ?");
            params.add(Timestamp.from(query.getEndTime()));
        }
        if (query.getIpAddress() != null) {
            sql.append(" AND ip_address = ?");
            params.add(query.getIpAddress());
        }
        if (query.getActor() != null) {
            sql.append(" AND (user_id = ? OR username = ?)");
            params.add(query.getActor());
            params.add(query.getActor());
        }
        appendContextFilter(query, sql, params);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;

        } catch (SQLException e) {
            log.error("Failed to count audit events", e);
            throw new RuntimeException("Failed to count audit events", e);
        }
    }

    @Override
    public long deleteOlderThan(Instant before) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_OLDER_THAN_SQL)) {

            ps.setTimestamp(1, Timestamp.from(before));
            int deleted = ps.executeUpdate();
            log.info("Deleted {} audit events older than {}", deleted, before);
            return deleted;

        } catch (SQLException e) {
            log.error("Failed to delete old audit events", e);
            throw new RuntimeException("Failed to delete audit events", e);
        }
    }

    // ========== Helper Methods ==========

    private List<AuditEvent> mapRowsToList(PreparedStatement ps) throws SQLException {
        List<AuditEvent> events = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                events.add(mapRowToEvent(rs));
            }
        }
        return events;
    }

    private AuditEvent mapRowToEvent(ResultSet rs) throws SQLException {
        AuditAction action = AuditAction.fromCode(rs.getString("action_code"));
        if (action == null) {
            action = AuditAction.CREATE; // fallback
        }

        return AuditEvent.builder()
                .id(rs.getString("id"))
                .timestamp(rs.getTimestamp("timestamp").toInstant())
                .action(action)
                .success(rs.getBoolean("success"))
                .userId(rs.getString("user_id"))
                .username(rs.getString("username"))
                .resourceType(rs.getString("resource_type"))
                .resourceId(rs.getString("resource_id"))
                .errorMessage(rs.getString("error_message"))
                .ipAddress(rs.getString("ip_address"))
                .userAgent(rs.getString("user_agent"))
                .sessionId(rs.getString("session_id"))
                .traceId(rs.getString("trace_id"))
                .context(deserializeContext(rs.getString("context")))
                .build();
    }

    /**
     * O inverso de {@link #serializeContext}. Antes a leitura nem olhava a coluna: o tenant, o
     * {@code executionId} e o nome gravados na auditoria voltavam como um contexto vazio, e a API, a
     * tela e o CSV não mostravam a quem o evento pertencia (RCHFLW-164). Um valor que não seja texto
     * (linha escrita por outro produtor) volta como o texto do JSON, em vez de sumir.
     */
    static Map<String, String> deserializeContext(String json) {
        Map<String, String> context = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return context;
        }
        try {
            JsonNode node = JSON.readTree(json);
            node.properties().forEach(campo -> context.put(campo.getKey(),
                    campo.getValue().isTextual() ? campo.getValue().textValue() : campo.getValue().toString()));
        } catch (JsonProcessingException e) {
            // A linha continua no banco; aqui só não dá para separar as chaves. O texto inteiro volta
            // numa chave só, para a leitura não esconder o que foi gravado.
            log.warn("Audit context is not valid JSON; returning it unparsed: {}", e.getOriginalMessage());
            context.put(RAW_CONTEXT_KEY, json);
        }
        return context;
    }

    /**
     * O contexto é gravado como o JSON plano de {@link #serializeContext} numa coluna TEXT, sem
     * coluna própria para o tenant; o filtro procura o par {@code "chave":"valor"} exatamente como
     * esse serializador o escreve. Aspas dentro de outro valor saem escapadas ({@code \"}), então o
     * par não casa com um pedaço de outro campo. Os curingas do LIKE ({@code %}, {@code _}) são
     * escapados — um tenant como {@code tenant_a} não pode casar com {@code tenantXa}.
     */
    private void appendContextFilter(AuditQuery query, StringBuilder sql, List<Object> params) {
        if (query.getContextKey() == null) {
            return;
        }
        String par = "\"" + escapeJson(query.getContextKey()) + "\":\""
                + escapeJson(query.getContextValue()) + "\"";
        sql.append(" AND context LIKE ? ESCAPE '!'");
        params.add("%" + escapeLike(par) + "%");
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private String serializeContext(java.util.Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        // Simple JSON serialization
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, String> entry : context.entrySet()) {
            if (!first) {
                json.append(",");
            }
            json.append("\"").append(escapeJson(entry.getKey())).append("\":");
            json.append("\"").append(escapeJson(entry.getValue())).append("\"");
            first = false;
        }
        json.append("}");
        return json.toString();
    }

    /**
     * Escapa como o JSON exige: além de barra, aspas, {@code \n}, {@code \r} e {@code \t}, todo
     * caractere abaixo de 0x20 vira o escape unicode (barra, u, quatro hex) — cru, ele deixa a coluna
     * fora do JSON estrito. O filtro por contexto ({@code contextKey/contextValue}) usa este mesmo
     * método para montar o padrão, então gravação e busca continuam casando.
     */
    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
