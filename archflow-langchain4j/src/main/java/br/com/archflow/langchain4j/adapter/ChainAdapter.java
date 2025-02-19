package br.com.archflow.langchain4j.adapter;

import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.chain.Chain;
import dev.langchain4j.chain.ConversationalChain;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.memory.ChatMemory;
import java.util.List;
import java.util.Map;

public class ChainAdapter implements LangChainAdapter {
    private Chain chain;
    private String chainType;
    private ChatMemory memory;
    private ChatLanguageModel model;

    @Override
    public void configure(Map<String, Object> properties) {
        this.chainType = properties.get("type").toString();

        // Configurar modelo e memória se fornecidos
        if (properties.containsKey("model")) {
            ModelAdapter modelAdapter = new ModelAdapter();
            modelAdapter.configure((Map<String, Object>) properties.get("model"));
            this.model = (ChatLanguageModel) modelAdapter.getModel();
        }

        if (properties.containsKey("memory")) {
            MemoryAdapter memoryAdapter = new MemoryAdapter();
            memoryAdapter.configure((Map<String, Object>) properties.get("memory"));
            this.memory = (ChatMemory) memoryAdapter.getMemory();
        }

        this.chain = createChain(properties);
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        return switch (operation) {
            case "execute" -> {
                if (input instanceof String text) {
                    yield chain.execute(text);
                } else {
                    throw new IllegalArgumentException("Input deve ser uma String");
                }
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
        if (!List.of("conversational", "qa").contains(type)) {
            throw new IllegalArgumentException("Tipo de chain não suportado: " + type);
        }

        // Validar configuração do modelo se necessário
        if (properties.containsKey("model")) {
            if (!(properties.get("model") instanceof Map)) {
                throw new IllegalArgumentException("Configuração do modelo deve ser um Map");
            }
        }

        // Validar configuração da memória se necessário
        if (properties.containsKey("memory")) {
            if (!(properties.get("memory") instanceof Map)) {
                throw new IllegalArgumentException("Configuração da memória deve ser um Map");
            }
        }
    }

    private Chain createChain(Map<String, Object> properties) {
        return switch (chainType) {
            case "conversational" -> {
                if (model == null) {
                    throw new IllegalArgumentException("Modelo é obrigatório para ConversationalChain");
                }

                ConversationalChain.ConversationalChainBuilder builder = ConversationalChain.builder()
                        .chatLanguageModel(model);

                if (memory != null) {
                    builder.chatMemory(memory);
                }

                yield builder.build();
            }
            case "qa" -> {
                // TODO: Implementar QA Chain quando disponível no LangChain4j
                throw new UnsupportedOperationException("QA Chain ainda não implementada");
            }
            default -> throw new IllegalArgumentException("Tipo de chain não suportado: " + chainType);
        };
    }

    @Override
    public void shutdown() {
        // Liberar recursos se necessário
    }

    // Métodos de acesso protegidos para testes e uso interno
    protected Chain getChain() {
        return chain;
    }

    protected ChatMemory getMemory() {
        return memory;
    }

    protected ChatLanguageModel getModel() {
        return model;
    }
}