package br.com.archflow.langchain4j.memory.jdbc;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JDBC-backed ChatMemory adapter para ambientes sem Redis.
 *
 * <p>Armazena mensagens de chat em banco relacional com isolamento
 * por tenant via chave composta {@code (tenant_id, session_id)}.
 *
 * <p>Configuração:
 * <ul>
 *   <li>{@code datasource} — DataSource JDBC (obrigatório)</li>
 *   <li>{@code memory.maxMessages} — Máximo de mensagens por sessão (default: 100)</li>
 * </ul>
 */
public class JdbcMemoryAdapter implements LangChainAdapter {
    private static final Logger logger = Logger.getLogger(JdbcMemoryAdapter.class.getName());

    private DataSource dataSource;
    private int maxMessages;

    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }
        if (properties.get("datasource") == null) {
            throw new IllegalArgumentException("DataSource is required (datasource)");
        }
    }

    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.dataSource = (DataSource) properties.get("datasource");
        this.maxMessages = properties.get("memory.maxMessages") != null
                ? ((Number) properties.get("memory.maxMessages")).intValue() : 100;
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        String tenantId = context.getTenantId();
        String sessionId = context.getSessionId() != null
                ? context.getSessionId()
                : context.getState().getFlowId();

        return switch (operation) {
            case "add" -> {
                if (!(input instanceof ChatMessage message)) {
                    throw new IllegalArgumentException("Input must be a ChatMessage");
                }
                addMessage(tenantId, sessionId, message);
                yield null;
            }
            case "get" -> getMessages(tenantId, sessionId);
            case "clear" -> {
                clearMessages(tenantId, sessionId);
                yield null;
            }
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }

    private void addMessage(String tenantId, String sessionId, ChatMessage message) throws SQLException {
        String role;
        String content;
        if (message instanceof UserMessage userMsg) {
            role = "user";
            content = userMsg.singleText();
        } else if (message instanceof AiMessage aiMsg) {
            role = "ai";
            content = aiMsg.text();
        } else {
            throw new IllegalArgumentException("Unsupported message type: " + message.getClass());
        }

        String sql = "INSERT INTO chat_messages (tenant_id, session_id, role, content) VALUES (?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.executeUpdate();
        }

        trimMessages(tenantId, sessionId);
    }

    private List<ChatMessage> getMessages(String tenantId, String sessionId) throws SQLException {
        String sql = "SELECT role, content FROM chat_messages WHERE tenant_id = ? AND session_id = ? ORDER BY id ASC";
        List<ChatMessage> messages = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String role = rs.getString("role");
                    String content = rs.getString("content");
                    if ("user".equals(role)) {
                        messages.add(UserMessage.from(content));
                    } else if ("ai".equals(role)) {
                        messages.add(AiMessage.from(content));
                    }
                }
            }
        }
        return messages;
    }

    private void clearMessages(String tenantId, String sessionId) throws SQLException {
        String sql = "DELETE FROM chat_messages WHERE tenant_id = ? AND session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            ps.executeUpdate();
        }
    }

    private void trimMessages(String tenantId, String sessionId) throws SQLException {
        String sql = """
            DELETE FROM chat_messages WHERE id IN (
                SELECT id FROM chat_messages
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY id DESC
                OFFSET ?
            )
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            ps.setInt(3, maxMessages);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.FINE, "Trim not supported or no excess messages", e);
        }
    }

    @Override
    public void shutdown() {
        this.dataSource = null;
    }

    public static class Factory implements LangChainAdapterFactory {
        @Override
        public String getProvider() {
            return "jdbc";
        }

        @Override
        public LangChainAdapter createAdapter(Map<String, Object> properties) {
            JdbcMemoryAdapter adapter = new JdbcMemoryAdapter();
            adapter.configure(properties);
            return adapter;
        }

        @Override
        public boolean supports(String type) {
            return "memory".equals(type);
        }
    }
}
