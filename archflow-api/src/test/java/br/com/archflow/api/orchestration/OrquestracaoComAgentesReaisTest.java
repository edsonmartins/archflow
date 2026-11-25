package br.com.archflow.api.orchestration;

import br.com.archflow.agent.confidence.ConfidenceResult;
import br.com.archflow.agent.confidence.ConfidenceScorer;
import br.com.archflow.agent.orchestration.CatalogAgentWorker;
import br.com.archflow.agent.orchestration.OrchestrationTrace;
import br.com.archflow.agent.orchestration.SubAgentContext;
import br.com.archflow.agent.orchestration.SubAgentEligibility;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.EventStreamRegistry;
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
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    /**
     * RCHFLW-108: o critério de {@link SubAgentEligibility} contra o catálogo real. Dos agentes que o
     * {@code seedBuiltIns} registra, só o mcp-agent fica fora — e o id é conferido contra a constante do
     * componente, para um renome não reabrir o defeito em silêncio. Os outros quatro são exatamente os que
     * {@link #eachRealAgentAcceptsTheSubtaskItIsRoutedTo} mostra rodando como sub-agente.
     */
    @Test
    void theSubAgentCriterionExcludesExactlyTheMcpAgentFromTheRealCatalog() {
        assertThat(SubAgentEligibility.excludedIn(catalog))
                .extracting(SubAgentEligibility.Exclusion::componentId)
                .containsExactly(McpAgentComponent.COMPONENT_ID);
        assertThat(catalog.listComponents()).filteredOn(md -> md.type() == ComponentType.AGENT)
                .extracting(md -> md.id())
                .filteredOn(SubAgentEligibility::canRunAsSubAgent)
                .containsExactlyInAnyOrder("conversational-agent", "research-agent", "data-analysis-agent",
                        "monitoring-agent");
    }

    /**
     * A exclusão diz a verdade: a instância do catálogo, no contexto de sub-agente, falha antes de
     * qualquer trabalho — era o "mcp-agent não inicializado" de toda subtarefa enviada a ela no Labs.
     */
    @Test
    void theExcludedMcpAgentReallyCannotRunInTheSubAgentContext() {
        AIComponent mcp = catalog.getComponent(McpAgentComponent.COMPONENT_ID).orElseThrow();

        assertThatThrownBy(() -> mcp.execute("execute", "mcp tools", SubAgentContext.of(ctx)))
                .hasMessageContaining("não inicializado");
    }

    /** O mcp-agent é o melhor do roteador para "mcp tools", e mesmo assim não é escolhido. */
    @Test
    void theMcpAgentIsNeverChosenEvenWhenItIsTheRoutersBestMatch() {
        assertThat(router.route("mcp tools", ComponentType.AGENT)).get()
                .extracting(ComponentQueryRouter.ScoredComponent::componentId).isEqualTo("mcp-agent");

        Result<Object> result = new CatalogAgentWorker(router, catalog, ctx).apply("mcp tools");

        // Antes: "mcp-agent: mcp-agent não inicializado". Sem outro agente pontuado, a falha é a explícita
        // que já existia — não um score mínimo novo nem uma configuração inventada.
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("no agent matched subtask: mcp tools");
    }

    /**
     * Com o mcp-agent no topo e um agente que roda logo abaixo, a subtarefa vai para o de baixo — a ordem
     * do roteador entre os que rodam é mantida.
     */
    @Test
    void aSubtaskWhoseBestMatchIsTheMcpAgentGoesToTheNextRunnableAgent() {
        String subtask = "Comparar com um sql de custo os fornecedores de frete";
        assertThat(router.rank(subtask, ComponentType.AGENT))
                .extracting(ComponentQueryRouter.ScoredComponent::componentId)
                .startsWith("mcp-agent", "data-analysis-agent");

        Result<Object> result = new CatalogAgentWorker(router, catalog, ctx).apply(subtask);

        assertThat(result.ok()).as("erro: %s", result.error()).isTrue();
        assertThat(result.value()).isInstanceOfSatisfying(Map.class,
                out -> assertThat(out).containsEntry("task_type", "analyze"));
    }

    /**
     * RCHFLW-108 como foi visto no Labs: com o objetivo dos fornecedores de frete, o roteador mandava a
     * maioria das subtarefas para o mcp-agent, e todas falhavam. Agora nenhuma vai para ele; o trace diz,
     * uma vez e antes do planejamento, que ele ficou fora e por quê; as que têm um agente que roda chegam a
     * ele e são verificadas; as que só casavam com o mcp-agent falham com "no agent matched".
     */
    @Test
    void labsFreightGoalNeverDispatchesToTheMcpAgentAndTracesTheExclusionOnce() {
        DynamicWorkflowService service = service(String.join("\n",
                "Levantar os preços de frete de cada um dos três fornecedores",
                "Comparar com um sql de custo os fornecedores de frete",
                "Research the freight rates of each of the three carriers",
                "Recomendar um fornecedor com base na comparação",
                "Evaluate the customer service and support quality of each carrier",
                "Avaliar a confiabilidade e o histórico de cada fornecedor"));

        DynamicWorkflowResponse response = service.run(request("Comparar três fornecedores de frete e recomendar um"));

        assertThat(response.trace()).filteredOn(e -> e.type().equals("dispatched"))
                .extracting(e -> e.componentId())
                .doesNotContain(McpAgentComponent.COMPONENT_ID)
                .containsExactlyInAnyOrder("data-analysis-agent", "research-agent", "conversational-agent",
                        null, null, null);
        assertThat(response.trace().get(0)).satisfies(e -> {
            assertThat(e.type()).isEqualTo(OrchestrationTrace.EXCLUDED_FROM_ROUTING);
            assertThat(e.componentId()).isEqualTo(McpAgentComponent.COMPONENT_ID);
            assertThat(e.detail()).contains("sub-agente").contains("McpAgentHost");
        });
        assertThat(response.trace()).filteredOn(e -> e.type().equals(OrchestrationTrace.EXCLUDED_FROM_ROUTING))
                .hasSize(1);
        assertThat(response.confirmedCount()).isEqualTo(3);
        assertThat(response.failures()).hasSize(3)
                .allSatisfy(f -> assertThat(f.error()).startsWith("no agent matched subtask: "));
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
        // "mcp tools" ia para o mcp-agent e falhava com o motivo dele; fora do roteamento, nenhum agente casa.
        assertThat(response.failures()).extracting(f -> f.error())
                .containsExactlyInAnyOrder("no agent matched subtask: zzz qqq", "no agent matched subtask: mcp tools");
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
                .contains("no agent matched subtask: mcp tools")
                .doesNotContain("mcp-agent: ");
        assertThat(ctx.get("o1")).isEmpty();
    }

    /**
     * RCHFLW-108 no stream do nó: o {@code orchestrate} manda UM evento, antes do planejamento, dizendo que
     * o mcp-agent ficou fora do roteamento e por quê — passando pelo listener que materializa o estado, que
     * precisa repassá-lo ao stream.
     */
    @Test
    void anOrchestrateStepStreamsTheRoutingExclusionOnceBeforePlanning() {
        DynamicWorkflowService service = service("research and planning for freight carriers\nmcp tools");
        ((DefaultExecutionContext) ctx).setState(
                br.com.archflow.model.flow.FlowState.builder().flowId("flow-1").build());
        EventStreamRegistry registry = new EventStreamRegistry();
        List<ArchflowEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        registry.addGlobalListener(events::add);
        var step = new OrchestrateStep("o1", List.of(), Map.of("goal", "Comparar fornecedores de frete"),
                service, registry, null);

        try {
            step.execute(ctx).join();
        } finally {
            registry.shutdown();
        }

        List<Object> phases = events.stream().map(e -> e.getData().get("phase")).toList();
        assertThat(phases).first().isEqualTo("excluded-from-routing");
        assertThat(phases).filteredOn("excluded-from-routing"::equals).hasSize(1);
        assertThat(events.get(0).getData().get("excluded")).asInstanceOf(InstanceOfAssertFactories.LIST)
                .singleElement().isInstanceOfSatisfying(Map.class, m -> {
                    assertThat(m).containsEntry("componentId", McpAgentComponent.COMPONENT_ID);
                    assertThat(m.get("reason")).asString().contains("McpAgentHost");
                });
        assertThat(events).filteredOn(e -> "dispatched".equals(e.getData().get("phase")))
                .extracting(e -> e.getData().get("componentId"))
                .doesNotContain(McpAgentComponent.COMPONENT_ID);
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
