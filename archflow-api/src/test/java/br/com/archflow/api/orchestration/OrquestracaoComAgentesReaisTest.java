package br.com.archflow.api.orchestration;

import br.com.archflow.agent.confidence.ConfidenceResult;
import br.com.archflow.agent.confidence.ConfidenceScorer;
import br.com.archflow.agent.orchestration.CatalogAgentWorker;
import br.com.archflow.api.agent.mcp.McpAgentComponent;
import br.com.archflow.api.flow.CatalogComponents;
import br.com.archflow.api.flow.OrchestrateStep;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.orchestration.Result;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import br.com.archflow.plugin.api.catalog.DefaultComponentQueryRouter;
import br.com.archflow.plugins.agents.ConversationalAgent;
import br.com.archflow.plugins.agents.DataAnalysisAgent;
import br.com.archflow.plugins.agents.MonitoringAgent;
import br.com.archflow.plugins.agents.ResearchAgent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-61 com os agentes REAIS do catálogo, registrados como o {@code seedBuiltIns} os registra.
 *
 * <p>O {@code CatalogAgentWorkerTest} usa um AIAgent mockado que aceita qualquer {@code Task} — por isso
 * nada pegou que o worker mandava {@code orchestration.subtask}, um tipo que nenhum dos quatro agentes
 * aceita, e que o mcp-agent era recusado com "not an AIAgent". Aqui o catálogo, o roteador e os agentes
 * são os de produção; só o planejador (LLM) e o verificador são roteirizados.</p>
 */
@DisplayName("Orquestração dinâmica com os agentes reais do catálogo")
class OrquestracaoComAgentesReaisTest {

    private ComponentCatalog catalog;
    private ComponentQueryRouter router;
    private ExecutionContext ctx;

