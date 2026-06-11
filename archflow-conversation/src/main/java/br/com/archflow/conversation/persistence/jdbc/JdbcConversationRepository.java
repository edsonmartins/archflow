package br.com.archflow.conversation.persistence.jdbc;

import br.com.archflow.conversation.domain.Conversation;
import br.com.archflow.conversation.domain.ConversationRepository;
import br.com.archflow.conversation.domain.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementação JDBC do {@link ConversationRepository} — conversas e mensagens
 * persistidas nas tabelas {@code conversations} e {@code conversation_messages}
 * (ver migration {@code V001__create_conversations.sql}).
 *
 * <p>Todas as consultas filtram por {@code tenant_id}, preservando o contrato
 * de isolamento da interface. SQL ANSI portável (PostgreSQL, H2, MySQL);
 * metadados serializados como JSON em coluna TEXT.
 */
public class JdbcConversationRepository implements ConversationRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcConversationRepository.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcConversationRepository(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        this.dataSource = dataSource;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    // ── Conversation ────────────────────────────────────────────────

    @Override
    public Conversation save(Conversation conversation) {
        String updateSql = """
            UPDATE conversations SET user_id = ?, channel = ?, status = ?, persona = ?,
                   metadata = ?, updated_at = ?
            WHERE id = ? AND tenant_id = ?
            """;
        String insertSql = """
            INSERT INTO conversations (id, tenant_id, user_id, channel, status, persona,
                                       metadata, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, conversation.userId());
                ps.setString(2, conversation.channel());
                ps.setString(3, conversation.status().name());
                ps.setString(4, conversation.persona());
                ps.setString(5, toJson(conversation.metadata()));
                ps.setTimestamp(6, Timestamp.from(conversation.updatedAt()));
                ps.setString(7, conversation.id());
                ps.setString(8, conversation.tenantId());
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, conversation.id());
                    ps.setString(2, conversation.tenantId());
                    ps.setString(3, conversation.userId());
                    ps.setString(4, conversation.channel());
                    ps.setString(5, conversation.status().name());
                    ps.setString(6, conversation.persona());
                    ps.setString(7, toJson(conversation.metadata()));
                    ps.setTimestamp(8, Timestamp.from(conversation.createdAt()));
                    ps.setTimestamp(9, Timestamp.from(conversation.updatedAt()));
                    ps.executeUpdate();
                }
            }
            return conversation;

        } catch (SQLException e) {
            log.error("Failed to save conversation {} (tenant {})",
                    conversation.id(), conversation.tenantId(), e);
            throw new RuntimeException("Failed to save conversation " + conversation.id(), e);
        }
    }

    @Override
    public Optional<Conversation> findById(String tenantId, String conversationId) {
        String sql = "SELECT * FROM conversations WHERE id = ? AND tenant_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, conversationId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapConversation(rs)) : Optional.empty();
            }

        } catch (SQLException e) {
            log.error("Failed to load conversation {} (tenant {})", conversationId, tenantId, e);
            throw new RuntimeException("Failed to load conversation " + conversationId, e);
        }
    }

    @Override
    public List<Conversation> listByTenant(String tenantId) {
        return queryConversations(
                "SELECT * FROM conversations WHERE tenant_id = ? ORDER BY updated_at DESC",
                ps -> ps.setString(1, tenantId));
    }

    @Override
    public List<Conversation> listByUser(String tenantId, String userId) {
        return queryConversations(
                "SELECT * FROM conversations WHERE tenant_id = ? AND user_id = ? ORDER BY updated_at DESC",
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, userId);
                });
    }

    @Override
    public boolean delete(String tenantId, String conversationId) {
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM conversation_messages WHERE conversation_id = ? AND tenant_id = ?")) {
                    ps.setString(1, conversationId);
                    ps.setString(2, tenantId);
                    ps.executeUpdate();
                }
                int deleted;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM conversations WHERE id = ? AND tenant_id = ?")) {
                    ps.setString(1, conversationId);
                    ps.setString(2, tenantId);
                    deleted = ps.executeUpdate();
                }
                conn.commit();
                return deleted > 0;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }

        } catch (SQLException e) {
            log.error("Failed to delete conversation {} (tenant {})", conversationId, tenantId, e);
            throw new RuntimeException("Failed to delete conversation " + conversationId, e);
        }
    }

    // ── Message ─────────────────────────────────────────────────────

    @Override
    public Message addMessage(Message message) {
        String sql = """
            INSERT INTO conversation_messages (id, conversation_id, tenant_id, sender_type,
                                               message_type, content, media_url, metadata, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, message.id());
            ps.setString(2, message.conversationId());
            ps.setString(3, message.tenantId());
            ps.setString(4, message.senderType().name());
            ps.setString(5, message.messageType().name());
            ps.setString(6, message.content());
            ps.setString(7, message.mediaUrl());
            ps.setString(8, toJson(message.metadata()));
            ps.setTimestamp(9, Timestamp.from(message.timestamp()));
            ps.executeUpdate();
            return message;

        } catch (SQLException e) {
            log.error("Failed to add message {} to conversation {}",
                    message.id(), message.conversationId(), e);
            throw new RuntimeException("Failed to add message " + message.id(), e);
        }
    }

    @Override
    public List<Message> listMessages(String tenantId, String conversationId) {
        return queryMessages("""
                SELECT * FROM conversation_messages
                WHERE tenant_id = ? AND conversation_id = ?
                ORDER BY created_at ASC, id ASC
                """,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, conversationId);
                });
    }

    @Override
    public List<Message> listRecentMessages(String tenantId, String conversationId, int limit) {
        List<Message> recentFirst = queryMessages("""
                SELECT * FROM conversation_messages
                WHERE tenant_id = ? AND conversation_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """,
                ps -> {
                    ps.setString(1, tenantId);
                    ps.setString(2, conversationId);
                    ps.setInt(3, limit);
                });
        Collections.reverse(recentFirst); // chronological order, like the in-memory impl
        return recentFirst;
    }

    @Override
    public int countMessages(String tenantId, String conversationId) {
        String sql = "SELECT COUNT(*) FROM conversation_messages WHERE tenant_id = ? AND conversation_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, tenantId);
            ps.setString(2, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }

        } catch (SQLException e) {
            log.error("Failed to count messages of conversation {} (tenant {})",
                    conversationId, tenantId, e);
            throw new RuntimeException("Failed to count messages of " + conversationId, e);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ParameterBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Conversation> queryConversations(String sql, ParameterBinder binder) {
        List<Conversation> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapConversation(rs));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to query conversations", e);
            throw new RuntimeException("Failed to query conversations", e);
        }
    }

    private List<Message> queryMessages(String sql, ParameterBinder binder) {
        List<Message> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapMessage(rs));
                }
            }
            return results;

        } catch (SQLException e) {
            log.error("Failed to query messages", e);
            throw new RuntimeException("Failed to query messages", e);
        }
    }

    private Conversation mapConversation(ResultSet rs) throws SQLException {
        return new Conversation(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("channel"),
                Conversation.ConversationStatus.valueOf(rs.getString("status")),
                rs.getString("persona"),
                fromJson(rs.getString("metadata")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Message mapMessage(ResultSet rs) throws SQLException {
        return new Message(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getString("tenant_id"),
                Message.SenderType.valueOf(rs.getString("sender_type")),
                Message.MessageType.valueOf(rs.getString("message_type")),
                rs.getString("content"),
                rs.getString("media_url"),
                fromJson(rs.getString("metadata")),
                rs.getTimestamp("created_at").toInstant());
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map == null ? Map.of() : map);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata; storing empty object", e);
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize metadata; returning empty object", e);
            return Map.of();
        }
    }
}
