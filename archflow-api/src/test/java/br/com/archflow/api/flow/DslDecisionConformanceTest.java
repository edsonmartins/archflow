package br.com.archflow.api.flow;

import br.com.archflow.dsl.Decision;
import br.com.archflow.dsl.Nodes;
import br.com.archflow.dsl.WorkflowDocument;
import br.com.archflow.dsl.Workflows;
import br.com.archflow.engine.execution.ConditionEvaluator;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * DSL → nó {@code decision}: o documento que {@link Decision} emite é o que o
 * {@code DecisionComponent} lê, e as arestas por rota ({@code route(...)}) viram a condição que o
 * motor avalia.
 *
 * <p>Como nas outras conformidades, nada aqui compara com um JSON esperado: o documento passa pelo
 * desserializador real, o passo <b>executa</b> (provedor de regras: determinístico, sem rede) e o
 * que se olha é o contexto que sai do outro lado e as {@link StepConnection} do passo.</p>
 */
@DisplayName("DSL → nó decision: a config emitida é a que o nó executa")
class DslDecisionConformanceTest {

    private final WorkflowDeserializer deserializer = new DefaultWorkflowDeserializer(
            new DefaultFlowStepFactory(mock(ComponentCatalog.class), null, null, null, null));

    private static WorkflowDocument fluxo() {
        return Workflows.define("triagem")
                .step("triagem", Decision.node()
                        .provider("rules")
                        .choice("time", "Quem cuida?", Map.of("pagamento", "cobrança", "conta", "login"))
                        .yesNo("urgente", "É urgente?")
                        .primary("time")
                        .gate("time")
                        .thresholds(0.9, 0.5)
                        .providerOptions("rules", Map.of("rules", Map.of(
                                "time", Map.of("match", Map.of("pagamento", List.of("pix", "boleto"),
                                        "conta", List.of("senha")), "otherwise", "conta"),
                                "urgente", Map.of("matchAny", List.of("urgente", "fora do ar"))))))
                .step("resolve", Nodes.component("resolver"))
                .step("humano", Nodes.approval())
                .route("triagem", "resolve", Decision.AUTO)
                .route("triagem", "humano", Decision.REVIEW)
                .route("triagem", "humano", Decision.ESCALATE)
                .build();
    }

    private static FlowStep passo(Flow flow, String id) {
        return flow.getSteps().stream().filter(s -> id.equals(s.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("passo ausente: " + id));
    }

    private static boolean aresta(StepConnection c, ExecutionContext ctx) {
        return new ConditionEvaluator(true).evaluate(c.getCondition().orElseThrow(), ctx);
    }

    @Test
    @DisplayName("o nó executa com as perguntas, o primary e o gate escritos na DSL")
    void executa() {
        Flow flow = deserializer.toFlow(fluxo().toMap());
        ExecutionContext ctx = new DefaultExecutionContext("t1", null, null, null);
        ctx.set("input", "o pix não passou");

        passo(flow, "triagem").execute(ctx).join();

        @SuppressWarnings("unchecked")
        Map<String, Object> saida = (Map<String, Object>) ctx.get("triagem").orElseThrow();
        assertThat(saida).containsEntry("route", "AUTO").containsEntry("decision", "pagamento")
                .containsEntry("primary", "time").containsEntry("provider", "rules");
        @SuppressWarnings("unchecked")
        Map<String, Object> answers = (Map<String, Object>) saida.get("answers");
        assertThat(answers).containsKeys("time", "urgente");
    }

    /** O ramo declarado na DSL é convertido pelo MOTOR na condição — a DSL não a escreve. */
    @Test
    @DisplayName("route(...) vira a condição ${passo.route} == '…', avaliada pelo motor")
    void arestasPorRota() {
        Flow flow = deserializer.toFlow(fluxo().toMap());
        List<StepConnection> arestas = passo(flow, "triagem").getConnections();
        assertThat(arestas).extracting(c -> c.getCondition().orElse(null))
                .containsExactly("${triagem.route} == 'AUTO'", "${triagem.route} == 'REVIEW'",
                        "${triagem.route} == 'ESCALATE'");

        ExecutionContext segura = new DefaultExecutionContext("t1", null, null, null);
        segura.set("input", "o pix não passou");
        passo(flow, "triagem").execute(segura).join();
        assertThat(aresta(arestas.get(0), segura)).as("AUTO → resolve").isTrue();
        assertThat(aresta(arestas.get(2), segura)).as("não escala").isFalse();

        ExecutionContext palpite = new DefaultExecutionContext("t1", null, null, null);
        palpite.set("input", "bom dia");
        passo(flow, "triagem").execute(palpite).join();
        assertThat(aresta(arestas.get(0), palpite)).isFalse();
        assertThat(aresta(arestas.get(2), palpite)).as("otherwise tem confiança 0: escala").isTrue();
    }
}
