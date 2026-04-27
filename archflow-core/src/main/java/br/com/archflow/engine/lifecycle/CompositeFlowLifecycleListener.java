package br.com.archflow.engine.lifecycle;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStep;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fan-out {@link FlowLifecycleListener} that forwards every callback
 * to a list of delegates. A failure in any delegate is logged but does
 * not stop the others from being invoked — lifecycle notifications
 * are strictly advisory and must never break flow execution.
 *
 * <p>Listeners can be added or removed at runtime, which lets Spring
 * beans or external integrations hook in after the engine has been
 * constructed. The underlying list is a
 * {@link CopyOnWriteArrayList} so concurrent reads during flow events
 * never block concurrent mutations.</p>
 */
public class CompositeFlowLifecycleListener implements FlowLifecycleListener {

    private static final Logger logger = Logger.getLogger(CompositeFlowLifecycleListener.class.getName());

    private final List<FlowLifecycleListener> delegates;

    public CompositeFlowLifecycleListener() {
        this.delegates = new CopyOnWriteArrayList<>();
    }

    public CompositeFlowLifecycleListener(List<FlowLifecycleListener> initial) {
        this.delegates = new CopyOnWriteArrayList<>(initial);
    }

    public void add(FlowLifecycleListener listener) {
        Objects.requireNonNull(listener, "listener");
        delegates.add(listener);
    }

    public void remove(FlowLifecycleListener listener) {
        delegates.remove(listener);
    }

    public int size() {
        return delegates.size();
    }

    private void forEach(String phase, java.util.function.Consumer<FlowLifecycleListener> fn) {
        for (FlowLifecycleListener d : delegates) {
            try {
                fn.accept(d);
            } catch (Throwable t) {
                logger.log(Level.WARNING,
                        "Lifecycle listener " + d.getClass().getName() + " failed on " + phase
                                + ": " + t.getMessage(), t);
            }
        }
    }

    @Override public void onFlowStarted(Flow flow, ExecutionContext context, int stepCount) {
        forEach("onFlowStarted", d -> d.onFlowStarted(flow, context, stepCount));
    }
    @Override public void onFlowCompleted(Flow flow, ExecutionContext context, FlowResult result, long durationMs) {
        forEach("onFlowCompleted", d -> d.onFlowCompleted(flow, context, result, durationMs));
    }
    @Override public void onFlowFailed(Flow flow, ExecutionContext context, Throwable error, long durationMs) {
        forEach("onFlowFailed", d -> d.onFlowFailed(flow, context, error, durationMs));
    }
    @Override public void onStepStarted(Flow flow, FlowStep step, ExecutionContext context,
                                        int stepIndex, int stepCount) {
        forEach("onStepStarted", d -> d.onStepStarted(flow, step, context, stepIndex, stepCount));
    }
    @Override public void onStepCompleted(Flow flow, FlowStep step, ExecutionContext context, long durationMs) {
        forEach("onStepCompleted", d -> d.onStepCompleted(flow, step, context, durationMs));
    }
    @Override public void onStepFailed(Flow flow, FlowStep step, ExecutionContext context,
                                       Throwable error, long durationMs) {
        forEach("onStepFailed", d -> d.onStepFailed(flow, step, context, error, durationMs));
    }
    @Override public void onStepSkipped(Flow flow, FlowStep step, ExecutionContext context) {
        forEach("onStepSkipped", d -> d.onStepSkipped(flow, step, context));
    }
}
