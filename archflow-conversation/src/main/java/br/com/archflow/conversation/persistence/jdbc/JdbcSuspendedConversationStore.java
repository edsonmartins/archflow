package br.com.archflow.conversation.persistence.jdbc;

import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.state.SuspendedConversation;
import br.com.archflow.conversation.state.SuspendedConversationStore;
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
import java.util.Map;
import java.util.Optional;

/**
 * Implementação JDBC do {@link SuspendedConversationStore} — conversas suspensas
 * persistidas na tabela {@code suspended_conversations} (ver migration
 * {@code V2_2__create_suspended_conversations.sql}), para que suspend/resume
 * sobreviva a restart.
 *
 * <p>O {@link FormData} e o {@code context} são serializados como JSON em coluna
 * TEXT (o {@code FormData} é round-trippável via {@code @JsonCreator}). SQL ANSI
 * portável, upsert por {@code conversation_id} (update-first, insert-if-zero).
 */
public class JdbcSuspendedConversationStore implements SuspendedConversationStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSuspendedConversationStore.class);

    private final DataSource dataSource;

    public JdbcSuspendedConversationStore(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public void save(SuspendedConversation c) {
        String updateSql = """
            UPDATE suspended_conversations SET resume_token = ?, workflow_id = ?,
                   workflow_execution_id = ?, form = ?, context = ?, status = ?,
                   priority = ?, created_at = ?, expires_at = ?
            WHERE conversation_id = ?
            """;
        String insertSql = """
            INSERT INTO suspended_conversations (conversation_id, resume_token, workflow_id,
                   workflow_execution_id, form, context, status, priority, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, c.getResumeToken());
                ps.setString(2, c.getWorkflowId());
                ps.setString(3, c.getWorkflowExecutionId());
                ps.setString(4, serializeForm(c.getForm()));
                ps.setString(5, JdbcSupport.toJson(c.getContext()));
                ps.setString(6, c.getStatus().name());
                ps.setInt(7, c.getPriority());
                ps.setTimestamp(8, timestamp(c.getCreatedAt()));
                ps.setTimestamp(9, timestamp(c.getExpiresAt()));
                ps.setString(10, c.getConversationId());
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, c.getConversationId());
                    ps.setString(2, c.getResumeToken());
                    ps.setString(3, c.getWorkflowId());
                    ps.setString(4, c.getWorkflowExecutionId());
                    ps.setString(5, serializeForm(c.getForm()));
                    ps.setString(6, JdbcSupport.toJson(c.getContext()));
                    ps.setString(7, c.getStatus().name());
                    ps.setInt(8, c.getPriority());
                    ps.setTimestamp(9, timestamp(c.getCreatedAt()));
                    ps.setTimestamp(10, timestamp(c.getExpiresAt()));
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.error("Failed to save suspended conversation {}", c.getConversationId(), e);
            throw new RuntimeException("Failed to save suspended conversation " + c.getConversationId(), e);
        }
    }

    @Override
    public Optional<SuspendedConversation> findByToken(String resumeToken) {
        return queryOne("SELECT * FROM suspended_conversations WHERE resume_token = ?", resumeToken);
    }

    @Override
    public Optional<SuspendedConversation> findById(String conversationId) {
        return queryOne("SELECT * FROM suspended_conversations WHERE conversation_id = ?", conversationId);
    }

    private Optional<SuspendedConversation> queryOne(String sql, String value) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to load suspended conversation ({})", sql, e);
            throw new RuntimeException("Failed to load suspended conversation", e);
        }
    }

    @Override
    public boolean deleteById(String conversationId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM suspended_conversations WHERE conversation_id = ?")) {
            ps.setString(1, conversationId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete suspended conversation {}", conversationId, e);
            throw new RuntimeException("Failed to delete suspended conversation " + conversationId, e);
        }
    }

    @Override
    public List<SuspendedConversation> findAll() {
        List<SuspendedConversation> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM suspended_conversations");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list suspended conversations", e);
            throw new RuntimeException("Failed to list suspended conversations", e);
        }
        return result;
    }

    private SuspendedConversation map(ResultSet rs) throws SQLException {
        return SuspendedConversation.builder()
                .conversationId(rs.getString("conversation_id"))
                .resumeToken(rs.getString("resume_token"))
                .workflowId(rs.getString("workflow_id"))
                .workflowExecutionId(rs.getString("workflow_execution_id"))
                .form(deserializeForm(rs.getString("form")))
                .context(JdbcSupport.fromJson(rs.getString("context")))
                .status(SuspendedConversation.ConversationStatus.valueOf(rs.getString("status")))
                .priority(rs.getInt("priority"))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .expiresAt(instant(rs.getTimestamp("expires_at")))
                .build();
    }

    private static String serializeForm(FormData form) {
        if (form == null) {
            return null;
        }
        try {
            return JdbcSupport.MAPPER.writeValueAsString(form);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize form for suspended conversation", e);
        }
    }

    private static FormData deserializeForm(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JdbcSupport.MAPPER.readValue(json, FormData.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize form for suspended conversation", e);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
