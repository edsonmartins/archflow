package br.com.archflow.langchain4j.memory.jdbc;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        SerializedMessage row = serializeMessage(message);

        String sql = "INSERT INTO chat_messages (tenant_id, session_id, role, content) VALUES (?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            ps.setString(3, row.role());
            ps.setString(4, row.content());
            ps.executeUpdate();
        }

        trimMessages(tenantId, sessionId);
    }

    /** Par (role, content) persistido na tabela {@code chat_messages}. */
    record SerializedMessage(String role, String content) {}

    /**
     * Serializa uma mensagem para o par (role, content). Formato:
     * <ul>
     *   <li>{@code user}/{@code ai}/{@code system} — texto puro em {@code content}
     *       (retrocompatível com linhas já gravadas)</li>
     *   <li>{@code ai_tool} — AiMessage com tool requests; {@code content} é JSON
     *       {@code {"text":...,"toolExecutionRequests":[{"id","name","arguments"}]}}</li>
     *   <li>{@code tool} — ToolExecutionResultMessage; {@code content} é JSON
     *       {@code {"id":...,"toolName":...,"text":...}}</li>
     * </ul>
     */
    SerializedMessage serializeMessage(ChatMessage message) {
        try {
            if (message instanceof UserMessage userMsg) {
                return new SerializedMessage("user", userMsg.singleText());
            }
            if (message instanceof SystemMessage systemMsg) {
                return new SerializedMessage("system", systemMsg.text());
            }
            if (message instanceof AiMessage aiMsg) {
                if (!aiMsg.hasToolExecutionRequests()) {
                    return new SerializedMessage("ai", aiMsg.text());
                }
                ObjectNode node = objectMapper.createObjectNode();
                if (aiMsg.text() != null) {
                    node.put("text", aiMsg.text());
                }
                ArrayNode requests = node.putArray("toolExecutionRequests");
                for (ToolExecutionRequest request : aiMsg.toolExecutionRequests()) {
                    ObjectNode reqNode = requests.addObject();
                    if (request.id() != null) {
                        reqNode.put("id", request.id());
                    }
                    reqNode.put("name", request.name());
                    if (request.arguments() != null) {
                        reqNode.put("arguments", request.arguments());
                    }
                }
                return new SerializedMessage("ai_tool", objectMapper.writeValueAsString(node));
            }
            if (message instanceof ToolExecutionResultMessage toolResult) {
                ObjectNode node = objectMapper.createObjectNode();
                if (toolResult.id() != null) {
                    node.put("id", toolResult.id());
                }
                if (toolResult.toolName() != null) {
                    node.put("toolName", toolResult.toolName());
                }
                node.put("text", toolResult.text());
                return new SerializedMessage("tool", objectMapper.writeValueAsString(node));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize message: " + message.getClass(), e);
        }
        throw new IllegalArgumentException("Unsupported message type: " + message.getClass());
    }

    /**
     * Desserializa o par (role, content) gravado por
     * {@link #serializeMessage(ChatMessage)}. Roles desconhecidos retornam
     * {@code null} (linha ignorada), preservando compatibilidade futura.
     */
    ChatMessage deserializeMessage(String role, String content) {
        try {
            return switch (role) {
                case "user" -> UserMessage.from(content);
                case "system" -> SystemMessage.from(content);
                case "ai" -> AiMessage.from(content);
                case "ai_tool" -> {
                    JsonNode node = objectMapper.readTree(content);
                    AiMessage.Builder builder = AiMessage.builder();
                    if (node.hasNonNull("text")) {
                        builder.text(node.get("text").asText());
                    }
                    JsonNode requestsNode = node.get("toolExecutionRequests");
                    if (requestsNode != null && requestsNode.isArray()) {
                        List<ToolExecutionRequest> requests = new ArrayList<>();
                        for (JsonNode reqNode : requestsNode) {
                            requests.add(ToolExecutionRequest.builder()
                                    .id(reqNode.hasNonNull("id") ? reqNode.get("id").asText() : null)
                                    .name(reqNode.get("name").asText())
                                    .arguments(reqNode.hasNonNull("arguments")
                                            ? reqNode.get("arguments").asText() : null)
                                    .build());
                        }
                        builder.toolExecutionRequests(requests);
                    }
                    yield builder.build();
                }
                case "tool" -> {
                    JsonNode node = objectMapper.readTree(content);
                    yield new ToolExecutionResultMessage(
                            node.hasNonNull("id") ? node.get("id").asText() : null,
                            node.hasNonNull("toolName") ? node.get("toolName").asText() : null,
                            node.get("text").asText());
                }
                default -> {
                    logger.log(Level.WARNING, "Skipping message with unknown role: {0}", role);
                    yield null;
                }
            };
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to deserialize message with role " + role, e);
            return null;
        }
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
                    ChatMessage message = deserializeMessage(rs.getString("role"), rs.getString("content"));
                    if (message != null) {
                        messages.add(message);
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
        // Cross-DB: MySQL < 8 rejects bare OFFSET without LIMIT; PostgreSQL
        // and SQL Server accept OFFSET alone but differ in FETCH syntax.
        // Solution: a two-step delete that reads the surviving IDs with
        // an explicit LIMIT, then deletes everything NOT in that set.
        //
        // This also sidesteps MySQL's "can't update table used in subquery"
        // restriction because we materialize the id list in Java.
        List<Long> keepIds = new ArrayList<>();
        String selectSql = """
            SELECT id FROM chat_messages
            WHERE tenant_id = ? AND session_id = ?
            ORDER BY id DESC
            LIMIT ?
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            ps.setInt(3, maxMessages);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keepIds.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            // If the SELECT fails we skip trimming — the caller will retry
            // on the next message. We log at FINE because a failure here
            // is not user-visible.
            logger.log(Level.FINE, "Trim skipped: unable to list surviving ids", e);
            return;
        }

        String deleteSql;
        if (keepIds.isEmpty()) {
            // Nothing to keep — delete everything for this session.
            deleteSql = "DELETE FROM chat_messages WHERE tenant_id = ? AND session_id = ?";
        } else {
            // Preserve only the most recent maxMessages ids.
            String placeholders = keepIds.stream().map(i -> "?").collect(java.util.stream.Collectors.joining(","));
            deleteSql = "DELETE FROM chat_messages WHERE tenant_id = ? AND session_id = ? "
                    + "AND id NOT IN (" + placeholders + ")";
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            for (int i = 0; i < keepIds.size(); i++) {
                ps.setLong(3 + i, keepIds.get(i));
            }
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
