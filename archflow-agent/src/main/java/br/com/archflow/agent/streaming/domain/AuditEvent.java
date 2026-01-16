package br.com.archflow.agent.streaming.domain;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;

import java.util.Map;

/**
 * Eventos do domínio AUDIT para tracing e debugging.
 *
 * <p>Este domain trata de:
 * <ul>
 *   <li>Traces de execução para debugging</li>
 *   <li>Spans de tracing distribuído</li>
 *   <li>Métricas de execução</li>
 *   <li>Logs estruturados</li>
 * </ul>
 *
 * <h3>Exemplos:</h3>
 * <pre>{@code
 * // Trace entry
 * AuditEvent.trace("Entering workflow step", "exec_123", "INFO");
 *
 * // Span
 * AuditEvent.span("llm_call", "exec_123", "parent_span", 0, 1500);
 *
 * // Métrica
 * AuditEvent.metric("tool_duration", 500, "ms", "search_tool");
 * }</pre>
 */
public final class AuditEvent {

    private AuditEvent() {
        // Utilitário - não instanciar
    }

    /**
     * Níveis de log.
     */
    public enum LogLevel {
        TRACE, DEBUG, INFO, WARN, ERROR
    }

    /**
     * Cria um evento de trace.
     *
     * @param message Mensagem do trace
     * @param executionId ID da execução
     * @param level Nível do log
     * @return ArchflowEvent
     */
    public static ArchflowEvent trace(String message, String executionId, LogLevel level) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.TRACE)
                .executionId(executionId)
                .addData("message", message)
                .addData("level", level.name())
                .build();
    }

    /**
     * Cria um evento de trace com contexto.
     *
     * @param message Mensagem do trace
     * @param executionId ID da execução
     * @param level Nível do log
     * @param component Componente que gerou o trace
     * @return ArchflowEvent
     */
    public static ArchflowEvent trace(String message, String executionId,
                                      LogLevel level, String component) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.TRACE)
                .executionId(executionId)
                .addData("message", message)
                .addData("level", level.name())
                .addData("component", component)
                .build();
    }

    /**
     * Cria um evento de span de tracing.
     *
     * @param spanName Nome do span
     * @param executionId ID da execução
     * @param parentSpanId ID do span pai (opcional)
     * @param startOffset Offset de início em ms
     * @param duration Duração em ms
     * @return ArchflowEvent
     */
    public static ArchflowEvent span(String spanName, String executionId,
                                     String parentSpanId, long startOffset, long duration) {
        ArchflowEvent.Builder builder = ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.SPAN)
                .executionId(executionId)
                .addData("spanName", spanName)
                .addData("startOffset", startOffset)
                .addData("duration", duration);

        if (parentSpanId != null) {
            builder.addData("parentSpanId", parentSpanId);
        }

        return builder.build();
    }

    /**
     * Cria um evento de span com atributos.
     *
     * @param spanName Nome do span
     * @param executionId ID da execução
     * @param parentSpanId ID do span pai
     * @param startOffset Offset de início em ms
     * @param duration Duração em ms
     * @param attributes Atributos adicionais
     * @return ArchflowEvent
     */
    public static ArchflowEvent span(String spanName, String executionId,
                                     String parentSpanId, long startOffset, long duration,
                                     Map<String, Object> attributes) {
        ArchflowEvent.Builder builder = ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.SPAN)
                .executionId(executionId)
                .addData("spanName", spanName)
                .addData("startOffset", startOffset)
                .addData("duration", duration);

        if (parentSpanId != null) {
            builder.addData("parentSpanId", parentSpanId);
        }

        if (attributes != null) {
            attributes.forEach(builder::addData);
        }

        return builder.build();
    }

    /**
     * Cria um evento de métrica.
     *
     * @param name Nome da métrica
     * @param value Valor da métrica
     * @param unit Unidade da métrica
     * @param tags Tags associadas
     * @return ArchflowEvent
     */
    public static ArchflowEvent metric(String name, double value, String unit, String... tags) {
        ArchflowEvent.Builder builder = ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.METRIC)
                .addData("name", name)
                .addData("value", value)
                .addData("unit", unit);

        if (tags.length > 0) {
            builder.addData("tags", String.join(",", tags));
        }

        return builder.build();
    }

    /**
     * Cria um evento de métrica com executionId.
     *
     * @param name Nome da métrica
     * @param value Valor da métrica
     * @param unit Unidade da métrica
     * @param executionId ID da execução
     * @param tags Tags associadas
     * @return ArchflowEvent
     */
    public static ArchflowEvent metric(String name, double value, String unit,
                                       String executionId, String... tags) {
        ArchflowEvent.Builder builder = ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.METRIC)
                .executionId(executionId)
                .addData("name", name)
                .addData("value", value)
                .addData("unit", unit);

        if (tags.length > 0) {
            builder.addData("tags", String.join(",", tags));
        }

        return builder.build();
    }

    /**
     * Cria um evento de log.
     *
     * @param message Mensagem do log
     * @param executionId ID da execução
     * @param level Nível do log
     * @return ArchflowEvent
     */
    public static ArchflowEvent log(String message, String executionId, LogLevel level) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.LOG)
                .executionId(executionId)
                .addData("message", message)
                .addData("level", level.name())
                .build();
    }

    /**
     * Cria um evento de log com exceção.
     *
     * @param message Mensagem do log
     * @param executionId ID da execução
     * @param level Nível do log
     * @param throwable Exceção
     * @return ArchflowEvent
     */
    public static ArchflowEvent log(String message, String executionId,
                                    LogLevel level, Throwable throwable) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.LOG)
                .executionId(executionId)
                .addData("message", message)
                .addData("level", level.name())
                .addData("exception", throwable.getClass().getSimpleName())
                .addData("exceptionMessage", throwable.getMessage())
                .build();
    }

    /**
     * Cria um evento de log com stack trace.
     *
     * @param message Mensagem do log
     * @param executionId ID da execução
     * @param level Nível do log
     * @param stackTrace Stack trace da exceção
     * @return ArchflowEvent
     */
    public static ArchflowEvent log(String message, String executionId,
                                    LogLevel level, String stackTrace) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.LOG)
                .executionId(executionId)
                .addData("message", message)
                .addData("level", level.name())
                .addData("stackTrace", stackTrace)
                .build();
    }

    /**
     * Cria um evento de início de tracing.
     *
     * @param executionId ID da execução
     * @param traceId ID do trace distribuído
     * @return ArchflowEvent
     */
    public static ArchflowEvent start(String executionId, String traceId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.START)
                .executionId(executionId)
                .addData("traceId", traceId)
                .build();
    }

    /**
     * Cria um evento de fim de tracing.
     *
     * @param executionId ID da execução
     * @param totalSpans Total de spans coletados
     * @return ArchflowEvent
     */
    public static ArchflowEvent end(String executionId, int totalSpans) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.AUDIT)
                .type(ArchflowEventType.END)
                .executionId(executionId)
                .addData("totalSpans", totalSpans)
                .build();
    }
}
