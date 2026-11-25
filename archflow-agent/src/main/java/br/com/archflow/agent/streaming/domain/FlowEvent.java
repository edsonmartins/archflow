package br.com.archflow.agent.streaming.domain;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;

/**
 * Factory for flow and step lifecycle events in the {@link ArchflowDomain#FLOW} domain.
 *
 * <p>Follow the same static-factory pattern as {@link ToolEvent} and {@link SystemEvent}.
 *
 * <h3>Example usage:</h3>
 * <pre>{@code
 * // Flow started
 * registry.broadcast(flowId, FlowEvent.flowStarted(flowId, tenantId, stepCount));
 *
 * // Step completed
 * registry.broadcast(flowId, FlowEvent.stepCompleted(flowId, stepId, stepIndex, durationMs));
 * }</pre>
 */
public final class FlowEvent {

    private FlowEvent() {}

    // ----------------------------------------------------------------
    // Flow-level events
    // ----------------------------------------------------------------

    /**
     * Creates a FLOW_STARTED event.
     *
     * @param flowId      flow identifier
     * @param tenantId    tenant identifier (may be null for single-tenant)
     * @param stepCount   total number of steps in the flow
     * @param executionId execution tracking ID (typically same as flowId)
     * @return ArchflowEvent
     */
    public static ArchflowEvent flowStarted(String flowId, String tenantId,
                                            int stepCount, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.FLOW_STARTED)
                .executionId(executionId)
                .tenantId(tenantId)
                .addData("flowId", flowId)
                .addData("stepCount", stepCount)
                .build();
    }

    /**
     * Creates a FLOW_COMPLETED event.
     *
     * <p>O motor encerra a travessia "sem exceção" também quando o fluxo terminou
     * CANCELLED ou PAUSED (aguardando aprovação). Sem o status no evento, quem consome o
     * stream lia todos esses desfechos como sucesso (RCHFLW-33). FAILED não passa por
     * aqui: vira {@link #flowFailed}.
     *
     * @param flowId      flow identifier
     * @param tenantId    tenant identifier
     * @param status      final status of the run ({@code ExecutionStatus} name: COMPLETED,
     *                    CANCELLED, PAUSED)
     * @param durationMs  total execution duration in milliseconds
     * @param executionId execution tracking ID
     * @return ArchflowEvent
     */
    public static ArchflowEvent flowCompleted(String flowId, String tenantId, String status,
                                              long durationMs, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.FLOW_COMPLETED)
                .executionId(executionId)
                .tenantId(tenantId)
                .addData("flowId", flowId)
                .addData("status", status)
                .addData("durationMs", durationMs)
                .build();
    }

    /**
     * Creates a FLOW_FAILED event.
     *
     * <p>Duas origens, dois vocabulários em {@code errorType}: quando o motor lança, é o nome
     * simples da classe da exceção (ex.: {@code FlowEngineException}); quando o fluxo volta como
     * {@code FlowResult} FAILED (um passo falhou sem aresta de erro — RCHFLW-33), não há exceção
     * nenhuma — o executor não repassa a causa do passo —, e o valor é o {@code ExecutionErrorType}
     * do primeiro erro do resultado (ex.: {@code EXECUTION}). {@code "unknown"} quando nenhum dos
     * dois existe. Quem agrupa ou filtra por esse campo precisa tratar os dois conjuntos.
     *
     * @param flowId       flow identifier
     * @param tenantId     tenant identifier
     * @param errorMessage error message
     * @param errorType    exception simple class name, or {@code ExecutionErrorType} name for a
     *                     FAILED result (see above)
     * @param durationMs   total execution duration until failure in milliseconds
     * @param executionId  execution tracking ID
     * @return ArchflowEvent
     */
    public static ArchflowEvent flowFailed(String flowId, String tenantId,
                                           String errorMessage, String errorType,
                                           long durationMs, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.FLOW_FAILED)
                .executionId(executionId)
                .tenantId(tenantId)
                .addData("flowId", flowId)
                .addData("error", errorMessage)
                .addData("errorType", errorType)
                .addData("durationMs", durationMs)
                .build();
    }

    // ----------------------------------------------------------------
    // Step-level events
    // ----------------------------------------------------------------

    /**
     * Creates a STEP_STARTED event.
     *
     * @param flowId      flow identifier
     * @param stepId      step identifier
     * @param stepName    human-readable step name
     * @param stepIndex   0-based index of this step
     * @param stepCount   total number of steps in the flow
     * @param executionId execution tracking ID
     * @return ArchflowEvent
     */
    public static ArchflowEvent stepStarted(String flowId, String stepId, String stepName,
                                            int stepIndex, int stepCount, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.STEP_STARTED)
                .executionId(executionId)
                .addData("flowId", flowId)
                .addData("stepId", stepId)
                .addData("stepName", stepName)
                .addData("stepIndex", stepIndex)
                .addData("stepCount", stepCount)
                .build();
    }

    /**
     * Creates a STEP_COMPLETED event.
     *
     * @param flowId      flow identifier
     * @param stepId      step identifier
     * @param stepIndex   0-based index of this step
     * @param durationMs  duration of this step in milliseconds
     * @param executionId execution tracking ID
     * @return ArchflowEvent
     */
    public static ArchflowEvent stepCompleted(String flowId, String stepId,
                                              int stepIndex, long durationMs,
                                              String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.STEP_COMPLETED)
                .executionId(executionId)
                .addData("flowId", flowId)
                .addData("stepId", stepId)
                .addData("stepIndex", stepIndex)
                .addData("durationMs", durationMs)
                .build();
    }

    /**
     * Creates a STEP_FAILED event.
     *
     * @param flowId       flow identifier
     * @param stepId       step identifier
     * @param stepIndex    0-based index of this step
     * @param errorMessage error message
     * @param errorType    simple class name of the exception
     * @param durationMs   duration until failure in milliseconds
     * @param executionId  execution tracking ID
     * @return ArchflowEvent
     */
    public static ArchflowEvent stepFailed(String flowId, String stepId, int stepIndex,
                                           String errorMessage, String errorType,
                                           long durationMs, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.STEP_FAILED)
                .executionId(executionId)
                .addData("flowId", flowId)
                .addData("stepId", stepId)
                .addData("stepIndex", stepIndex)
                .addData("error", errorMessage)
                .addData("errorType", errorType)
                .addData("durationMs", durationMs)
                .build();
    }

    /**
     * Creates a STEP_SKIPPED event.
     *
     * @param flowId      flow identifier
     * @param stepId      step identifier
     * @param stepIndex   0-based index of this step
     * @param executionId execution tracking ID
     * @return ArchflowEvent
     */
    public static ArchflowEvent stepSkipped(String flowId, String stepId,
                                            int stepIndex, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.STEP_SKIPPED)
                .executionId(executionId)
                .addData("flowId", flowId)
                .addData("stepId", stepId)
                .addData("stepIndex", stepIndex)
                .build();
    }

    /**
     * Creates a STEP_SUSPENDED event: the step stopped the flow to wait (human approval).
     *
     * @param flowId      flow identifier
     * @param stepId      step identifier
     * @param stepIndex   0-based index of this step (the same one STEP_STARTED carried)
     * @param durationMs  time spent in the step until it suspended
     * @param executionId execution tracking ID
     * @return ArchflowEvent
     */
    public static ArchflowEvent stepSuspended(String flowId, String stepId,
                                              int stepIndex, long durationMs,
                                              String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.STEP_SUSPENDED)
                .executionId(executionId)
                .addData("flowId", flowId)
                .addData("stepId", stepId)
                .addData("stepIndex", stepIndex)
                .addData("durationMs", durationMs)
                .build();
    }
}
