package br.com.archflow.api.flow;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.dsl.Nodes;
import br.com.archflow.dsl.WorkflowDocument;
import br.com.archflow.dsl.Workflows;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * O documento que a DSL emite é executável pelo motor de verdade.
 *
 * <p><b>Por que este teste existe.</b> O schema do documento de workflow já era
 * conhecido em dois lugares — o {@link DefaultFlowStepFactory}, que o lê, e os
 * POJOs {@code Serializable*} do archflow-standalone, que o transportam — sem
 * nada que os mantivesse sincronizados. A DSL é o terceiro. Um builder que
 * emite JSON plausível mas que o motor lê torto é pior que não ter builder: o
 * erro aparece na execução, não na compilação, que é exatamente o que a DSL
 * promete evitar.
 *
 * <p>Então a conformidade não é verificada contra uma cópia do schema nem
 * contra um JSON esperado escrito à mão — é verificada passando a saída da DSL
 * pelo {@link DefaultWorkflowDeserializer} real e olhando o {@link Flow} que
 * sai do outro lado. Se o motor mudar como lê o documento, este teste quebra
 * antes de qualquer usuário da DSL descobrir.
 *
 * <p>O catálogo é mock porque aqui não se testa se o componente existe — isso
 * depende do runtime. Testa-se a <b>forma</b>: os passos, os tipos e o grafo.
 */
@DisplayName("DSL → motor: o documento emitido é executável")
class DslConformanceTest {

    private final WorkflowDeserializer deserializer = new DefaultWorkflowDeserializer(
            new DefaultFlowStepFactory(mock(ComponentCatalog.class), mock(DynamicWorkflowService.class),
                    mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                    mock(br.com.archflow.engine.core.StateManager.class), null));

    private static FlowStep stepById(Flow flow, String id) {
        return flow.getSteps().stream()
                .filter(s -> id.equals(s.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("passo ausente no flow: " + id));
    }

    /**
     * A asserção central: as arestas escritas com {@code .edge(...)} chegam ao
     * motor como {@link StepConnection} do passo de origem.
     *
     * <p>Se a DSL emitisse o {@code connections} no topo do documento — a forma
     * dos templates da UI — este teste veria um flow com todos os passos e
     * <b>nenhuma</b> conexão, que é precisamente o modo de falhar em silêncio
     * que se quer impedir: o fluxo carrega, executa o primeiro passo e para.
     */
    @Test
    @DisplayName("as arestas viram StepConnection no passo de origem")
    void edgesBecomeConnections() {
        WorkflowDocument doc = Workflows.define("rag")
                .named("Q&A")
                .step("pergunta", Nodes.component("input"))
                .step("busca", Nodes.component("vector-search"))
                .step("resposta", Nodes.component("output"))
                .edge("pergunta", "busca")
                .edge("busca", "resposta")
                .build();

        Flow flow = deserializer.toFlow(doc.toMap());

        assertThat(flow.getId()).isEqualTo("rag");
        assertThat(flow.getMetadata().name()).isEqualTo("Q&A");
        assertThat(flow.getSteps()).extracting(FlowStep::getId)
                .containsExactly("pergunta", "busca", "resposta");

        List<StepConnection> daPergunta = stepById(flow, "pergunta").getConnections();
        assertThat(daPergunta).singleElement().satisfies(c -> {
            assertThat(c.getSourceId()).isEqualTo("pergunta");
            assertThat(c.getTargetId()).isEqualTo("busca");
            assertThat(c.isErrorPath()).isFalse();
        });
        assertThat(stepById(flow, "resposta").getConnections())
                .as("o último passo não tem saída")
                .isEmpty();
    }

    @Test
    @DisplayName("condição e caminho de erro sobrevivem à travessia")
    void conditionAndErrorPathSurvive() {
        WorkflowDocument doc = Workflows.define("router")
                .step("route", Nodes.component("semantic-router"))
                .step("billing", Nodes.component("agent"))
                .step("falhou", Nodes.component("notify"))
                .edge("route", "billing", "route == 'billing'")
                .onError("route", "falhou")
                .build();

        Flow flow = deserializer.toFlow(doc.toMap());

        List<StepConnection> saidas = stepById(flow, "route").getConnections();
        assertThat(saidas).hasSize(2);
        assertThat(saidas.get(0).getCondition()).contains("route == 'billing'");
        assertThat(saidas.get(0).isErrorPath()).isFalse();
        assertThat(saidas.get(1).getTargetId()).isEqualTo("falhou");
        assertThat(saidas.get(1).isErrorPath())
                .as("onError precisa chegar como caminho de erro, senão vira aresta normal")
                .isTrue();
    }

    /**
     * {@code Nodes.approval()} e {@code Nodes.orchestrate()} prometem um passo
     * do próprio motor — não um componente do catálogo. A promessa só vale se o
     * factory construir a classe dedicada.
     */
    @Test
    @DisplayName("approval() e orchestrate() constroem os passos dedicados do motor")
    void engineStepsAreBuiltByTheirOwnClasses() {
        WorkflowDocument doc = Workflows.define("gated")
                .step("gate", Nodes.approval())
                .step("orq", Nodes.orchestrate())
                .step("comum", Nodes.component("qualquer"))
                .build();

        Flow flow = deserializer.toFlow(doc.toMap());

        assertThat(stepById(flow, "gate"))
                .as("um APPROVAL que virasse ComponentStep não suspenderia o fluxo")
                .isInstanceOf(HumanApprovalStep.class);
        assertThat(stepById(flow, "orq")).isInstanceOf(OrchestrateStep.class);
        assertThat(stepById(flow, "comum")).isInstanceOf(ComponentStep.class);
    }

    @Test
    @DisplayName("o tipo do passo comum chega como TOOL, que é o que o factory atribui")
    void plainStepsAreTools() {
        Flow flow = deserializer.toFlow(
                Workflows.define("f").step("a", Nodes.component("x")).build().toMap());

        assertThat(stepById(flow, "a").getType()).isEqualTo(StepType.TOOL);
    }

    /**
     * O JSON é o formato em que o documento realmente viaja (document store,
     * arquivo em git, corpo de request). Um mapa que atravessa em memória mas
     * não sobrevive à serialização não serviria.
     */
    @Test
    @DisplayName("o mesmo vale depois de passar por JSON de verdade")
    void survivesJsonRoundTrip() throws Exception {
        WorkflowDocument doc = Workflows.define("f")
                .step("a", Nodes.llmChat("openai").with("model", "gpt-4o"))
                .step("b", Nodes.component("output"))
                .edge("a", "b")
                .build();

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> reread = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(doc.toJson(), java.util.Map.class);
        Flow flow = deserializer.toFlow(reread);

        assertThat(flow.getSteps()).extracting(FlowStep::getId).containsExactly("a", "b");
        assertThat(stepById(flow, "a").getConnections()).singleElement()
                .satisfies(c -> assertThat(c.getTargetId()).isEqualTo("b"));
    }
}
