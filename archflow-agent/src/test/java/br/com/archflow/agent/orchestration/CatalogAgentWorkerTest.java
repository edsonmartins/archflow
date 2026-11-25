package br.com.archflow.agent.orchestration;

import br.com.archflow.model.ai.AIAgent;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.domain.Task;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.orchestration.Result;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter.ScoredComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    /** Um AIAgent mockado que declara tipos — o contrato que o worker agora exige (RCHFLW-61). */
    private static AIAgent agentDeclaring(String... types) {
        AIAgent agent = mock(AIAgent.class);
        when(agent.supportedTaskTypes()).thenReturn(List.of(types));
        return agent;
    }

    private void routeTo(String componentId, AIComponent component) {
        when(router.route(any(), eq(ComponentType.AGENT)))
                .thenReturn(Optional.of(new ScoredComponent(componentId, null, 0.8)));
        when(catalog.getComponent(componentId)).thenReturn(Optional.of(component));
    }

    @Test
    void routesSubtaskToBestAgentAndReturnsItsOutput() {
        AIAgent agent = agentDeclaring("classify");
        when(router.route("classify ticket", ComponentType.AGENT))
                .thenReturn(Optional.of(new ScoredComponent("classifier", null, 0.9)));
        when(catalog.getComponent("classifier")).thenReturn(Optional.of(agent));
        // O sub-agente recebe um contexto RECORTADO (SubAgentContext), não o do
        // fluxo — antes recebia o contexto inteiro, com toda variável e a
        // conversa acumulada.
        when(agent.executeTask(any(Task.class), any(ExecutionContext.class)))
                .thenReturn(br.com.archflow.model.ai.domain.Result.success("BUG"));

        Result<Object> result = worker.apply("classify ticket");

        assertThat(result.ok()).isTrue();
        assertThat(result.value()).isEqualTo("BUG");

        var passed = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(agent).executeTask(any(Task.class), passed.capture());
        assertThat(passed.getValue())
                .as("delegar nao pode entregar o contexto do fluxo")
                .isNotSameAs(context);
    }

    /**
     * RCHFLW-61: o worker mandava {@code orchestration.subtask} com o texto em {@code description}, e
     * todo agente do catálogo recusava. A tarefa sai no tipo padrão que o agente declara, com o texto
     * também em {@code request} e {@code query}.
     */
    @Test
    void dispatchesTheAgentsDefaultTaskTypeWithTheTextUnderTheKeysAgentsRead() {
        AIAgent agent = agentDeclaring("research", "summarize");
        routeTo("research-agent", agent);
        when(agent.executeTask(any(Task.class), any()))
                .thenReturn(br.com.archflow.model.ai.domain.Result.success("ok"));

        worker.apply("compare three carriers");

        ArgumentCaptor<Task> task = ArgumentCaptor.forClass(Task.class);
        verify(agent).executeTask(task.capture(), any());
        assertThat(task.getValue().type()).isEqualTo("research");
        assertThat(task.getValue().parameters())
                .containsEntry("description", "compare three carriers")
                .containsEntry("request", "compare three carriers")
                .containsEntry("query", "compare three carriers");
    }

    @Test
    void refusesAnAgentThatDeclaresNoTaskTypesInsteadOfGuessingOne() {
        AIAgent agent = mock(AIAgent.class);
        when(agent.supportedTaskTypes()).thenReturn(List.of());
        routeTo("mute-agent", agent);

        Result<Object> result = worker.apply("do");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).startsWith("mute-agent: ").contains("supportedTaskTypes");
        verify(agent, never()).executeTask(any(), any());
    }

    @Test
    void failsWhenNoAgentMatches() {
        when(router.route(any(), eq(ComponentType.AGENT))).thenReturn(Optional.empty());

        Result<Object> result = worker.apply("something");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("no agent matched");
    }

    /**
     * RCHFLW-61: um componente AGENT que não é AIAgent (o mcp-agent) era recusado com "not an AIAgent".
     * Ele roda pelo {@code execute()} de AIComponent, com a operação padrão — o caminho dele num fluxo.
     */
    @Test
    void runsANonAgentComponentThroughExecuteWithItsDefaultOperation() throws Exception {
        AIComponent component = mock(AIComponent.class);
        when(component.getMetadata()).thenReturn(metadataWithOperations("execute"));
        when(component.execute(eq("execute"), eq("do it"), any())).thenReturn(Map.of("text", "feito"));
        routeTo("mcp-agent", component);

        Result<Object> result = worker.apply("do it");

        assertThat(result.ok()).isTrue();
        assertThat(result.value()).isEqualTo(Map.of("text", "feito"));
    }

    @Test
    void aNonAgentComponentThatThrowsFailsTheSubtaskWithItsReason() throws Exception {
        AIComponent component = mock(AIComponent.class);
        when(component.getMetadata()).thenReturn(metadataWithOperations("execute"));
        when(component.execute(anyString(), any(), any())).thenThrow(new IllegalStateException("não inicializado"));
        routeTo("mcp-agent", component);

        Result<Object> result = worker.apply("do");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("mcp-agent: não inicializado");
    }

    @Test
    void aNonAgentComponentWithoutTheOperationFailsListingWhatItHas() {
        AIComponent component = mock(AIComponent.class);
        when(component.getMetadata()).thenReturn(metadataWithOperations("transform"));
        routeTo("plugin-x", component);

        Result<Object> result = worker.apply("do");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).startsWith("plugin-x: ").contains("'execute'").contains("transform");
    }

    @Test
    void propagatesAgentFailureWithTheAgentId() {
        AIAgent agent = agentDeclaring("any");
        routeTo("a", agent);
        when(agent.executeTask(any(Task.class), any()))
                .thenReturn(br.com.archflow.model.ai.domain.Result.failure("rate limited"));

        Result<Object> result = worker.apply("do");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("a: rate limited");
    }

    @Test
    void anAgentThatThrowsFailsOnlyItsSubtask() {
        AIAgent agent = agentDeclaring("any");
        routeTo("a", agent);
        when(agent.executeTask(any(Task.class), any())).thenThrow(new IllegalArgumentException("boom"));

        Result<Object> result = worker.apply("do");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("a: boom");
    }

    @Test
    void extractsUsageFromAgentMetadataForBudgeting() {
        AIAgent agent = agentDeclaring("any");
        routeTo("a", agent);
        var domainResult = new br.com.archflow.model.ai.domain.Result(
                true, "ok", Map.of("tokensUsed", 1200, "costUsd", 0.03), List.of());
        when(agent.executeTask(any(Task.class), any())).thenReturn(domainResult);

        Result<Object> result = worker.apply("do");

        assertThat(result.usage().tokens()).isEqualTo(1200L);
        assertThat(result.usage().costUsd()).isEqualTo(0.03);
    }

    /** Escuta o que o worker emite, como "rodada|subtarefa|componente|score|sucesso". */
    private static final class Recorder implements OrchestrationListener {
        final List<String> dispatched = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> unknown = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onDispatched(int round, Object subtask, String componentId, Double score, boolean success) {
            dispatched.add(round + "|" + subtask + "|" + componentId + "|" + score + "|" + success);
        }

        @Override
        public void onUsageUnknown(int round, Object subtask, String source) {
            unknown.add(round + "|" + subtask + "|" + source);
        }
    }

    private CatalogAgentWorker listenedBy(OrchestrationListener listener) {
        return new CatalogAgentWorker(router, catalog, context, null, listener);
    }

    /**
     * RCHFLW-64: o componente escolhido pelo roteador era descartado no sucesso — o trace, o SSE e o estado
     * não diziam qual agente atendeu a subtarefa. Agora sai um evento de despacho, com a rodada.
     */
    @Test
    void reportsTheChosenComponentScoreAndSuccessOnEveryPath() {
        Recorder rec = new Recorder();
        CatalogAgentWorker w = listenedBy(rec);
        AIAgent ok = agentDeclaring("t");
        when(ok.executeTask(any(Task.class), any()))
                .thenReturn(br.com.archflow.model.ai.domain.Result.success("x"));
        AIAgent bad = agentDeclaring("t");
        when(bad.executeTask(any(Task.class), any()))
                .thenReturn(br.com.archflow.model.ai.domain.Result.failure("recusou"));
        when(router.route(eq("ok"), eq(ComponentType.AGENT)))
                .thenReturn(Optional.of(new ScoredComponent("agent-ok", null, 0.75)));
        when(router.route(eq("bad"), eq(ComponentType.AGENT)))
                .thenReturn(Optional.of(new ScoredComponent("agent-bad", null, 0.5)));
        when(router.route(eq("gone"), eq(ComponentType.AGENT)))
                .thenReturn(Optional.of(new ScoredComponent("agent-gone", null, 0.25)));
        when(router.route(eq("zzz"), eq(ComponentType.AGENT))).thenReturn(Optional.empty());
        when(catalog.getComponent("agent-ok")).thenReturn(Optional.of(ok));
        when(catalog.getComponent("agent-bad")).thenReturn(Optional.of(bad));
        when(catalog.getComponent("agent-gone")).thenReturn(Optional.empty());

        w.apply(3, "ok");
        w.apply(3, "bad");
        w.apply(3, "gone");
        w.apply(3, "zzz");

        assertThat(rec.dispatched).containsExactly(
                "3|ok|agent-ok|0.75|true",
                "3|bad|agent-bad|0.5|false",
                "3|gone|agent-gone|0.25|false",
                // Nenhum agente casou: o evento sai mesmo assim, sem componente nem score.
                "3|zzz|null|null|false");
    }

    /**
     * O roteador e o catálogo rodavam fora do try: uma exceção deles pulava o evento de despacho e a
     * subtarefa sumia do trace, do SSE e do estado. Agora o evento sai e a falha tem motivo.
     */
    @Test
    void aRouterOrCatalogExceptionStillReportsTheDispatchAndFailsWithTheReason() {
        Recorder rec = new Recorder();
        when(router.route(eq("boom"), eq(ComponentType.AGENT))).thenThrow(new IllegalStateException("índice"));
        when(router.route(eq("gone"), eq(ComponentType.AGENT)))
                .thenReturn(Optional.of(new ScoredComponent("agent-x", null, 0.5)));
        when(catalog.getComponent("agent-x")).thenThrow(new IllegalStateException("catálogo fora"));

        Result<Object> routerFailed = listenedBy(rec).apply(1, "boom");
        Result<Object> catalogFailed = listenedBy(rec).apply(1, "gone");

        assertThat(routerFailed.ok()).isFalse();
        assertThat(routerFailed.error()).contains("roteador").contains("índice");
        assertThat(catalogFailed.ok()).isFalse();
        assertThat(catalogFailed.error()).startsWith("agent-x: ").contains("catálogo fora");
        assertThat(rec.dispatched).containsExactly("1|boom|null|null|false", "1|gone|agent-x|0.5|false");
        // Nenhum componente chegou a rodar: não há uso a declarar desconhecido.
        assertThat(rec.unknown).isEmpty();
    }

    /** Fora do supervisor não há rodada: o {@code apply(item)} do núcleo despacha como rodada 0. */
    @Test
    void theCoreApplyDispatchesAsRoundZero() {
        Recorder rec = new Recorder();
        when(router.route(any(), eq(ComponentType.AGENT))).thenReturn(Optional.empty());

        listenedBy(rec).apply("zzz");

        assertThat(rec.dispatched).containsExactly("0|zzz|null|null|false");
    }

    /**
     * RCHFLW-62: o agente que não informa {@code tokensUsed} debitava zero em silêncio. Continua sem número
     * inventado, mas com aviso — e só quando o componente rodou: recusado antes de rodar não gastou.
     */
    @Test
    void anAgentThatRanWithoutReportingUsageIsFlaggedAsUnknown() {
        Recorder rec = new Recorder();
        AIAgent agent = agentDeclaring("t");
        routeTo("a", agent);
        when(agent.executeTask(any(Task.class), any()))
                .thenReturn(br.com.archflow.model.ai.domain.Result.success("x"));

        Result<Object> result = listenedBy(rec).apply(2, "do");

        assertThat(result.usage().tokens()).isZero();
        assertThat(rec.unknown).containsExactly("2|do|a");
    }

    @Test
    void reportedUsageIsNotFlaggedAndIsCarriedEvenOnFailure() {
        Recorder rec = new Recorder();
        AIAgent agent = agentDeclaring("t");
        routeTo("a", agent);
        // Gastou e falhou: o gasto existe, e o Result.fail do núcleo o perderia (debita zero).
        when(agent.executeTask(any(Task.class), any())).thenReturn(new br.com.archflow.model.ai.domain.Result(
                false, null, Map.of("tokensUsed", 300), List.of("estourou o contexto")));

        Result<Object> result = listenedBy(rec).apply(1, "do");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("a: estourou o contexto");
        assertThat(result.usage().tokens()).isEqualTo(300L);
        assertThat(rec.unknown).isEmpty();
    }

    @Test
    void aSubtaskNoComponentRanForHasNoUnknownUsage() {
        Recorder rec = new Recorder();
        // Agente que não declara tipos é recusado antes de rodar.
        routeTo("silent", agentDeclaring());
        when(router.route(eq("zzz"), eq(ComponentType.AGENT))).thenReturn(Optional.empty());

        listenedBy(rec).apply(1, "zzz");
        listenedBy(rec).apply(1, "silent");

        assertThat(rec.unknown).isEmpty();
    }

    /** A coluna "Agente" do Labs sai do trace: a linha de despacho e a de falha dizem o componente. */
    @Test
    void theTraceNamesTheAgentOnTheDispatchAndFailureRows() {
        OrchestrationTrace trace = new OrchestrationTrace();
        AIAgent agent = agentDeclaring("t");
        routeTo("research-agent", agent);
        when(agent.executeTask(any(Task.class), any()))
                .thenReturn(br.com.archflow.model.ai.domain.Result.failure("recusou"));

        Result<Object> result = listenedBy(trace).apply(1, "compare");
        trace.onWorkerFailed(1, "compare", result.error());

        assertThat(trace.entries()).extracting(OrchestrationTrace.Entry::type)
                .containsExactly("dispatched", "usage-unknown", "failed");
        assertThat(trace.entries()).allSatisfy(e -> assertThat(e.componentId()).isEqualTo("research-agent"));
        assertThat(trace.entries().get(0).detail()).isEqualTo("compare (score=0.80, success=false)");
    }

    private static ComponentMetadata metadataWithOperations(String... operations) {
        List<ComponentMetadata.OperationMetadata> ops = java.util.Arrays.stream(operations)
                .map(id -> new ComponentMetadata.OperationMetadata(id, id, id, List.of(), List.of()))
                .toList();
        return new ComponentMetadata("x", "x", "x", ComponentType.AGENT, "1", java.util.Set.of(), ops,
                Map.of(), java.util.Set.of());
    }
}
