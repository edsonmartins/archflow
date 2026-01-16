package br.com.archflow.agent.streaming.domain;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;

import java.util.Map;

/**
 * Eventos do domínio TOOL para execução de tools/funções.
 *
 * <p>Este domain trata de:
 * <ul>
 *   <li>Início e fim de execução de tools</li>
 *   <li>Progresso de execução</li>
 *   <li>Resultados de tools</li>
 *   <li>Erros de execução</li>
 * </ul>
 *
 * <h3>Exemplos:</h3>
 * <pre>{@code
 * // Início de tool
 * ToolEvent.start("search", Map.of("query", "Java"), "exec_123");
 *
 * // Progresso
 * ToolEvent.progress("search", "Searching database...", 50, "exec_123");
 *
 * // Resultado
 * ToolEvent.result("search", Map.of("results", List.of(...)), "exec_123");
 * }</pre>
 */
public final class ToolEvent {

    private ToolEvent() {
        // Utilitário - não instanciar
    }

    /**
     * Cria um evento de início de execução de tool.
     *
     * @param toolName Nome da tool
     * @param input Input da tool
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent start(String toolName, Map<String, Object> input, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.TOOL_START)
                .executionId(executionId)
                .addData("toolName", toolName)
                .addData("input", input != null ? input : Map.of())
                .build();
    }

    /**
     * Cria um evento de início de execução com toolCallId.
     *
     * @param toolName Nome da tool
     * @param toolCallId ID da chamada da tool
     * @param input Input da tool
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent start(String toolName, String toolCallId,
                                      Map<String, Object> input, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.TOOL_START)
                .executionId(executionId)
                .addData("toolName", toolName)
                .addData("toolCallId", toolCallId)
                .addData("input", input != null ? input : Map.of())
                .build();
    }

    /**
     * Cria um evento de progresso de execução.
     *
     * @param toolName Nome da tool
     * @param message Mensagem de progresso
     * @param percentage Percentual completado (0-100)
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent progress(String toolName, String message,
                                        int percentage, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.PROGRESS)
                .executionId(executionId)
                .addData("toolName", toolName)
                .addData("message", message)
                .addData("percentage", percentage)
                .build();
    }

    /**
     * Cria um evento de progresso com dados adicionais.
     *
     * @param toolName Nome da tool
     * @param message Mensagem de progresso
     * @param percentage Percentual completado (0-100)
     * @param current Valor atual
     * @param total Valor total
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent progress(String toolName, String message,
                                        int percentage, long current, long total, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.PROGRESS)
                .executionId(executionId)
                .addData("toolName", toolName)
                .addData("message", message)
                .addData("percentage", percentage)
                .addData("current", current)
                .addData("total", total)
                .build();
    }

    /**
     * Cria um evento de resultado de tool.
     *
     * @param toolName Nome da tool
     * @param result Resultado da execução
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent result(String toolName, Object result, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.RESULT)
                .executionId(executionId)
                .addData("toolName", toolName)
                .addData("result", result)
                .build();
    }

    /**
     * Cria um evento de resultado com metadados.
     *
     * @param toolName Nome da tool
     * @param toolCallId ID da chamada da tool
     * @param result Resultado da execução
     * @param durationMs Duração em milissegundos
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent result(String toolName, String toolCallId,
                                       Object result, long durationMs, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.RESULT)
                .executionId(executionId)
                .addData("toolName", toolName)
                .addData("toolCallId", toolCallId)
                .addData("result", result)
                .addData("durationMs", durationMs)
                .build();
    }

    /**
     * Cria um evento de erro de tool.
     *
     * @param toolName Nome da tool
     * @param errorMessage Mensagem de erro
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent error(String toolName, String errorMessage, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.TOOL_ERROR)
                .executionId(executionId)
                .addData("toolName", toolName)
                .addData("error", errorMessage)
                .build();
    }

    /**
     * Cria um evento de erro com exceção.
     *
     * @param toolName Nome da tool
     * @param toolCallId ID da chamada da tool
     * @param error Exceção ocorrida
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent error(String toolName, String toolCallId,
                                      Throwable error, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.TOOL_ERROR)
                .executionId(executionId)
                .addData("toolName", toolName)
                .addData("toolCallId", toolCallId)
                .addData("error", error.getMessage())
                .addData("errorType", error.getClass().getSimpleName())
                .build();
    }

    /**
     * Cria um evento de fim de execução de tool.
     *
     * @param toolName Nome da tool
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent end(String toolName, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.END)
                .executionId(executionId)
                .addData("toolName", toolName)
                .build();
    }

    /**
     * Cria um evento de fim com estatísticas.
     *
     * @param toolName Nome da tool
     * @param toolCallId ID da chamada da tool
     * @param durationMs Duração em milissegundos
     * @param success Se executou com sucesso
     * @param executionId ID da execução
     * @return ArchflowEvent
     */
    public static ArchflowEvent end(String toolName, String toolCallId,
                                    long durationMs, boolean success, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.TOOL)
                .type(ArchflowEventType.END)
                .executionId(executionId)
                .addData("toolName", toolName)
                .addData("toolCallId", toolCallId)
                .addData("durationMs", durationMs)
                .addData("success", success)
                .build();
    }
}
