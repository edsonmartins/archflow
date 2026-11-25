package br.com.archflow.api.flow;

import br.com.archflow.api.agui.AgUiSeedInputAccess;
import br.com.archflow.api.agui.RunAgentInput;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RCHFLW-101: um nó Condição com {@code ${input} != ''}, executado com a entrada vazia, seguia o ramo
 * {@code true}. A entrada vazia deixava o {@code input} indefinido, {@code ${input}} resolvia para
 * {@code null} e {@code null != ''} é verdadeiro — a checagem "tem entrada?" dava sim justamente quando
 * não havia entrada.
 *
 * <p>Agora todo caminho que semeia a entrada ({@link RunInputSeeding}) define {@code input = ""}: o
 * Executar do editor (AG-UI, sem {@code state}), o da lista e o "Executar novamente" (REST, sem corpo
 * ou com {@code {}}) e o MCP ({@code tools/call} sem argumentos). O teste passa pelo nó Condição real
 * e pela aresta do ramo, como o motor avalia.</p>
 */
@DisplayName("Entrada vazia: input \"\" em todos os caminhos (RCHFLW-101)")
class EntradaVaziaTest {

    private final DefaultFlowStepFactory factory = new DefaultFlowStepFactory(
            mock(ComponentCatalog.class), null, null, null, null);

    private static ExecutionContext ctx() {
        return new DefaultExecutionContext("t1", "u", "exec-1", null);
    }

    private FlowStep temEntrada() {
        return factory.create(Map.of("id", "tem", "type", "condition", "componentId", "condition",
                "configuration", Map.of("conditionExpression", "${input} != ''"),
                "connections", List.of(
                        Map.of("targetId", "processar", "branch", "true"),
                        Map.of("targetId", "pedir", "branch", "false"))));
    }

    /** Qual aresta segue depois do nó, como o motor avalia a condição derivada do ramo. */
    private String destino(ExecutionContext c) {
        FlowStep passo = temEntrada();
        passo.execute(c).join();
        ConditionEvaluator avaliador = new ConditionEvaluator(true);
        return passo.getConnections().stream()
                .filter(aresta -> avaliador.evaluate(aresta.getCondition().orElse(null), c))
                .map(StepConnection::getTargetId)
                .reduce((a, b) -> a + "," + b)
                .orElse("nenhum");
    }

    private static Map<String, Consumer<ExecutionContext>> caminhosSemEntrada() {
        return Map.of(
                "REST sem corpo", c -> RunInputSeeding.seedVariables(c, null),
                "REST/MCP com {}", c -> RunInputSeeding.seedVariables(c, Map.of()),
                "AG-UI sem state nem mensagem (Executar do editor)",
                c -> AgUiSeedInputAccess.seed(c, new RunAgentInput(null, null, null, null, null, null)),
                "AG-UI com state {}",
                c -> AgUiSeedInputAccess.seed(c, new RunAgentInput(List.of(), Map.of(), null, null, null, null)));
    }

    @Test
    @DisplayName("sem entrada, por qualquer caminho: input \"\" e a condição segue o ramo false")
    void semEntradaSegueFalse() {
        caminhosSemEntrada().forEach((caminho, semear) -> {
            ExecutionContext c = ctx();
            semear.accept(c);

            assertThat(c.get("input")).as(caminho).contains("");
            assertThat(destino(c)).as(caminho).isEqualTo("pedir");
        });
    }

    @Test
    @DisplayName("com entrada (texto ou variáveis): a condição segue o ramo true, como antes")
    void comEntradaSegueTrue() {
        ExecutionContext texto = ctx();
        AgUiSeedInputAccess.seed(texto, new RunAgentInput(
                List.of(Map.of("role", "user", "content", "pedido 123")), null, null, null, null, null));
        assertThat(destino(texto)).isEqualTo("processar");

        ExecutionContext variaveis = ctx();
        RunInputSeeding.seedVariables(variaveis, Map.of("input", "pedido 123"));
        assertThat(destino(variaveis)).isEqualTo("processar");
    }
}
