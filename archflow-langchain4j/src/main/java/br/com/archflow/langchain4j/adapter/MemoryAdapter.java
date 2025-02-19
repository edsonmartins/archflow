package br.com.archflow.langchain4j.adapter;

import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.List;
import java.util.Map;

public class MemoryAdapter implements LangChainAdapter {
    private ChatMemory memory;
    private String memoryType;

    @Override
    public void configure(Map<String, Object> properties) {
        this.memoryType = properties.get("type").toString();
        this.memory = createMemory(properties);
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        return switch (operation) {
            case "addMessage" -> {
                if (input instanceof ChatMessage message) {
                    memory.add(message);
                    yield null;
                } else {
                    throw new IllegalArgumentException("Input deve ser um ChatMessage");
                }
            }
            case "addMessages" -> {
                if (input instanceof List<?> messages) {
                    List<ChatMessage> chatMessages = (List<ChatMessage>) messages;
                    chatMessages.forEach(memory::add);
                    yield null;
                } else {
                    throw new IllegalArgumentException("Input deve ser uma lista de ChatMessage");
                }
            }
            case "getMessages" -> memory.messages();
            case "clear" -> {
                memory.clear();
                yield null;
            }
            default -> throw new IllegalArgumentException("Operação não suportada: " + operation);
        };
    }

    @Override
    public void validate(Map<String, Object> properties) {
        if (!properties.containsKey("type")) {
            throw new IllegalArgumentException("Propriedade 'type' é obrigatória");
        }

        String type = properties.get("type").toString();
        if (!List.of("window").contains(type)) {
            throw new IllegalArgumentException("Tipo de memória não suportado: " + type);
        }
    }

    private ChatMemory createMemory(Map<String, Object> properties) {
        return switch (memoryType) {
            case "window" -> MessageWindowChatMemory.builder()
                    .maxMessages(getInt(properties, "maxMessages", 10))
                    .id(properties.getOrDefault("id", "default").toString())
                    .build();
            default -> throw new IllegalArgumentException("Tipo de memória não suportado: " + memoryType);
        };
    }

    private Integer getInt(Map<String, Object> properties, String key, int defaultValue) {
        Object value = properties.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public ChatMemory getMemory() {
        return memory;
    }

    public String getMemoryType() {
        return memoryType;
    }

    @Override
    public void shutdown() {
        if (memory != null) {
            memory.clear();
        }
    }
}