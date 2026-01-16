package br.com.archflow.agent.streaming.domain;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;

/**
 * Eventos do domínio THINKING para raciocínio de modelos reasoning.
 *
 * <p>Este domain trata de:
 * <ul>
 *   <li>Processo de raciocínio (thinking) de modelos o1, o3, etc</li>
 *   <li>Passos de reflexão</li>
 *   <li>Verificações internas</li>
 * </ul>
 *
 * <h3>Exemplos:</h3>
 * <pre>{@code
 * // Chunk de thinking
 * ThinkingEvent.thinking("Let me analyze this step by step...", "exec_123");
 *
 * // Reflexão completa
 * ThinkingEvent.reflection("I need to reconsider the approach", "exec_123", 1);
 *
 * // Verificação
 * ThinkingEvent.verification("Result validated", "exec_123", true);
 * }</pre>
 */
public final class ThinkingEvent {

    private ThinkingEvent() {
        // Utilitário - não instanciar
    }

    /**
     * Cria um evento de thinking (chunk do raciocínio).
     *
     * @param content Conteúdo do thinking
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent thinking(String content, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.THINKING)
                .type(ArchflowEventType.THINKING)
                .executionId(executionId)
                .addData("content", content)
                .build();
    }

    /**
     * Cria um evento de thinking com índice.
     *
     * @param content Conteúdo do thinking
     * @param executionId ID da execução
     * @param index Índice do chunk
     * @return ArchflowEvent
     */
    public static ArchflowEvent thinking(String content, String executionId, int index) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.THINKING)
                .type(ArchflowEventType.THINKING)
                .executionId(executionId)
                .addData("content", content)
                .addData("index", index)
                .build();
    }

    /**
     * Cria um evento de reflexão.
     *
     * <p>Reflexões são passos onde o modelo reconsidera sua abordagem.
     *
     * @param content Conteúdo da reflexão
     * @param executionId ID da execução
     * @param stepNumber Número do passo de reflexão
     * @return ArchflowEvent
     */
    public static ArchflowEvent reflection(String content, String executionId, int stepNumber) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.THINKING)
                .type(ArchflowEventType.REFLECTION)
                .executionId(executionId)
                .addData("content", content)
                .addData("stepNumber", stepNumber)
                .build();
    }

    /**
     * Cria um evento de reflexão com detalhes.
     *
     * @param content Conteúdo da reflexão
     * @param executionId ID da execução
     * @param stepNumber Número do passo
     * @param reasoningType Tipo de raciocínio (analysis, synthesis, verification)
     * @return ArchflowEvent
     */
    public static ArchflowEvent reflection(String content, String executionId, int stepNumber,
                                          String reasoningType) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.THINKING)
                .type(ArchflowEventType.REFLECTION)
                .executionId(executionId)
                .addData("content", content)
                .addData("stepNumber", stepNumber)
                .addData("reasoningType", reasoningType)
                .build();
    }

    /**
     * Cria um evento de verificação.
     *
     * <p>Verificações são validações internas realizadas pelo modelo.
     *
     * @param content Conteúdo da verificação
     * @param executionId ID da execução
     * @param passed Se a verificação passou
     * @return ArchflowEvent
     */
    public static ArchflowEvent verification(String content, String executionId, boolean passed) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.THINKING)
                .type(ArchflowEventType.VERIFICATION)
                .executionId(executionId)
                .addData("content", content)
                .addData("passed", passed)
                .build();
    }

    /**
     * Cria um evento de verificação com score de confiança.
     *
     * @param content Conteúdo da verificação
     * @param executionId ID da execução
     * @param passed Se a verificação passou
     * @param confidenceScore Score de confiança (0.0 a 1.0)
     * @return ArchflowEvent
     */
    public static ArchflowEvent verification(String content, String executionId, boolean passed,
                                            double confidenceScore) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.THINKING)
                .type(ArchflowEventType.VERIFICATION)
                .executionId(executionId)
                .addData("content", content)
                .addData("passed", passed)
                .addData("confidenceScore", confidenceScore)
                .build();
    }

    /**
     * Cria um evento de início de thinking.
     *
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent start(String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.THINKING)
                .type(ArchflowEventType.START)
                .executionId(executionId)
                .build();
    }

    /**
     * Cria um evento de fim de thinking.
     *
     * @param executionId ID da execução
     * @param totalSteps Total de passos de raciocínio
     * @return ArchflowEvent
     */
    public static ArchflowEvent end(String executionId, int totalSteps) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.THINKING)
                .type(ArchflowEventType.END)
                .executionId(executionId)
                .addData("totalSteps", totalSteps)
                .build();
    }

    /**
     * Cria um evento de erro de thinking.
     *
     * @param executionId ID da execução
     * @param errorMessage Mensagem de erro
     * @return ArchflowEvent
     */
    public static ArchflowEvent error(String executionId, String errorMessage) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.THINKING)
                .type(ArchflowEventType.ERROR)
                .executionId(executionId)
                .addData("error", errorMessage)
                .build();
    }
}