    @BeforeEach
    void setup() {
        catalog = new DefaultComponentCatalog();
        for (AIComponent c : List.<AIComponent>of(new ConversationalAgent(), new ResearchAgent(),
                new DataAnalysisAgent(), new MonitoringAgent())) {
            CatalogComponents.initializeOnRegistration(c);
            catalog.register(c);
        }
        // Como no seedBuiltIns: o mcp-agent entra sem initialize (não tem systemPrompt sem um nó).
        catalog.register(new McpAgentComponent());
        router = new DefaultComponentQueryRouter(catalog);
        ctx = new DefaultExecutionContext("acme", "flow-1", "exec-1",
                MessageWindowChatMemory.builder().maxMessages(20).build());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "research and planning for freight carriers, research-agent, research",
            "customer sales question about the carriers, conversational-agent, conversation",
            "sql query visualization and forecasting of freight cost, data-analysis-agent, analyze",
            "monitoring and alerting of carrier delays, monitoring-agent, monitor",
    })
    void eachRealAgentAcceptsTheSubtaskItIsRoutedTo(String subtask, String agentId, String taskType) {
        assertThat(router.route(subtask, ComponentType.AGENT)).get()
                .extracting(ComponentQueryRouter.ScoredComponent::componentId).isEqualTo(agentId);

        Result<Object> result = new CatalogAgentWorker(router, catalog, ctx).apply(subtask);

        assertThat(result.ok()).as("erro: %s", result.error()).isTrue();
        assertThat(result.value()).isInstanceOfSatisfying(Map.class,
                out -> assertThat(out).containsEntry("task_type", taskType));
    }

    @Test
    void theConversationalAgentReadsTheSubtaskText() {
        Result<Object> result = new CatalogAgentWorker(router, catalog, ctx)
                .apply("customer sales question about the carriers");

        // O conversacional lê "request"; com o texto só em "description" ele respondia a uma pergunta vazia.
        assertThat(result.value()).isInstanceOfSatisfying(Map.class,
                out -> assertThat(out.get("response")).asString().isNotBlank());
    }

    @Test
    void noAgentMatchedIsAnExplicitFailure() {
        Result<Object> result = new CatalogAgentWorker(router, catalog, ctx).apply("zzz qqq");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("no agent matched subtask: zzz qqq");
    }

    @Test
    void theMcpAgentRunsThroughExecuteAndFailsWithItsOwnReason() {
        assertThat(router.route("mcp tools", ComponentType.AGENT)).get()
                .extracting(ComponentQueryRouter.ScoredComponent::componentId).isEqualTo("mcp-agent");

        Result<Object> result = new CatalogAgentWorker(router, catalog, ctx).apply("mcp tools");

        // Antes: "component is not an AIAgent: mcp-agent". Agora ele é executado; sem um nó que lhe dê
        // systemPrompt ele recusa — e o motivo é o dele, não o do worker.
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).startsWith("mcp-agent: ").doesNotContain("not an AIAgent")
                .contains("não inicializado");
    }

    @Test
    void labsResponseCountsAcceptedAndFailedSubtasks() {
        DynamicWorkflowService service = service("research and planning for freight carriers\nzzz qqq");

        DynamicWorkflowResponse response = service.run(request("Comparar fornecedores de frete"));

        assertThat(response.confirmedCount()).isEqualTo(1);
        assertThat(response.subtasks()).isEqualTo(2);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.failures()).singleElement().satisfies(f -> {
            assertThat(f.subtask()).isEqualTo("zzz qqq");
            assertThat(f.error()).contains("no agent matched");
        });
        assertThat(response.trace()).anyMatch(e -> e.type().equals("verified"));
        assertThat(response.trace()).anyMatch(e -> e.type().equals("failed") && e.detail().contains("zzz qqq"));
    }

    @Test
    void labsResponseSaysEverySubtaskFailedAndWhy() {
        DynamicWorkflowService service = service("zzz qqq\nmcp tools");

        DynamicWorkflowResponse response = service.run(request("Comparar fornecedores de frete"));

        assertThat(response.confirmedCount()).isZero();
        assertThat(response.subtasks()).isEqualTo(2);
        assertThat(response.failedCount()).isEqualTo(2);
        assertThat(response.failures()).extracting(f -> f.error())
                .anyMatch(e -> e.contains("no agent matched"))
                .anyMatch(e -> e.startsWith("mcp-agent: "));
        assertThat(response.trace()).noneMatch(e -> e.type().equals("verified"));
    }

    @Test
    void anOrchestrateStepWhoseSubtasksAllFailIsAFailedStepWithTheReasons() {
        DynamicWorkflowService service = service("zzz qqq\nmcp tools");
        var step = new OrchestrateStep("o1", List.of(), Map.of("goal", "Comparar fornecedores de frete"),
                service, null, null);

        StepResult result = step.execute(ctx).join();

        // Antes: COMPLETED com [] e o fluxo seguia.
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getErrors()).singleElement().extracting(e -> e.message()).asString()
                .contains("2 subtarefa(s) falharam")
                .contains("no agent matched subtask: zzz qqq")
                .contains("mcp-agent: ");
        assertThat(ctx.get("o1")).isEmpty();
    }

    /**
     * RCHFLW-64 com os agentes reais: a linha de despacho do trace diz qual agente do catálogo atendeu
     * cada subtarefa — inclusive a que nenhum atendeu. RCHFLW-62: os agentes de demonstração não
     * informam {@code tokensUsed}, e isso vira aviso de uso desconhecido, não zero calado.
     */
    @Test
    void labsTraceNamesTheRealAgentOfEachSubtaskAndFlagsTheirUnreportedUsage() {
        DynamicWorkflowService service = service("research and planning for freight carriers\nzzz qqq");

        DynamicWorkflowResponse response = service.run(request("Comparar fornecedores de frete"));

        assertThat(response.trace()).filteredOn(e -> e.type().equals("dispatched"))
                .extracting(e -> e.componentId())
                .containsExactlyInAnyOrder("research-agent", null);
        assertThat(response.trace()).filteredOn(e -> e.type().equals("usage-unknown") && e.componentId() != null)
                .singleElement().satisfies(e -> assertThat(e.componentId()).isEqualTo("research-agent"));
        // O planejador roteirizado aqui não informa uso (ChatResponse sem tokenUsage): um aviso por rodada.
        assertThat(response.trace()).filteredOn(e -> e.type().equals("usage-unknown") && e.componentId() == null)
                .hasSize(response.rounds())
                .allSatisfy(e -> assertThat(e.detail()).startsWith("planner: "));
        assertThat(response.usageUnknown()).isEqualTo(response.rounds() + 1);
        assertThat(response.tokensSpent()).isZero();
    }

    /**
     * RCHFLW-64 no estado persistido: o ramo de cada subtarefa no {@code ExecutionPath} guarda o componente
     * que o roteador escolheu — é o que torna "nenhum sub-agente fora de allowedComponents" verificável.
     */
    @Test
    void anOrchestrateStepRecordsTheChosenAgentOnEachSubtaskPath() {
        DynamicWorkflowService service = service("research and planning for freight carriers\nzzz qqq");
        ((DefaultExecutionContext) ctx).setState(
                br.com.archflow.model.flow.FlowState.builder().flowId("flow-1").build());
        var step = new OrchestrateStep("o1", List.of(), Map.of("goal", "Comparar fornecedores de frete"),
                service, null, null);

        step.execute(ctx).join();

        var branches = ctx.getState().getExecutionPaths().get(0).getParallelBranches();
        assertThat(branches).filteredOn(b -> b.getPathId().startsWith("research")).singleElement()
                .satisfies(b -> assertThat(b.getMetadata())
                        .containsEntry("componentId", "research-agent")
                        .containsEntry("success", true)
                        .containsKey("score"));
        assertThat(branches).filteredOn(b -> b.getPathId().equals("zzz qqq")).singleElement()
                .satisfies(b -> assertThat(b.getMetadata())
                        .containsEntry("componentId", null)
                        .containsEntry("success", false));
    }

    private DynamicWorkflowService service(String plannerLines) {
        LLMConfigResolver resolver = mock(LLMConfigResolver.class);
        ChatModel model = mock(ChatModel.class);
        when(resolver.resolveModel(any())).thenReturn(model);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn(plannerLines);
        when(model.chat(org.mockito.ArgumentMatchers.<List<dev.langchain4j.data.message.ChatMessage>>any()))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(plannerLines)).build());
        ConfidenceScorer scorer = mock(ConfidenceScorer.class);
        when(scorer.score(any())).thenReturn(new ConfidenceResult(0.9, List.of(), false, "ok"));
        return new DynamicWorkflowService(resolver, new ResolvedLLMConfig("test", "m", 0.0, 256, 30, Map.of()),
                catalog, router, scorer);
    }

    private static DynamicWorkflowRequest request(String goal) {
        return new DynamicWorkflowRequest(goal, null, 8, 1, 1, 5, 1, null, "acme");
    }
}
