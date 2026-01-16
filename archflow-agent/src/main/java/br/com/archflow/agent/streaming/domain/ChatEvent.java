package br.com.archflow.agent.streaming.domain;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;

import java.util.Map;

/**
 * Eventos do domínio CHAT para mensagens do modelo de linguagem.
 *
 * <p>Este domain trata de:
 * <ul>
 *   <li>Deltas de streaming (chunks de resposta)</li>
 *   <li>Mensagens completas</li>
 *   <li>Início e fim de respostas</li>
 *   <li>Metadados de chat (role, model, tokens)</li>
 * </ul>
 *
 * <h3>Exemplos:</h3>
 * <pre>{@code
 * // Delta de streaming
 * ChatEvent.delta("Hello", "exec_123");
 *
 * // Mensagem completa
 * ChatEvent.message("Hello World!", "user", "gpt-4", 150, Map.of("finishReason", "stop"));
 *
 * // Início de resposta
 * ChatEvent.start("exec_123", "gpt-4");
 * }</pre>
 */
public final class ChatEvent {

    private ChatEvent() {
        // Utilitário - não instanciar
    }

    /**
     * Cria um evento de delta (chunk de streaming).
     *
     * @param content Conteúdo do delta
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent delta(String content, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.DELTA)
                .executionId(executionId)
                .addData("content", content)
                .build();
    }

    /**
     * Cria um evento de delta com metadados adicionais.
     *
     * @param content Conteúdo do delta
     * @param executionId ID da execução
     * @param index Índice do delta (para ordenação)
     * @return ArchflowEvent
     */
    public static ArchflowEvent delta(String content, String executionId, int index) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.DELTA)
                .executionId(executionId)
                .addData("content", content)
                .addData("index", index)
                .build();
    }

    /**
     * Cria um evento de mensagem completa.
     *
     * @param content Conteúdo da mensagem
     * @param role Role (user, assistant, system)
     * @param model Modelo usado
     * @return ArchflowEvent
     */
    public static ArchflowEvent message(String content, String role, String model) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.MESSAGE)
                .addData("content", content)
                .addData("role", role)
                .addData("model", model)
                .build();
    }

    /**
     * Cria um evento de mensagem completa com metadados de uso.
     *
     * @param content Conteúdo da mensagem
     * @param role Role (user, assistant, system)
     * @param model Modelo usado
     * @param totalTokens Total de tokens usados
     * @param metadata Metadados adicionais
     * @return ArchflowEvent
     */
    public static ArchflowEvent message(String content, String role, String model,
                                        int totalTokens, Map<String, Object> metadata) {
        ArchflowEvent.Builder builder = ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.MESSAGE)
                .addData("content", content)
                .addData("role", role)
                .addData("model", model)
                .addData("totalTokens", totalTokens);

        if (metadata != null) {
            metadata.forEach(builder::addData);
        }

        return builder.build();
    }

    /**
     * Cria um evento de início de resposta.
     *
     * @param executionId ID da execução
     * @param model Modelo usado
     * @return ArchflowEvent
     */
    public static ArchflowEvent start(String executionId, String model) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.START)
                .executionId(executionId)
                .addData("model", model)
                .build();
    }

    /**
     * Cria um evento de início de resposta com parâmetros.
     *
     * @param executionId ID da execução
     * @param model Modelo usado
     * @param temperature Temperatura usada
     * @param maxTokens Max tokens
     * @return ArchflowEvent
     */
    public static ArchflowEvent start(String executionId, String model,
                                      Double temperature, Integer maxTokens) {
        ArchflowEvent.Builder builder = ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.START)
                .executionId(executionId)
                .addData("model", model);

        if (temperature != null) {
            builder.addData("temperature", temperature);
        }
        if (maxTokens != null) {
            builder.addData("maxTokens", maxTokens);
        }

        return builder.build();
    }

    /**
     * Cria um evento de fim de resposta.
     *
     * @param executionId ID da execução
     * @param finishReason Razão do término (stop, length, tool_calls, etc)
     * @return ArchflowEvent
     */
    public static ArchflowEvent end(String executionId, String finishReason) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.END)
                .executionId(executionId)
                .addData("finishReason", finishReason)
                .build();
    }

    /**
     * Cria um evento de fim de resposta com estatísticas.
     *
     * @param executionId ID da execução
     * @param finishReason Razão do término
     * @param totalTokens Total de tokens
     * @param promptTokens Tokens do prompt
     * @param completionTokens Tokens da resposta
     * @return ArchflowEvent
     */
    public static ArchflowEvent end(String executionId, String finishReason,
                                    int totalTokens, int promptTokens, int completionTokens) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.END)
                .executionId(executionId)
                .addData("finishReason", finishReason)
                .addData("totalTokens", totalTokens)
                .addData("promptTokens", promptTokens)
                .addData("completionTokens", completionTokens)
                .build();
    }

    /**
     * Cria um evento de erro de chat.
     *
     * @param executionId ID da execução
     * @param errorMessage Mensagem de erro
     * @return ArchflowEvent
     */
    public static ArchflowEvent error(String executionId, String errorMessage) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.ERROR)
                .executionId(executionId)
                .addData("error", errorMessage)
                .build();
    }

    /**
     * Cria um evento de erro com exceção.
     *
     * @param executionId ID da execução
     * @param error Exceção ocorrida
     * @return ArchflowEvent
     */
    public static ArchflowEvent error(String executionId, Throwable error) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.ERROR)
                .executionId(executionId)
                .addData("error", error.getMessage())
                .addData("errorType", error.getClass().getSimpleName())
                .build();
    }
}
