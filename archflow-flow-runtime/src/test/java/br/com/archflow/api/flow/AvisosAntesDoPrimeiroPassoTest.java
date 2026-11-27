package br.com.archflow.api.flow;

import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.engine.validation.DefaultFlowValidator;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * RCHFLW-201: o nó cujo componente o catálogo não resolve e o {@code condition}/{@code switch} sem expressão
 * passavam pela validação em silêncio — o run executava os passos anteriores (LLM pago) e só falhava ao chegar no
 * nó. Decisão: aviso no log, sem recusa, como o ramo não declarado (RCHFLW-147). Os fluxos publicados (VendaX, API)
 * seguem rodando exatamente como hoje.
 */
@DisplayName("ExecutableNodesValidator: avisos de componente não resolvido e expressão vazia")
class AvisosAntesDoPrimeiroPassoTest {

    private static Map<String, Object> no(String id, String type, String label, Map<String, Object> config,
                                          String alvo) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("componentId", type);
        if (label != null) {
            node.put("label", label);
        }
        node.put("configuration", config);
        node.put("connections", alvo == null ? List.of() : List.of(Map.of("targetId", alvo)));
        return node;
    }

    private static Flow fluxo(DefaultComponentCatalog catalogo, boolean exigeLista, Map<String, Object> configuracao,
                              Map<String, Object> meio) {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(catalogo, null, null);
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", "triagem");
        doc.put("metadata", Map.of("name", "triagem"));
        doc.put("configuration", configuracao);
        doc.put("steps", List.of(
                no("entrada", "input", "Entrada", Map.of(), (String) meio.get("id")),
                meio,
                no("saida", "output", "Saída", Map.of(), null)));
        return new DefaultWorkflowDeserializer(factory, exigeLista).toFlow(doc);
    }

    private static void valida(Flow flow) throws FlowValidationException {
        new ExecutableNodesValidator(new DefaultFlowValidator()).validate(flow);
    }

    private static DefaultComponentCatalog catalogoCom(String id) {
        DefaultComponentCatalog catalogo = new DefaultComponentCatalog();
        catalogo.register(new AIComponent() {
            @Override
            public void initialize(Map<String, Object> config) {
            }

            @Override
            public ComponentMetadata getMetadata() {
                return new ComponentMetadata(id, id, "plugin", ComponentType.TOOL, "1.0.0", Set.of(), List.of(),
                        Map.of(), Set.of());
            }

            @Override
            public Object execute(String operation, Object input, ExecutionContext c) {
                return input;
            }

            @Override
            public void shutdown() {
            }
        });
        return catalogo;
    }

    @Test
    @DisplayName("componente fora do catálogo: aviso (não recusa o run) nomeando o nó e o 'component not found'")
    void componenteNaoResolvidoAvisado() {
        Flow flow = fluxo(new DefaultComponentCatalog(), false, Map.of(),
                no("humor", "sentiment", "Sentimento", Map.of(), "saida"));

        assertThatCode(() -> valida(flow)).doesNotThrowAnyException();
        assertThat(ExecutableNodesValidator.unresolvedComponents(flow)).singleElement().satisfies(err -> {
            assertThat(err.message()).startsWith("o nó \"Sentimento\" (humor): component not found: sentiment")
                    .contains("falha ao chegar nele");
            assertThat(err.code()).isEqualTo(ExecutableNodesValidator.UNRESOLVED_CODE);
            assertThat(err.context()).containsEntry("node", "humor").containsEntry("component", "sentiment");
        });
    }

    @Test
    @DisplayName("componente negado pelo escopo: o aviso traz o motivo do allowedComponents, como o execute")
    void negadoPeloEscopoTrazOMotivo() {
        Flow flow = fluxo(catalogoCom("sentiment"), true, Map.of(),
                no("humor", "sentiment", null, Map.of(), "saida"));

        assertThatCode(() -> valida(flow)).doesNotThrowAnyException();
        assertThat(ExecutableNodesValidator.unresolvedComponents(flow)).singleElement()
                .satisfies(err -> assertThat(err.message())
                        .startsWith("o nó humor: component not found: sentiment — "
                                + DefaultWorkflowDeserializer.REQUIRE_ALLOWLIST_REASON));
    }

    @Test
    @DisplayName("componente que o catálogo resolve não gera aviso")
    void componenteResolvidoSemAviso() {
        Flow flow = fluxo(catalogoCom("sentiment"), false, Map.of(),
                no("humor", "sentiment", "Sentimento", Map.of(), "saida"));

        assertThat(ExecutableNodesValidator.unresolvedComponents(flow)).isEmpty();
    }

    @Test
    @DisplayName("condition e switch sem expressão: aviso (não recusa o run) nomeando o nó e o campo")
    void expressaoVaziaAvisada() {
        Flow condition = fluxo(new DefaultComponentCatalog(), false, Map.of(),
                no("alto", "condition", "Valor alto?", Map.of("conditionExpression", "  "), "saida"));
        Flow sw = fluxo(new DefaultComponentCatalog(), false, Map.of(),
                no("motivo", "switch", "Motivo", Map.of("switchCases", "a\nb"), "saida"));

        assertThatCode(() -> valida(condition)).doesNotThrowAnyException();
        assertThatCode(() -> valida(sw)).doesNotThrowAnyException();
        assertThat(ExecutableNodesValidator.missingControlExpressions(condition)).singleElement().satisfies(err -> {
            assertThat(err.message())
                    .startsWith("o nó \"Valor alto?\" (alto), campo conditionExpression: sem expressão");
            assertThat(err.code()).isEqualTo(ExecutableNodesValidator.EXPRESSION_MISSING_CODE);
            assertThat(err.context()).containsEntry("node", "alto").containsEntry("field", "conditionExpression");
        });
        assertThat(ExecutableNodesValidator.missingControlExpressions(sw)).singleElement()
                .satisfies(err -> assertThat(err.message())
                        .startsWith("o nó \"Motivo\" (motivo), campo switchExpression: sem expressão"));
    }

    @Test
    @DisplayName("condition com expressão não gera aviso")
    void expressaoPreenchidaSemAviso() {
        Flow flow = fluxo(new DefaultComponentCatalog(), false, Map.of(),
                no("alto", "condition", "Valor alto?", Map.of("conditionExpression", "${entrada.valor} > 3"),
                        "saida"));

        assertThat(ExecutableNodesValidator.missingControlExpressions(flow)).isEmpty();
    }
}
