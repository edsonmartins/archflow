package br.com.archflow.langchain4j.adapter;

import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import java.util.Map;

public class ModelAdapter implements LangChainAdapter {
    private ChatLanguageModel model;
    private String provider;
    private String modelName;

    @Override
    public void configure(Map<String, Object> properties) {
        this.provider = properties.get("provider").toString();
        this.modelName = properties.get("model").toString();

        this.model = createModel(properties);
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        return switch (operation) {
            case "chat" -> {
                if (input instanceof String text) {
                    // Usando o novo método chat
                    yield model.chat(text);
                } else {
                    throw new IllegalArgumentException("Input deve ser uma String");
                }
            }
            case "chatWithMessages" -> {
                if (input instanceof List<?> messages) {
                    List<ChatMessage> chatMessages = (List<ChatMessage>) messages;
                    yield model.chat(chatMessages);
                } else {
                    throw new IllegalArgumentException("Input deve ser uma lista de ChatMessage");
                }
            }
            default -> throw new IllegalArgumentException("Operação não suportada: " + operation);
        };
    }

    @Override
    public void validate(Map<String, Object> properties) {
        if (!properties.containsKey("provider")) {
            throw new IllegalArgumentException("Propriedade 'provider' é obrigatória");
        }
        if (!properties.containsKey("model")) {
            throw new IllegalArgumentException("Propriedade 'model' é obrigatória");
        }
        if (!properties.containsKey("apiKey")) {
            throw new IllegalArgumentException("Propriedade 'apiKey' é obrigatória");
        }

        String provider = properties.get("provider").toString();
        if (!List.of("openai", "anthropic").contains(provider)) {
            throw new IllegalArgumentException("Provider não suportado: " + provider);
        }
    }

    private ChatLanguageModel createModel(Map<String, Object> properties) {
        return switch (provider.toLowerCase()) {
            case "openai" -> OpenAiChatModel.builder()
                    .apiKey(properties.get("apiKey").toString())
                    .modelName(modelName)
                    .temperature(getDouble(properties, "temperature", 0.7))
                    .maxTokens(getInt(properties, "maxTokens", 2000))
                    .build();

            case "anthropic" -> AnthropicChatModel.builder()
                    .apiKey(properties.get("apiKey").toString())
                    .modelName(modelName)
                    .temperature(getDouble(properties, "temperature", 0.7))
                    .maxTokens(getInt(properties, "maxTokens", 2000))
                    .build();

            default -> throw new IllegalArgumentException("Provider não suportado: " + provider);
        };
    }

    private Double getDouble(Map<String, Object> properties, String key, double defaultValue) {
        Object value = properties.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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

    public ChatLanguageModel getModel() {
        return model;
    }

    @Override
    public void shutdown() {
        // Libera recursos se necessário
    }
}