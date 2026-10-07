package br.com.archflow.api.flow;

import br.com.archflow.engine.execution.ConditionEvaluator;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
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
 * Os nós {@code condition} e {@code switch} do designer deixam de existir só na tela: viram passos
 * que calculam o ramo, e a aresta que declara o seu {@code branch} segue (ou não) por ele.
 */
@DisplayName("nós de controle: condition, switch e o ramo da aresta")
class NosDeControleTest {

    private final DefaultFlowStepFactory factory = new DefaultFlowStepFactory(
            mock(ComponentCatalog.class), null, null, null, null);

    private static ExecutionContext ctx() {
        return new DefaultExecutionContext("t1", null, null, null);
    }

    private static boolean segue(StepConnection aresta, ExecutionContext ctx) {
        return new ConditionEvaluator(true).evaluate(aresta.getCondition().orElse(null), ctx);
    }

    private FlowStep condicao(String expressao) {
        return factory.create(Map.of("id", "decide", "type", "CONDITION", "componentId", "condition",
                "configuration", Map.of("conditionExpression", expressao),
                "connections", List.of(
                        Map.of("targetId", "sim", "branch", "true"),
                        Map.of("targetId", "nao", "branch", "false"))));
    }

    @Test
    @DisplayName("condition: avalia a expressão e a aresta do ramo certo segue; a outra não")
    void condition() {
        ExecutionContext c = ctx();
        c.set("agent", Map.of("confidence", 0.93));
        FlowStep passo = condicao("${agent.confidence} > 0.8");

        passo.execute(c).join();

        assertThat(passo).isInstanceOf(ControlStep.class);
        assertThat(c.get("decide").map(Object::toString).orElse("")).contains("branch=true");
        List<StepConnection> arestas = passo.getConnections();
        assertThat(segue(arestas.get(0), c)).as("ramo true").isTrue();
        assertThat(segue(arestas.get(1), c)).as("ramo false").isFalse();
    }

    @Test
    @DisplayName("condition falsa vira o ramo false; expressão que não se avalia também — nunca o verdadeiro")
    void conditionFalsaEQuebrada() {
        ExecutionContext c = ctx();
        c.set("agent", Map.of("confidence", 0.3));
        FlowStep passo = condicao("${agent.confidence} > 0.8");
        passo.execute(c).join();
        assertThat(segue(passo.getConnections().get(1), c)).isTrue();

        ExecutionContext c2 = ctx();
        FlowStep quebrada = condicao("${agent.confidence} >");
        quebrada.execute(c2).join();
        assertThat(segue(quebrada.getConnections().get(0), c2))
                .as("expressão quebrada não segue pelo 'sim'").isFalse();
    }

    @Test
    @DisplayName("a entrada segue intacta: o nó de controle decide o caminho, não transforma o dado")
    void preservaAEntrada() {
        ExecutionContext c = ctx();
        c.set("input", "texto original");
        c.set("agent", Map.of("confidence", 1.0));

        condicao("${agent.confidence} > 0.5").execute(c).join();

        assertThat(c.get("input")).contains("texto original");
    }

    @Test
    @DisplayName("switch: o valor casa com um caso (sem diferenciar maiúsculas); senão, 'default'")
    void switchPorCaso() {
        FlowStep passo = factory.create(Map.of("id", "rota", "type", "SWITCH", "componentId", "switch",
                "configuration", Map.of("switchExpression", "${agent.intent}",
                        "switchCases", "order_tracking\ncomplaint\nsales"),
                "connections", List.of(
                        Map.of("targetId", "a", "branch", "complaint"),
                        Map.of("targetId", "b", "branch", "sales"),
                        Map.of("targetId", "c", "branch", "default"))));

        ExecutionContext reclamacao = ctx();
        reclamacao.set("agent", Map.of("intent", "Complaint"));
        passo.execute(reclamacao).join();
        assertThat(passo.getConnections().stream().map(a -> segue(a, reclamacao)).toList())
                .containsExactly(true, false, false);

        ExecutionContext outro = ctx();
        outro.set("agent", Map.of("intent", "smalltalk"));
        passo.execute(outro).join();
        assertThat(passo.getConnections().stream().map(a -> segue(a, outro)).toList())
                .containsExactly(false, false, true);
    }

    @Test
    @DisplayName("expressão obrigatória ausente falha o passo, com o motivo")
    void semExpressao() {
        FlowStep passo = factory.create(Map.of("id", "x", "type", "CONDITION", "componentId", "condition"));
        var resultado = passo.execute(ctx()).join();

        assertThat(resultado.getStatus()).isEqualTo(br.com.archflow.model.enums.StepStatus.FAILED);
    }

    @Test
    @DisplayName("aresta de um nó decision declara o ramo pela rota; condição escrita à mão vence o ramo")
    void ramoDoNoDeDecisao() {
        FlowStep passo = factory.create(Map.of("id", "triagem", "type", "decision", "componentId", "decision",
                "config", Map.of("provider", "rules", "questions", Map.of("t", Map.of("type", "noul"))),
                "connections", List.of(
                        Map.of("targetId", "a", "branch", "AUTO"),
                        Map.of("targetId", "b", "branch", "ESCALATE"),
                        Map.of("targetId", "c", "branch", "AUTO", "condition", "${x} == 1"))));

        List<StepConnection> arestas = passo.getConnections();
        assertThat(arestas.get(0).getCondition()).contains("${triagem.route} == 'AUTO'");
        assertThat(arestas.get(1).getCondition()).contains("${triagem.route} == 'ESCALATE'");
        assertThat(arestas.get(2).getCondition()).as("a condição manual vence").contains("${x} == 1");
    }

    @Test
    @DisplayName("ramo em nó que não tem ramos é ignorado; aspas no ramo não quebram a expressão")
    void ramoInvalido() {
        assertThat(DefaultFlowStepFactory.conditionForBranch("s", "llm-chat", "x")).isNull();
        assertThat(DefaultFlowStepFactory.conditionForBranch("s", "switch", " ")).isNull();
        assertThat(DefaultFlowStepFactory.conditionForBranch("s", "switch", "a'b"))
                .isEqualTo("${s.branch} == 'ab'");
    }
}
