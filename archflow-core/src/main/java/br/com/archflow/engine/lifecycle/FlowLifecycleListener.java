package br.com.archflow.engine.lifecycle;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStep;

/**
 * SPI for observing flow and step lifecycle events.
 *
 * <p>All methods have no-op defaults so implementors only override what they need.
 * The constant {@link #NO_OP} is provided to avoid null checks at injection sites.
 *
 * <p>Implementations must be thread-safe: callbacks are fired from virtual threads
 * and may arrive concurrently for different flows.
 *
 * <p>Exceptions thrown by implementations are swallowed by the engine — a listener
 * failure will never fail the flow.
 */
public interface FlowLifecycleListener {

    /**
     * Shared no-op sentinel — use instead of null.
     */
    FlowLifecycleListener NO_OP = new FlowLifecycleListener() {};

    // ----------------------------------------------------------------
    // Flow-level callbacks
    // ----------------------------------------------------------------

    /**
     * Called immediately before a flow's first step is executed.
     *
     * @param flow      the flow being started
     * @param context   execution context, includes state and variables
     * @param stepCount total number of steps in this flow
     */
    default void onFlowStarted(Flow flow, ExecutionContext context, int stepCount) {}

    /**
     * Called when a flow completes successfully.
     *
     * @param flow       the flow that completed
     * @param context    execution context
     * @param result     the successful result
     * @param durationMs wall-clock duration of the flow in milliseconds
     */
    default void onFlowCompleted(Flow flow, ExecutionContext context,
                                 FlowResult result, long durationMs) {}

    /**
     * Called when a flow fails with an exception.
     *
     * @param flow       the flow that failed
     * @param context    execution context
     * @param error      the exception that caused the failure
     * @param durationMs wall-clock duration until the failure in milliseconds
     */
    default void onFlowFailed(Flow flow, ExecutionContext context,
                              Throwable error, long durationMs) {}

    // ----------------------------------------------------------------
    // Step-level callbacks
    // ----------------------------------------------------------------

    /**
     * Called immediately before a step is executed.
     *
     * @param flow       the containing flow
     * @param step       the step about to execute
     * @param context    execution context
     * @param stepIndex  0-based index of this step in the flow
     * @param stepCount  total number of steps in the flow
     */
    default void onStepStarted(Flow flow, FlowStep step, ExecutionContext context,
                               int stepIndex, int stepCount) {}

    /**
     * Called when a step completes successfully.
     *
     * @param flow       the containing flow
     * @param step       the step that completed
     * @param context    execution context
     * @param durationMs wall-clock duration of this step in milliseconds
     */
    default void onStepCompleted(Flow flow, FlowStep step, ExecutionContext context,
                                 long durationMs) {}

    /**
     * Called when a step fails.
     *
     * @param flow       the containing flow
     * @param step       the step that failed
     * @param context    execution context
     * @param error      the exception or error cause
     * @param durationMs wall-clock duration until failure in milliseconds
     */
    default void onStepFailed(Flow flow, FlowStep step, ExecutionContext context,
                              Throwable error, long durationMs) {}

    /**
     * Called when a step is skipped (e.g. condition not met).
     *
     * @param flow    the containing flow
     * @param step    the step that was skipped
     * @param context execution context
     */
    default void onStepSkipped(Flow flow, FlowStep step, ExecutionContext context) {}
}
