package br.com.archflow.agent.orchestration;

import br.com.archflow.model.ai.AIAgent;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.domain.Task;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.orchestration.Result;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter.ScoredComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogAgentWorkerTest {

    private ComponentQueryRouter router;
    private ComponentCatalog catalog;
    private ExecutionContext context;
    private CatalogAgentWorker worker;

    @BeforeEach
    void setup() {
        router = mock(ComponentQueryRouter.class);
        catalog = mock(ComponentCatalog.class);
        context = mock(ExecutionContext.class);
        worker = new CatalogAgentWorker(router, catalog, context);
    }

    @Test
    void routesSubtaskToBestAgentAndReturnsItsOutput() {
        AIAgent agent = mock(AIAgent.class);
        when(router.route("classify ticket", ComponentType.AGENT))
                .thenReturn(Optional.of(new ScoredComponent("classifier", null, 0.9)));
        when(catalog.getComponent("classifier")).thenReturn(Optional.of(agent));
        when(agent.executeTask(any(Task.class), eq(context)))
                .thenReturn(br.com.archflow.model.ai.domain.Result.success("BUG"));

        Result<Object> result = worker.apply("classify ticket");

        assertThat(result.ok()).isTrue();
        assertThat(result.value()).isEqualTo("BUG");
    }

    @Test
    void failsWhenNoAgentMatches() {
        when(router.route(any(), eq(ComponentType.AGENT))).thenReturn(Optional.empty());

        Result<Object> result = worker.apply("something");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("no agent matched");
    }

    @Test
    void failsWhenComponentIsNotAnAgent() {
        when(router.route(any(), eq(ComponentType.AGENT)))
                .thenReturn(Optional.of(new ScoredComponent("plugin-x", null, 0.5)));
        when(catalog.getComponent("plugin-x")).thenReturn(Optional.of(mock(AIComponent.class)));

        Result<Object> result = worker.apply("do");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not an AIAgent");
    }

    @Test
    void propagatesAgentFailure() {
        AIAgent agent = mock(AIAgent.class);
        when(router.route(any(), eq(ComponentType.AGENT)))
                .thenReturn(Optional.of(new ScoredComponent("a", null, 0.8)));
        when(catalog.getComponent("a")).thenReturn(Optional.of(agent));
        when(agent.executeTask(any(Task.class), any()))
                .thenReturn(br.com.archflow.model.ai.domain.Result.failure("rate limited"));

        Result<Object> result = worker.apply("do");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("rate limited");
    }

    @Test
    void extractsUsageFromAgentMetadataForBudgeting() {
        AIAgent agent = mock(AIAgent.class);
        when(router.route(any(), eq(ComponentType.AGENT)))
                .thenReturn(Optional.of(new ScoredComponent("a", null, 0.8)));
        when(catalog.getComponent("a")).thenReturn(Optional.of(agent));
        var domainResult = new br.com.archflow.model.ai.domain.Result(
                true, "ok", Map.of("tokensUsed", 1200, "costUsd", 0.03), java.util.List.of());
        when(agent.executeTask(any(Task.class), any())).thenReturn(domainResult);

        Result<Object> result = worker.apply("do");

        assertThat(result.usage().tokens()).isEqualTo(1200L);
        assertThat(result.usage().costUsd()).isEqualTo(0.03);
    }
}
