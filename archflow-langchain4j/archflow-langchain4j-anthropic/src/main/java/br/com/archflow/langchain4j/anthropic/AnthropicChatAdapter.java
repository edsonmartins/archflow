package br.com.archflow.langchain4j.anthropic;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.memory.ChatMemory;

import java.util.Map;

/**
 * Adapter para integração com o modelo de chat da Anthropic (Claude) no LangChain4j 1.10.0.
 * Esta implementação permite interagir com modelos Claude, suportando geração de respostas simples e conversas com memória.
 *
 * <p>Este adapter é projetado para ser usado dentro do Archflow, integrando-se ao framework via SPI e oferecendo suporte a dois tipos de operações:
 * <ul>
 *   <li>{@code generate} - Gera uma resposta única a partir de uma entrada de texto</li>
 *   <li>{@code chat} - Realiza uma conversa mantendo o contexto através de memória</li>
 * </ul>
 *
 * <p>Exemplo de configuração:
 * <pre>{@code
 * Map<String, Object> config = Map.of(
 *     "api.key", "sua-chave-api-anthropic",  // Chave de API da Anthropic
 *     "model.name", "claude-sonnet-4-20250514",  // Nome do modelo (default: claude-3-5-sonnet-20241022)
 *     "temperature", 0.7                      // Temperatura (default: 0.7)
 * );
 * }</pre>
 *
 * @see LangChainAdapter
 * @see ChatModel
 * @see AnthropicChatModel
 */
public class AnthropicChatAdapter implements LangChainAdapter {
    private ChatModel model;
    private Map<String, Object> config;

    /**
     * Valida as configurações fornecidas para o adapter.
     *
     * @param properties Map com as configurações, incluindo "api.key", "model.name" (opcional) e "temperature" (opcional)
     * @throws IllegalArgumentException se as configurações forem inválidas ou ausentes
     */
    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }

        String apiKey = (String) properties.get("api.key");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Anthropic API key is required");
        }

        String modelName = (String) properties.getOrDefault("model.name", "claude-3-5-sonnet-20241022");
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Model name cannot be empty");
        }

        Object temperature = properties.get("temperature");
        if (temperature != null) {
            if (!(temperature instanceof Number)) {
                throw new IllegalArgumentException("Temperature must be a number");
            }
            double temp = ((Number) temperature).doubleValue();
            if (temp < 0.0 || temp > 1.0) {
                throw new IllegalArgumentException("Temperature must be between 0.0 and 1.0");
            }
        }
    }

    /**
     * Configura o adapter com as propriedades especificadas.
     *
     * <p>Requer as seguintes configurações:
     * <ul>
     *   <li>{@code api.key} - Chave de API da Anthropic</li>
     * </ul>
     * <p>Configurações opcionais:
     * <ul>
     *   <li>{@code model.name} - Nome do modelo Claude (default: "claude-3-5-sonnet-20241022")</li>
     *   <li>{@code temperature} - Temperatura para controle de criatividade (default: 0.7)</li>
     *   <li>{@code maxTokens} - Máximo de tokens na resposta (default: 4096)</li>
     * </ul>
     *
     * @param properties Map com as configurações
     * @throws IllegalArgumentException se alguma configuração obrigatória estiver faltando ou for inválida
     */
    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);
        this.config = properties;

        String apiKey = (String) properties.get("api.key");
        String modelName = (String) properties.getOrDefault("model.name", "claude-3-5-sonnet-20241022");
        Double temperature = ((Number) properties.getOrDefault("temperature", 0.7)).doubleValue();
        Integer maxTokens = properties.get("maxTokens") != null
                ? ((Number) properties.get("maxTokens")).intValue()
                : 4096;

        // LangChain4j 1.10.0: Builder pattern para criação do modelo
        this.model = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    /**
     * Executa operações no modelo de chat da Anthropic.
     *
     * <p>Operações suportadas:
     * <ul>
     *   <li>{@code generate} - Gera uma resposta única a partir de uma mensagem de entrada</li>
     *   <li>{@code chat} - Realiza uma interação conversacional mantendo o contexto na memória</li>
     * </ul>
     *
     * @param operation Nome da operação ("generate" ou "chat")
     * @param input     Para "generate" ou "chat": String com a mensagem do usuário
     * @param context   Contexto de execução, necessário para "chat" para acessar a memória
     * @return Para "generate": String com a resposta do modelo
     *         Para "chat": String com a resposta do modelo e memória atualizada
     * @throws IllegalArgumentException se a operação for inválida ou o input estiver no formato incorreto
     * @throws IllegalStateException se o adapter não estiver configurado ou a memória estiver ausente para "chat"
     * @throws RuntimeException se ocorrer um erro durante a execução
     */
    @Override
    public synchronized Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (model == null) {
            throw new IllegalStateException("Adapter not configured. Call configure() first.");
        }

        try {
            if ("generate".equals(operation)) {
                if (!(input instanceof String)) {
                    throw new IllegalArgumentException("Input must be a string for 'generate' operation");
                }
                // LangChain4j 1.10.0: ChatModel.chat(String) retorna String diretamente
                return model.chat((String) input);
            }

            if ("chat".equals(operation)) {
                if (!(input instanceof String)) {
                    throw new IllegalArgumentException("Chat input must be a string");
                }

                ChatMemory memory = context.getChatMemory();
                if (memory == null) {
                    throw new IllegalStateException("Chat memory not available in context");
                }

                // LangChain4j 1.10.0: Usa ChatModel.chat() com mensagens da memória
                UserMessage userMessage = UserMessage.from((String) input);
                memory.add(userMessage);

                // ChatModel.chat(List<ChatMessage>) retorna ChatResponse
                ChatResponse response = model.chat(memory.messages());
                memory.add(response.aiMessage());

                return response.aiMessage().text();
            }

            throw new IllegalArgumentException("Unsupported operation: " + operation);
        } catch (Exception e) {
            throw new RuntimeException("Error executing operation: " + operation, e);
        }
    }

    /**
     * Libera recursos utilizados pelo adapter.
     */
    @Override
    public void shutdown() {
        this.model = null;
        this.config = null;
    }
}
