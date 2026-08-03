package br.com.archflow.api.flow;

import br.com.archflow.agent.tool.ToolContext;
import br.com.archflow.agent.tool.ToolInterceptorChain;
import br.com.archflow.agent.tool.ToolResult;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;

import java.util.List;
import java.util.Map;
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
    private final ToolInterceptorChain interceptors;
    private final AIComponent resolved;
    private final Map<String, Object> configuration;

    public ComponentStep(String id, StepType type, String componentId, String operation,
                         List<StepConnection> connections, ComponentCatalog catalog) {
        this(id, type, componentId, operation, connections, catalog, null);
    }

    public ComponentStep(String id, StepType type, String componentId, String operation,
                         List<StepConnection> connections, ComponentCatalog catalog,
                         ToolInterceptorChain interceptors) {
        this(id, type, componentId, operation, connections, catalog, interceptors, null);
    }

    /**
     * @param resolved componente já materializado; quando presente, o catálogo
     *                 não é consultado. É o caminho dos nós servidos por um
     *                 adapter LangChain4j, que não vive no catálogo — ele é
     *                 construído a partir da config <b>deste</b> nó, e um
     *                 singleton compartilhado não poderia carregar config de nó.
     */
    public ComponentStep(String id, StepType type, String componentId, String operation,
                         List<StepConnection> connections, ComponentCatalog catalog,
                         ToolInterceptorChain interceptors, AIComponent resolved) {
        this(id, type, componentId, operation, connections, catalog, interceptors, resolved,
                Map.of());
    }

    public ComponentStep(String id, StepType type, String componentId, String operation,
                         List<StepConnection> connections, ComponentCatalog catalog,
                         ToolInterceptorChain interceptors, AIComponent resolved,
                         Map<String, Object> configuration) {
        this.id = id;
        this.type = type;
        this.componentId = componentId;
        this.operation = operation;
        this.connections = connections == null ? List.of() : List.copyOf(connections);
        this.catalog = catalog;
        this.interceptors = interceptors;
        this.resolved = resolved;
        this.configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }

    @Override public String getId() { return id; }
    @Override public StepType getType() { return type; }
    @Override public List<StepConnection> getConnections() { return connections; }
    @Override public LLMConfigPatch getLLMPatch() { return LLMConfigPatch.fromMap(configuration); }

    Map<String, Object> getConfiguration() { return configuration; }

    @Override
    public CompletableFuture<StepResult> execute(ExecutionContext context) {
        long start = System.nanoTime();
        AIComponent component = resolved != null
                ? resolved
                : catalog.getComponent(componentId).orElse(null);
        if (component == null) {
            return CompletableFuture.completedFuture(
                    SimpleStepResult.failed(id, "component not found: " + componentId, elapsedMs(start)));
        }
        try {
            Object input = context.get(INPUT_KEY).orElse(null);
            Object output = invoke(component, input, context);
            context.set(id, output);
            context.set(INPUT_KEY, output);
            return CompletableFuture.completedFuture(SimpleStepResult.ok(id, output, elapsedMs(start)));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return CompletableFuture.completedFuture(SimpleStepResult.failed(id, msg, elapsedMs(start)));
        }
    }

    /**
     * Invoca o componente através da {@link ToolInterceptorChain} quando há uma
     * configurada, senão direto.
     *
     * <p>Este era o segundo ponto de invocação de tool sem nenhuma interceptação:
     * a cadeia existia completa (com {@code beforeExecute} capaz de abortar) e não
     * tinha chamador em produção. Ligá-la aqui dá ao caminho de workflow o mesmo
     * veto pré-invocação que o laço MCP ganhou com a {@code ToolAccessPolicy}.
     *
     * <p>Um interceptor que aborta vira {@link ToolResult.Status#ERROR}, que o
     * step traduz em falha — o componente não roda.
     */
    private Object invoke(AIComponent component, Object input, ExecutionContext context)
            throws Exception {
        if (interceptors == null || interceptors.getInterceptors().isEmpty()) {
            return component.execute(operation, input, context);
        }

        ToolContext toolContext = ToolContext.builder()
                .executionId(id)
                .toolName(componentId)
                .input(input)
                .executionContext(context)
                .build();

        ToolResult<?> result = ToolInterceptorChain.builder()
                .addInterceptors(interceptors.getInterceptors())
                .toolExecutor(tc -> ToolResult.success(
                        component.execute(operation, tc.getInput(), context)))
                .build()
                .execute(toolContext);

        if (result.isError()) {
            throw new IllegalStateException(result.getMessage()
                    .orElse("tool interceptor rejeitou a execução de " + componentId));
        }
        return result.getData().orElse(null);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
