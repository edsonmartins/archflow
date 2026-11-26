package br.com.archflow.api.flow;

import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.engine.validation.DefaultFlowValidator;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RCHFLW-147: renomear um caso do switch ("arrependimento" → "arrependido"), à mão, pelo copiloto ou pelo Gerar com
 * IA, deixava a aresta no ramo antigo. Como o switch só grava um caso declarado ou {@code default}, a aresta nunca
 * era seguida, e a requisição que caía no caso novo terminava COMPLETED sem resposta ao cliente — o caso casado sem
 * aresta é o fim deliberado do {@link ControlStep}, então só a validação antes do primeiro passo pega.
 */
@DisplayName("ExecutableNodesValidator: aresta num ramo que o nó não declara")
class RamoNaoDeclaradoNaValidacaoTest {

    private static Map<String, Object> aresta(String alvo, String ramo) {
        Map<String, Object> a = new HashMap<>();
        a.put("targetId", alvo);
        if (ramo != null) {
            a.put("branch", ramo);
        }
        return a;
    }

    private static Map<String, Object> no(String id, String type, String label, Map<String, Object> config,
                                          List<Map<String, Object>> arestas) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("componentId", type);
        node.put("label", label);
        node.put("configuration", config);
        node.put("connections", arestas);
        return node;
    }

    /** Entrada → Switch (casos {@code casos}) → as saídas pelos ramos dados. */
    private static Flow fluxo(String casos, List<Map<String, Object>> arestasDoSwitch) {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);
        return new DefaultWorkflowDeserializer(factory).toFlow(Map.of("id", "devolucao", "metadata",
                Map.of("name", "devolucao"), "steps", List.of(
                        no("entrada", "input", "Entrada", Map.of(), List.of(aresta("sw", null))),
                        no("sw", "switch", "Motivo", Map.of("switchExpression", "${entrada.motivo}",
                                "switchCases", casos), arestasDoSwitch),
                        no("defeito", "output", "Troca", Map.of(), List.of()),
                        no("resposta", "output", "Resposta ao cliente", Map.of(), List.of()),
                        no("outro", "output", "Outro", Map.of(), List.of()))));
    }

    private static void valida(Flow flow) throws FlowValidationException {
        new ExecutableNodesValidator(new DefaultFlowValidator()).validate(flow);
    }

    @Test
    @DisplayName("caso renomeado: a aresta no nome antigo vira aviso (não recusa o run), nomeando o nó, o ramo e os ramos que existem")
    void casoRenomeadoAvisado() {
        Flow flow = fluxo("defeito\narrependido\ndefault", List.of(aresta("defeito", "defeito"),
                aresta("resposta", "arrependimento"), aresta("outro", "default")));

        // Só aviso: o run não é recusado (fluxos publicados e chamados pela API seguem como antes).
        assertThatCode(() -> valida(flow)).doesNotThrowAnyException();
        assertThat(ExecutableNodesValidator.undeclaredBranches(flow)).singleElement()
                        .satisfies(err -> {
                            assertThat(err.message())
                                    .contains("a aresta de \"Motivo\" (sw) para resposta está no ramo «arrependimento»")
                                    .contains("ramos: defeito, arrependido, default");
                            assertThat(err.code()).isEqualTo(ExecutableNodesValidator.BRANCH_CODE);
                            assertThat(err.context()).containsEntry("node", "sw")
                                    .containsEntry("branch", "arrependimento")
                                    .containsEntry("target", "resposta");
                        });
    }

    @Test
    @DisplayName("ramos declarados, o default implícito e a condição escrita à mão passam")
    void ramosValidos() {
        Map<String, Object> manual = aresta("outro", "inexistente");
        manual.put("condition", "${entrada.valor} > 3");
        Map<String, Object> erro = aresta("outro", "qualquer");
        erro.put("isErrorPath", true);
        Flow flow = fluxo("defeito\narrependido", List.of(aresta("defeito", "defeito"),
                aresta("resposta", "arrependido"), aresta("outro", "default"), manual, erro));

        assertThatCode(() -> valida(flow)).doesNotThrowAnyException();
        assertThat(ExecutableNodesValidator.undeclaredBranches(flow)).isEmpty();
    }

    @Test
    @DisplayName("ramo com apóstrofo: comparado sem ele, como a BranchCondition — e o designer dá a mesma resposta")
    void apostrofoComoNaBranchCondition() {
        // A aresta "d'água" vira `== 'dágua'`, que o caso "d'água" nunca satisfaz; o caso "dágua" a alcança.
        Flow morta = fluxo("d'água", List.of(aresta("resposta", "d'água"), aresta("defeito", "default"),
                aresta("outro", "default")));
        Flow viva = fluxo("dágua", List.of(aresta("resposta", "d'água"), aresta("defeito", "default"),
                aresta("outro", "default")));

        assertThat(ExecutableNodesValidator.undeclaredBranches(morta)).singleElement()
                .satisfies(e -> assertThat(e.message())
                        .contains("está no ramo «d'água» (o ramo é comparado sem apóstrofos, como «dágua»)"));
        assertThat(ExecutableNodesValidator.undeclaredBranches(viva)).isEmpty();
    }

    @Test
    @DisplayName("condition só tem true/false")
    void conditionEDecision() {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);
        Flow flow = new DefaultWorkflowDeserializer(factory).toFlow(Map.of("id", "f", "metadata",
                Map.of("name", "f"), "steps", List.of(
                        no("entrada", "input", "Entrada", Map.of(), List.of(aresta("c", null))),
                        no("c", "condition", "Valor alto?", Map.of("conditionExpression", "${entrada.valor} > 3"),
                                List.of(aresta("s1", "true"), aresta("s2", "sim"))),
                        no("s1", "output", "S1", Map.of(), List.of()),
                        no("s2", "output", "S2", Map.of(), List.of()))));

        assertThat(ExecutableNodesValidator.undeclaredBranches(flow)).singleElement()
                .satisfies(e -> assertThat(e.message())
                        .contains("está no ramo «sim», que o nó não declara (ramos: true, false)"));
    }
}
