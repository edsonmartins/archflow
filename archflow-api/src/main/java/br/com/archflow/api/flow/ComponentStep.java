package br.com.archflow.api.flow;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Executable {@link FlowStep} for component-backed nodes (ASSISTANT/AGENT/TOOL):
 * resolves {@code componentId} from the {@link ComponentCatalog} and invokes
 * {@code AIComponent.execute(operation, input, ctx)} (design-0004 step 1).
 *
 * <p>Linear data flow: the step reads the run's current {@code "input"} variable,
 * runs, then writes its output back to {@code "input"} and to a per-step key, so
 * the next step sees it.
 */
public final class ComponentStep implements FlowStep {

    static final String INPUT_KEY = "input";

    private final String id;
    private final StepType type;
    private final String componentId;
    private final String operation;
    private final List<StepConnection> connections;
    private final ComponentCatalog catalog;

    public ComponentStep(String id, StepType type, String componentId, String operation,
                         List<StepConnection> connections, ComponentCatalog catalog) {
        this.id = id;
        this.type = type;
        this.componentId = componentId;
        this.operation = operation;
        this.connections = connections == null ? List.of() : List.copyOf(connections);
        this.catalog = catalog;
    }

    @Override public String getId() { return id; }
    @Override public StepType getType() { return type; }
    @Override public List<StepConnection> getConnections() { return connections; }

    @Override
    public CompletableFuture<StepResult> execute(ExecutionContext context) {
        long start = System.nanoTime();
        AIComponent component = catalog.getComponent(componentId).orElse(null);
        if (component == null) {
            return CompletableFuture.completedFuture(
                    SimpleStepResult.failed(id, "component not found: " + componentId, elapsedMs(start)));
        }
        try {
            Object input = context.get(INPUT_KEY).orElse(null);
            Object output = component.execute(operation, input, context);
            context.set(id, output);
            context.set(INPUT_KEY, output);
            return CompletableFuture.completedFuture(SimpleStepResult.ok(id, output, elapsedMs(start)));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return CompletableFuture.completedFuture(SimpleStepResult.failed(id, msg, elapsedMs(start)));
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
