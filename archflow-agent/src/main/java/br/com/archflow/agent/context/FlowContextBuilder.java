package br.com.archflow.agent.context;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.*;
import java.util.logging.Logger;

/**
 * Builder para criar e configurar o contexto de execução de fluxos
 */
public class FlowContextBuilder {
    private static final Logger logger = Logger.getLogger(FlowContextBuilder.class.getName());
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Flow flow;
    private JsonNode parametersJson;
    private Map<String, Object> initialVariables;
    private Map<String, Object> additionalContext;

    public FlowContextBuilder(Flow flow) {
        this.flow = flow;
        this.initialVariables = new HashMap<>();
        this.additionalContext = new HashMap<>();
    }

    /**
     * Define parâmetros a partir de JSON
     */
    public FlowContextBuilder withParametersJson(String json) {
        try {
            this.parametersJson = objectMapper.readTree(json);
            return this;
        } catch (Exception e) {
            throw new ContextBuilderException("Erro ao processar JSON de parâmetros", e);
        }
    }

    /**
     * Define parâmetros a partir de JsonNode
     */
    public FlowContextBuilder withParametersJson(JsonNode json) {
        this.parametersJson = json;
        return this;
    }

    /**
     * Adiciona variáveis iniciais
     */
    public FlowContextBuilder withInitialVariables(Map<String, Object> variables) {
        this.initialVariables.putAll(variables);
        return this;
    }

    /**
     * Adiciona contexto adicional
     */
    public FlowContextBuilder withAdditionalContext(Map<String, Object> context) {
        this.additionalContext.putAll(context);
        return this;
    }

    /**
     * Constrói o contexto de execução
     */
    public ExecutionContext build() {
        try {
            // 1. Cria o contexto base
            DefaultExecutionContext context = new DefaultExecutionContext(
                MessageWindowChatMemory.builder()
                    .maxMessages(100)
                    .build()
            );

            // 2. Processa parâmetros do JSON se disponível
            if (parametersJson != null) {
                processJsonParameters(context);
            }

            // 3. Adiciona variáveis iniciais
            initialVariables.forEach(context::set);

            // 4. Adiciona contexto adicional
            additionalContext.forEach(context::set);

            // 5. Configura estado inicial
            context.setState(createInitialState());

            return context;

        } catch (Exception e) {
            throw new ContextBuilderException("Erro ao construir contexto de execução", e);
        }
    }

    private void processJsonParameters(DefaultExecutionContext context) {
        // Processa parâmetros simples
        if (parametersJson.isObject()) {
            parametersJson.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode valueNode = entry.getValue();
                
                try {
                    Object value = convertJsonValue(valueNode);
                    context.set(key, value);
                } catch (Exception e) {
                    logger.warning("Erro ao processar parâmetro " + key + ": " + e.getMessage());
                }
            });
        }
    }

    private Object convertJsonValue(JsonNode node) {
        try {
            if (node.isNull()) {
                return null;
            } else if (node.isBoolean()) {
                return node.asBoolean();
            } else if (node.isInt()) {
                return node.asInt();
            } else if (node.isLong()) {
                return node.asLong();
            } else if (node.isDouble()) {
                return node.asDouble();
            } else if (node.isTextual()) {
                return node.asText();
            } else if (node.isArray()) {
                List<Object> list = new ArrayList<>();
                node.elements().forEachRemaining(element -> 
                    list.add(convertJsonValue(element))
                );
                return list;
            } else if (node.isObject()) {
                Map<String, Object> map = new HashMap<>();
                node.fields().forEachRemaining(entry ->
                    map.put(entry.getKey(), convertJsonValue(entry.getValue()))
                );
                return map;
            } else {
                return node.toString();
            }
        } catch (Exception e) {
            throw new ContextBuilderException("Erro ao converter valor JSON", e);
        }
    }

    private FlowState createInitialState() {
        return FlowState.builder()
            .flowId(flow.getId())
            .status(FlowStatus.INITIALIZED)
            .variables(new HashMap<>(initialVariables))
            .executionPaths(new ArrayList<>())
            .build();
    }

    /**
     * Exceção específica para erros no builder
     */
    public static class ContextBuilderException extends RuntimeException {
        public ContextBuilderException(String message) {
            super(message);
        }

        public ContextBuilderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Utilitário para validar tipos de valores
     */
    public static class TypeValidator {
        public static boolean isValidNumber(Object value) {
            return value instanceof Number;
        }

        public static boolean isValidBoolean(Object value) {
            return value instanceof Boolean;
        }

        public static boolean isValidString(Object value) {
            return value instanceof String;
        }

        public static boolean isValidList(Object value) {
            return value instanceof List;
        }

        public static boolean isValidMap(Object value) {
            return value instanceof Map;
        }

        public static <T> T convertTo(Object value, Class<T> type) {
            if (value == null) {
                return null;
            }
            return objectMapper.convertValue(value, type);
        }

        public static <T> List<T> convertToList(Object value, Class<T> elementType) {
            if (value == null) {
                return null;
            }
            return objectMapper.convertValue(value, 
                objectMapper.getTypeFactory().constructCollectionType(List.class, elementType)
            );
        }

        public static <K, V> Map<K, V> convertToMap(Object value, Class<K> keyType, Class<V> valueType) {
            if (value == null) {
                return null;
            }
            return objectMapper.convertValue(value,
                objectMapper.getTypeFactory().constructMapType(Map.class, keyType, valueType)
            );
        }
    }

    /**
     * Factory method para criar builder
     */
    public static FlowContextBuilder forFlow(Flow flow) {
        return new FlowContextBuilder(flow);
    }
}