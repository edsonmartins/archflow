package br.com.archflow.api.orchestration;

import br.com.archflow.agent.confidence.ConfidenceResult;
import br.com.archflow.agent.confidence.ConfidenceScorer;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.model.ai.AIAgent;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter.ScoredComponent;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicWorkflowServiceTest {

    @Test
    void composesAndRunsAWorkflowEndToEnd() {
        LLMConfigResolver resolver = mock(LLMConfigResolver.class);
        ComponentCatalog catalog = mock(ComponentCatalog.class);
        ComponentQueryRouter router = mock(ComponentQueryRouter.class);
        ConfidenceScorer scorer = mock(ConfidenceScorer.class);
        ChatModel model = mock(ChatModel.class);
        AIAgent agent = mock(AIAgent.class);
        ResolvedLLMConfig platformDefault = new ResolvedLLMConfig("test", "m", 0.0, 256, 30, Map.of());

        when(resolver.resolveModel(any())).thenReturn(model);
        when(model.chat(anyString())).thenReturn("task one\ntask two");
        when(router.route(anyString(), eq(ComponentType.AGENT)))
                .thenReturn(Optional.of(new ScoredComponent("agent", null, 0.9)));
        when(catalog.getComponent("agent")).thenReturn(Optional.of(agent));
        when(agent.executeTask(any(), any()))
                .thenReturn(br.com.archflow.model.ai.domain.Result.success("done"));
        when(scorer.score(any())).thenReturn(new ConfidenceResult(0.9, List.of(), false, "ok"));

        DynamicWorkflowService service =
                new DynamicWorkflowService(resolver, platformDefault, catalog, router, scorer);

        DynamicWorkflowResponse response = service.run(
                new DynamicWorkflowRequest("audit secrets", null, 10, 1, 1, 5, 4, null, null));

        // Two subtasks, both routed to the agent and confirmed by the scorer.
        assertThat(response.confirmedCount()).isEqualTo(2);
        // round 1 productive; rounds 2-3 dry (planner repeats) -> converge.
        assertThat(response.rounds()).isEqualTo(3);
        verify(resolver).resolveModel(any());
    }

    @Test
    void rejectsBlankGoal() {
        DynamicWorkflowService service = new DynamicWorkflowService(
                mock(LLMConfigResolver.class),
                new ResolvedLLMConfig("test", "m", 0.0, 256, 30, Map.of()),
                mock(ComponentCatalog.class),
                mock(ComponentQueryRouter.class),
                mock(ConfidenceScorer.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.run(new DynamicWorkflowRequest("  ", null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
