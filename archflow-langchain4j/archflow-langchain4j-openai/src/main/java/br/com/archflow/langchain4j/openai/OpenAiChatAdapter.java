package br.com.archflow.langchain4j.openai;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.memory.ChatMemory;

import java.io.IOException;
import java.util.Map;

/**
 * Adapter para integração com o modelo de chat da OpenAI no LangChain4j.
 * Esta implementação permite interagir com modelos de linguagem da OpenAI, suportando geração de respostas simples e conversas com memória.
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
 *     "api.key", "sua-chave-api-openai",    // Chave de API da OpenAI
 *     "model.name", "gpt-4",                // Nome do modelo (opcional, default: gpt-3.5-turbo)
 *     "temperature", 0.9                    // Temperatura para controle de criatividade (opcional, default: 0.7)
 * );
 * }</pre>
 *
 * @see LangChainAdapter
 * @see OpenAiChatModel
 */
public class OpenAiChatAdapter implements LangChainAdapter {
    private ChatLanguageModel model;
    private Map<String, Object> config;

    /**
     * Valida as configurações fornecidas para o adapter.
     *
     * <p>Verifica se as propriedades obrigatórias (como a chave de API) estão presentes e se os valores opcionais
     * (como nome do modelo e temperatura) estão dentro dos limites aceitáveis.
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
            throw new IllegalArgumentException("OpenAI API key is required");
        }

        String modelName = (String) properties.getOrDefault("model.name", "gpt-3.5-turbo");
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Model name cannot be empty");
        }

        Object temperature = properties.get("temperature");
        if (temperature != null) {
            if (!(temperature instanceof Number)) {
                throw new IllegalArgumentException("Temperature must be a number");
            }
            double temp = ((Number) temperature).doubleValue();
            if (temp < 0.0 || temp > 2.0) {
                throw new IllegalArgumentException("Temperature must be between 0.0 and 2.0");
            }
        }
    }

    /**
     * Configura o adapter com as propriedades especificadas.
     *
     * <p>Requer as seguintes configurações:
     * <ul>
     *   <li>{@code api.key} - Chave de API da OpenAI</li>
     * </ul>
     * <p>Configurações opcionais:
     * <ul>
     *   <li>{@code model.name} - Nome do modelo OpenAI (default: "gpt-3.5-turbo")</li>
     *   <li>{@code temperature} - Temperatura para controle de criatividade (default: 0.7)</li>
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
        String modelName = (String) properties.getOrDefault("model.name", "gpt-3.5-turbo");
        Double temperature = (Double) properties.getOrDefault("temperature", 0.7);

        this.model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .build();
    }

    /**
     * Executa operações no modelo de chat da OpenAI.
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
     * @return Para "generate": {@link ChatResponse} com a resposta do modelo
     *         Para "chat": {@link ChatResponse} com a resposta do modelo e memória atualizada
     * @throws IllegalArgumentException se a operação for inválida ou o input estiver no formato incorreto
     * @throws IllegalStateException se o adapter não estiver configurado ou a memória estiver ausente para "chat"
     * @throws RuntimeException se ocorrer um erro durante a execução (ex.: falha de rede)
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
                UserMessage userMessage = UserMessage.from((String) input);
                return model.chat(userMessage);
            }

            if ("chat".equals(operation)) {
                if (!(input instanceof String)) {
                    throw new IllegalArgumentException("Chat input must be a string");
                }

                ChatMemory memory = context.getChatMemory();
                if (memory == null) {
                    throw new IllegalStateException("Chat memory not available in context");
                }

                UserMessage userMessage = UserMessage.from((String) input);
                memory.add(userMessage);
                ChatResponse response = model.chat(userMessage);
                memory.add(response.aiMessage());
                return response;
            }

            throw new IllegalArgumentException("Unsupported operation: " + operation);
        } catch (Exception e) {
            throw new RuntimeException("Error executing operation: " + operation, e);
        }
    }

    /**
     * Libera recursos utilizados pelo adapter.
     *
     * <p>Define o modelo e as configurações como null, permitindo que o garbage collector os libere.
     * Não há garantia de que recursos internos do {@link OpenAiChatModel} sejam fechados.
     */
    @Override
    public void shutdown() {
        this.model = null;
        this.config = null;
    }
}