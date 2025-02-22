package br.com.archflow.langchain4j.memory.redis;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
        String conversationId = context.getState().getFlowId();

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

    private String serializeMessage(ChatMessage message) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();

        if (message instanceof UserMessage userMessage) {
            node.put("type", "user");
            node.put("content", userMessage.singleText());
        } else if (message instanceof AiMessage aiMessage) {
            node.put("type", "ai");
            node.put("content", aiMessage.text());
        } else {
            throw new IllegalArgumentException("Unsupported message type: " + message.getClass());
        }

        return objectMapper.writeValueAsString(node);
    }

    private ChatMessage deserializeMessage(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        String type = node.get("type").asText();
        String content = node.get("content").asText();

        if ("user".equals(type)) {
            return UserMessage.from(content);
        } else if ("ai".equals(type)) {
            return AiMessage.from(content);
        } else {
            throw new IllegalArgumentException("Unknown message type: " + type);
        }
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