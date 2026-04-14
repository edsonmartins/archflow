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
     * @param flowId      flow identifier
     * @param tenantId    tenant identifier
     * @param durationMs  total execution duration in milliseconds
     * @param executionId execution tracking ID
     * @return ArchflowEvent
     */
    public static ArchflowEvent flowCompleted(String flowId, String tenantId,
                                              long durationMs, String executionId) {
        return ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.FLOW_COMPLETED)
                .executionId(executionId)
                .tenantId(tenantId)
                .addData("flowId", flowId)
                .addData("durationMs", durationMs)
                .build();
    }

    /**
     * Creates a FLOW_FAILED event.
     *
     * @param flowId       flow identifier
     * @param tenantId     tenant identifier
     * @param errorMessage error message
     * @param errorType    simple class name of the exception
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
}
