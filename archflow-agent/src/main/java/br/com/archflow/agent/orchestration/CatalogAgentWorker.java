package br.com.archflow.agent.orchestration;

import br.com.archflow.model.ai.AIAgent;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.domain.Task;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.orchestration.Result;
import br.com.archflow.orchestration.Usage;
import br.com.archflow.orchestration.Worker;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter;

import java.util.Map;
import java.util.Optional;

/**
 * Orchestration {@link Worker} that picks the best-matching agent for each
 * subtask <em>at runtime</em> via {@link ComponentQueryRouter} and invokes it —
 * the dynamic "best agent for the next task" selection that distinguishes
 * archflow's catalog from a hand-wired pipeline (ADR-0002 D4 / design-0003).
 *
 * <p>Input is the subtask description (as produced by a {@code Planner}); output
 * is the agent's raw output. Token/cost {@link Usage} is read from the agent
 * Result's metadata ({@code tokensUsed} / {@code costUsd}) when present, so the
 * orchestrator's budget enforcement works for agents that report it.
 */
public final class CatalogAgentWorker implements Worker<String, Object> {

    private final ComponentQueryRouter router;
    private final ComponentCatalog catalog;
    private final ExecutionContext context;

    public CatalogAgentWorker(ComponentQueryRouter router, ComponentCatalog catalog, ExecutionContext context) {
        this.router = router;
        this.catalog = catalog;
        this.context = context;
    }

    @Override
    public Result<Object> apply(String subtask) {
        Optional<ComponentQueryRouter.ScoredComponent> scored = router.route(subtask, ComponentType.AGENT);
        if (scored.isEmpty()) {
            return Result.fail("no agent matched subtask: " + subtask);
        }
        String componentId = scored.get().componentId();
        AIComponent component = catalog.getComponent(componentId).orElse(null);
        if (!(component instanceof AIAgent agent)) {
            return Result.fail("component is not an AIAgent: " + componentId);
        }

        Task task = Task.of("orchestration.subtask", Map.of("description", subtask));
        br.com.archflow.model.ai.domain.Result result = agent.executeTask(task, context);
        if (result == null || !result.success()) {
            String msg = (result != null && !result.messages().isEmpty())
                    ? result.messages().get(0)
                    : "agent execution failed: " + componentId;
            return Result.fail(msg);
        }
        return Result.success(result.output(), usageFrom(result));
    }

    private static Usage usageFrom(br.com.archflow.model.ai.domain.Result result) {
        long tokens = asLong(result.metadata().get("tokensUsed"));
        double cost = asDouble(result.metadata().get("costUsd"));
        return new Usage(tokens, cost);
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
