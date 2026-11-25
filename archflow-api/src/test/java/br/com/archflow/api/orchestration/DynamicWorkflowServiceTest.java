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
        // A rota REST mede o uso (RCHFLW-75): o planejador pede a resposta inteira, de onde sai o uso.
        when(model.chat(anyMessages()))
                .thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("task one\ntask two")).build());
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

    /**
     * O planejador resolvia o LLM sem tenant ("nenhum tenant"): nem a chave do tenant nem os modelos
     * permitidos dele (RCHFLW-79) valiam. O tenant é o do contexto de execução.
     */
    @Test
    void resolvesThePlannerModelForTheExecutionTenant() {
        LLMConfigResolver resolver = mock(LLMConfigResolver.class);
        java.util.List<br.com.archflow.langchain4j.provider.LLMResolutionRequest> pedidos =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        when(resolver.resolveModel(any())).thenAnswer(inv -> {
            pedidos.add(inv.getArgument(0));
            throw new IllegalStateException("[LLM-MODELO] parou no teste");
        });
        DynamicWorkflowService service = new DynamicWorkflowService(resolver,
                new ResolvedLLMConfig("test", "m", 0.0, 256, 30, Map.of()),
                mock(ComponentCatalog.class), mock(ComponentQueryRouter.class), mock(ConfidenceScorer.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.run(
                        new DynamicWorkflowRequest("audit", null, null, null, null, null, null, null, "acme")))
                .hasMessageContaining("[LLM-MODELO]");

        assertThat(pedidos).singleElement()
                .extracting(br.com.archflow.langchain4j.provider.LLMResolutionRequest::tenantId)
                .isEqualTo("acme");
    }

    /**
     * RCHFLW-75: o planejador chamava {@code model::chat} (texto entra, texto sai) e o uso que o
     * provedor devolvia se perdia — o passo ORCHESTRATE gastava a chave do tenant sem aparecer no
     * contador da execução. Agora cada chamada do planejador relata ao contador do contexto.
     */
    @Test
    void plannerCallsAreCountedInTheFlowCounter() {
        Fixture f = new Fixture();
        br.com.archflow.model.engine.ExecutionContext ctx = new br.com.archflow.model.engine.DefaultExecutionContext(
                "acme", "flow-1", "exec-1",
                dev.langchain4j.memory.chat.MessageWindowChatMemory.builder().maxMessages(20).build());
        br.com.archflow.api.agent.mcp.ContadorDeUso uso = new br.com.archflow.api.agent.mcp.ContadorDeUso();
        br.com.archflow.api.agent.mcp.ContadorDeUso.injetar(ctx, uso);

        f.service.runOn(new DynamicWorkflowRequest("audit secrets", null, 10, 1, 1, 5, 4, null, null), ctx);

        var resumo = uso.resumo().orElseThrow();
        // round 1 + 2 rodadas secas = 3 chamadas ao planejador, 10/5 tokens cada.
        assertThat(resumo.turnos()).isEqualTo(3);
        assertThat(resumo.tokensEntrada()).isEqualTo(30L);
        assertThat(resumo.tokensSaida()).isEqualTo(15L);
        assertThat(resumo.modelo()).isEqualTo("test/m");
    }

    /**
     * RCHFLW-75: {@code POST /api/orchestration/run} roda fora do motor e não tinha contador nenhum —
     * o consumo não entrava no uso medido do tenant.
     */
    @Test
    void restRunRegistersTheMeasuredUsageForTheTenant() {
        Fixture f = new Fixture();
        br.com.archflow.api.usage.RegistroDeUso registro = new br.com.archflow.api.usage.RegistroDeUso(
                new br.com.archflow.api.usage.InMemoryRepositorioDeUso(), null);
        f.service.setRegistroDeUso(registro);

        f.service.run(new DynamicWorkflowRequest("audit secrets", null, 10, 1, 1, 5, 4, null, "acme"));

        var doMes = registro.doTenantNoMes("acme", registro.mesAtual());
        assertThat(doMes.execucoes()).isEqualTo(1);
        assertThat(doMes.tokensEntrada()).isEqualTo(30L);
        assertThat(doMes.tokensSaida()).isEqualTo(15L);
    }

    /** {@code any()} tipado para o {@code chat(List<ChatMessage>)} — sem ele a sobrecarga é ambígua. */
    private static List<dev.langchain4j.data.message.ChatMessage> anyMessages() {
        return any();
    }

    /** O serviço com um planejador que responde duas subtarefas e informa 10/5 tokens por chamada. */
    private static final class Fixture {
        final DynamicWorkflowService service;

        Fixture() {
            LLMConfigResolver resolver = mock(LLMConfigResolver.class);
            ComponentCatalog catalog = mock(ComponentCatalog.class);
            ComponentQueryRouter router = mock(ComponentQueryRouter.class);
            ConfidenceScorer scorer = mock(ConfidenceScorer.class);
            ChatModel model = mock(ChatModel.class);
            AIAgent agent = mock(AIAgent.class);
            ResolvedLLMConfig platformDefault = new ResolvedLLMConfig("test", "m", 0.0, 256, 30, Map.of());

            when(resolver.resolveModel(any())).thenReturn(model);
            when(resolver.resolve(any())).thenReturn(platformDefault);
            dev.langchain4j.model.chat.response.ChatResponse response =
                    dev.langchain4j.model.chat.response.ChatResponse.builder()
                            .aiMessage(dev.langchain4j.data.message.AiMessage.from("task one\ntask two"))
                            .tokenUsage(new dev.langchain4j.model.output.TokenUsage(10, 5))
                            .build();
            when(model.chat(anyMessages()))
                    .thenReturn(response);
            when(router.route(anyString(), eq(ComponentType.AGENT)))
                    .thenReturn(Optional.of(new ScoredComponent("agent", null, 0.9)));
            when(catalog.getComponent("agent")).thenReturn(Optional.of(agent));
            when(agent.executeTask(any(), any()))
                    .thenReturn(br.com.archflow.model.ai.domain.Result.success("done"));
            when(scorer.score(any())).thenReturn(new ConfidenceResult(0.9, List.of(), false, "ok"));
            service = new DynamicWorkflowService(resolver, platformDefault, catalog, router, scorer);
        }
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
