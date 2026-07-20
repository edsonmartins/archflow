package br.com.archflow.langchain4j.memory.redis;

import br.com.archflow.langchain4j.core.memory.ChatMessageCodec;
import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RedisMemoryAdapter implements LangChainAdapter {
    private JedisPool jedisPool;
    private String keyPrefix;
    private int maxMessages;
    private final ObjectMapper objectMapper;

    public RedisMemoryAdapter() {
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }

        String host = (String) properties.get("redis.host");
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Redis host is required");
        }

        Object port = properties.get("redis.port");
        if (port != null && !(port instanceof Number)) {
            throw new IllegalArgumentException("Redis port must be a number");
        }

        Object maxMessages = properties.get("memory.maxMessages");
        if (maxMessages != null && !(maxMessages instanceof Number)) {
            throw new IllegalArgumentException("Max messages must be a number");
        }
    }

    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);

        String host = (String) properties.getOrDefault("redis.host", "localhost");
        int port = (Integer) properties.getOrDefault("redis.port", 6379);
        this.keyPrefix = (String) properties.getOrDefault("redis.prefix", "archflow:chat:");
        this.maxMessages = (Integer) properties.getOrDefault("memory.maxMessages", 100);

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(5);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);

        this.jedisPool = new JedisPool(poolConfig, host, port);
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        String conversationId = buildConversationKey(context);

        if ("add".equals(operation)) {
            if (input instanceof ChatMessage message) {
                addMessage(conversationId, message);
                return null;
            }
            throw new IllegalArgumentException("Input must be a ChatMessage");
        }

        if ("get".equals(operation)) {
            return getMessages(conversationId);
        }

        if ("clear".equals(operation)) {
            clearMemory(conversationId);
            return null;
        }

        throw new IllegalArgumentException("Unsupported operation: " + operation);
    }

    private void addMessage(String conversationId, ChatMessage message) throws Exception {
        String key = keyPrefix + conversationId;
        try (var jedis = jedisPool.getResource()) {
            String json = serializeMessage(message);
            jedis.lpush(key, json);
            jedis.ltrim(key, 0, maxMessages - 1);
        }
    }

    private List<ChatMessage> getMessages(String conversationId) throws Exception {
        String key = keyPrefix + conversationId;
        try (var jedis = jedisPool.getResource()) {
            List<String> jsonMessages = jedis.lrange(key, 0, -1);
            List<ChatMessage> messages = new ArrayList<>();

            for (String json : jsonMessages) {
                ChatMessage message = deserializeMessage(json);
                if (message != null) {
                    messages.add(message);
                }
            }

            return messages;
        }
    }

    private void clearMemory(String conversationId) {
        String key = keyPrefix + conversationId;
        try (var jedis = jedisPool.getResource()) {
            jedis.del(key);
        }
    }

    /**
     * Serializa a mensagem no formato canônico da LangChain4j (via
     * {@link ChatMessageCodec}) — o mesmo formato do backend JDBC, cobrindo
     * todos os tipos de mensagem. Substitui o formato ad-hoc anterior
     * ({@code type}/{@code content}), que divergia do JDBC.
     */
    String serializeMessage(ChatMessage message) throws Exception {
        return ChatMessageCodec.toJson(message);
    }

    /**
     * Desserializa a mensagem. Formato atual: canônico da LangChain4j; o
     * formato legado ({@code type} + {@code content}, gravado por versões
     * anteriores) continua sendo lido pelo fallback.
     */
    ChatMessage deserializeMessage(String json) throws Exception {
        ChatMessage canonical = ChatMessageCodec.fromJson(json);
        if (canonical != null) {
            return canonical;
        }
        JsonNode node = objectMapper.readTree(json);
        String type = node.get("type").asText();

        return switch (type) {
            case "user" -> UserMessage.from(node.get("content").asText());
            case "system" -> SystemMessage.from(node.get("content").asText());
            case "ai" -> {
                AiMessage.Builder builder = AiMessage.builder();
                if (node.hasNonNull("content")) {
                    builder.text(node.get("content").asText());
                }
                JsonNode requestsNode = node.get("toolExecutionRequests");
                if (requestsNode != null && requestsNode.isArray() && !requestsNode.isEmpty()) {
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
            case "tool_execution_result" -> new ToolExecutionResultMessage(
                    node.hasNonNull("id") ? node.get("id").asText() : null,
                    node.hasNonNull("toolName") ? node.get("toolName").asText() : null,
                    node.get("content").asText());
            default -> throw new IllegalArgumentException("Unknown message type: " + type);
        };
    }

    /**
     * Monta a chave de conversa com isolamento por tenant.
     * Formato preferencial: {@code tenantId:sessionId}.
     * Fallback: {@code tenantId:flowId} — mantém isolamento por tenant.
     */
    private String buildConversationKey(ExecutionContext context) {
        String tenantId = context.getTenantId();
        String sessionId = context.getSessionId();
        String suffix = sessionId != null ? sessionId : context.getState().getFlowId();
        return tenantId + ":" + suffix;
    }

    @Override
    public void shutdown() {
        if (jedisPool != null) {
            jedisPool.close();
        }
    }

    public static class Factory implements LangChainAdapterFactory {
        @Override
        public String getProvider() {
            return "redis";
        }

        @Override
        public LangChainAdapter createAdapter(Map<String, Object> properties) {
            RedisMemoryAdapter adapter = new RedisMemoryAdapter();
            adapter.configure(properties);
            return adapter;
        }

        @Override
        public boolean supports(String type) {
            return "memory".equals(type);
        }
    }
}