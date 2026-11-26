package br.com.archflow.engine.execution;

import br.com.archflow.engine.execution.ControlExpressionRules.Finding;
import br.com.archflow.engine.execution.ControlExpressionRules.FlowRefs;
import br.com.archflow.engine.execution.ControlExpressionRules.Problem;
import br.com.archflow.model.engine.DefaultExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-116: a checagem estática das expressões dos nós de controle. O assistente gerou
 * {@code ticket.urgente == true} — sem {@code ${}} e apontando para uma variável que nenhum passo produz — e o
 * pedido urgente seguia o ramo {@code false} sem erro nem aviso.
 */
@DisplayName("ControlExpressionRules: o formato do avaliador e referências a passos que existem")
class ControlExpressionRulesTest {

    /** Os casos que o editor também lê: os dois lados recusam e aceitam o mesmo. */
    private static final Path CASES = Path.of("..", "archflow-ui", "src", "components", "FlowCanvas",
            "__tests__", "controlExpressionCases.json");

    private static final FlowRefs REFS = new FlowRefs(
            Set.of("entrada", "classificar", "triagem"), Set.of("classificar"));

    private static List<Problem> condicao(String expressao) {
        return ControlExpressionRules.checkCondition(expressao, REFS).stream().map(Finding::problem).toList();
    }

    @Test
    @DisplayName("os casos compartilhados com o editor dão as mesmas recusas, na mesma ordem")
    void casosCompartilhadosComOEditor() throws Exception {
        assertThat(CASES).as("casos do editor").exists();
        JsonNode doc = new ObjectMapper().readTree(Files.readString(CASES));
        Set<String> ids = new HashSet<>();
        Set<String> texto = new HashSet<>();
        Set<String> campos = new HashSet<>();
        for (JsonNode step : doc.get("steps")) {
            ids.add(step.get("id").asText());
            if (ControlExpressionRules.TEXT_OUTPUT_NODE_TYPES.contains(step.get("kind").asText())) {
                texto.add(step.get("id").asText());
            }
            if (step.has("ioFields")) {
                step.get("ioFields").forEach(c -> campos.add(c.asText()));
            }
        }
        FlowRefs refs = new FlowRefs(ids, texto, campos);
        assertThat(doc.get("cases")).hasSizeGreaterThan(20);
        for (JsonNode c : doc.get("cases")) {
            String expressao = c.get("expression").asText();
            List<Finding> achados = "switchExpression".equals(c.get("field").asText())
                    ? ControlExpressionRules.checkSwitch(expressao, refs)
                    : ControlExpressionRules.checkCondition(expressao, refs);
            List<String> esperado = new ArrayList<>();
            c.get("problems").forEach(p -> esperado.add(p.asText()));
            assertThat(achados.stream().map(f -> f.problem().name()).toList()).as(expressao).isEqualTo(esperado);
        }
    }

    @Test
    @DisplayName("a condição do defeito é recusada, e a mensagem diz como escrever certo")
    void condicaoDoDefeito() {
        List<Finding> achados = ControlExpressionRules.checkCondition("ticket.urgente == true", REFS);

        assertThat(achados).singleElement().satisfies(f -> {
            assertThat(f.problem()).isEqualTo(Problem.BARE_OPERAND);
            assertThat(f.fragment()).isEqualTo("ticket.urgente");
            assertThat(f.message()).contains("«ticket.urgente» não é um valor").contains("${…}");
        });
    }

    @Test
    @DisplayName("referência a passo inexistente sugere o id do nó e declarar a chave na Entrada")
    void passoInexistente() {
        Finding f = ControlExpressionRules.checkCondition("${ticket.urgente} == true", REFS).get(0);

        assertThat(f.problem()).isEqualTo(Problem.UNKNOWN_STEP);
        assertThat(f.message()).contains("nenhum passo do fluxo tem o id 'ticket'")
                .contains("declare-a em «Campos de entrada esperados» do nó Entrada");
        assertThat(condicao("${entrada.ticket.urgente} == true")).isEmpty();
    }

    @Test
    @DisplayName("chave da entrada declarada na Entrada vale como raiz — e resolve no avaliador, com ou sem input")
    void campoDeclaradoNaEntrada() {
        FlowRefs comCampo = new FlowRefs(Set.of("entrada"), Set.of(), Set.of("ticket"));
        String expressao = "${ticket.urgente} == true";

        assertThat(ControlExpressionRules.checkCondition(expressao, comCampo)).isEmpty();
        assertThat(ControlExpressionRules.checkCondition("${pedido.urgente} == true", comCampo))
                .extracting(Finding::problem).containsExactly(Problem.UNKNOWN_STEP);
        // A entrada {"input": "...", "ticket": {...}}: o RunInputSeeding grava cada chave, e o avaliador desce
        // no mapa de "ticket" — a forma aceita é a que segue o dado, mesmo com "input" ocupado por outra chave.
        ConditionEvaluator avaliador = new ConditionEvaluator(true);
        DefaultExecutionContext urgente = contexto(Map.of());
        urgente.set("input", "o sistema caiu");
        urgente.set("ticket", Map.of("urgente", true));
        DefaultExecutionContext normal = contexto(Map.of());
        normal.set("ticket", Map.of("urgente", false));
        assertThat(avaliador.evaluate(expressao, urgente)).isTrue();
        assertThat(avaliador.evaluate(expressao, normal)).isFalse();
    }

    @Test
    @DisplayName("as raízes que o servidor grava fora dos passos não são recusadas")
    void raizesDoServidor() {
        // AgentFlowRunner.PAYLOAD (VEN-397), ExecutionKeys.APPROVAL_PAYLOAD e CorrelacaoMcp.CTX_*: o invoke e a
        // aprovação gravam essas chaves no contexto, e a expressão que as lê resolve na execução.
        assertThat(condicao("${payload.canal} == 'whatsapp'")).isEmpty();
        assertThat(condicao("${approvalPayload.valor} > 100")).isEmpty();
        assertThat(condicao("${vendax.origem.task} != ''")).isEmpty();
        assertThat(ControlExpressionRules.checkSwitch("${payload.tipo}", REFS)).isEmpty();
    }

    @Test
    @DisplayName("campo da saída de um LLM Chat nunca resolve: a saída é o texto")
    void campoDeTexto() {
        Finding f = ControlExpressionRules.checkCondition("${classificar.urgente} == true", REFS).get(0);

        assertThat(f.problem()).isEqualTo(Problem.TEXT_OUTPUT_FIELD);
        assertThat(f.message()).contains("'classificar' é um LLM Chat").contains("decision");
        assertThat(condicao("${classificar} contains 'URGENTE'")).isEmpty();
    }

    @Test
    @DisplayName("sem falso positivo: variável da entrada sem ponto, raízes do motor, id com ponto e em branco")
    void semFalsoPositivo() {
        assertThat(condicao("${message} == 'oi'")).isEmpty();
        assertThat(condicao("${input.ticket.urgente} == true")).isEmpty();
        assertThat(condicao("${step.classificar.output} contains 'x'")).isEmpty();
        assertThat(condicao("${llm.model} == 'gpt-4o-mini'")).isEmpty();
        assertThat(condicao("   ")).isEmpty();
        assertThat(ControlExpressionRules.checkCondition("${a.b.c} == 1",
                new FlowRefs(Set.of("a.b"), Set.of()))).isEmpty();
    }

    @Test
    @DisplayName("o que é aceito é o que o avaliador resolve: a condição certa muda de ramo com o dado")
    void aceitoResolveDeVerdade() {
        // A recusa só vale se a forma aceita funciona: a mesma expressão, no avaliador estrito, segue o dado.
        String expressao = "${triagem.decision} == 'urgente'";
        assertThat(condicao(expressao)).isEmpty();
        ConditionEvaluator avaliador = new ConditionEvaluator(true);
        DefaultExecutionContext urgente = contexto(Map.of("decision", "urgente"));
        DefaultExecutionContext normal = contexto(Map.of("decision", "normal"));

        assertThat(avaliador.evaluate(expressao, urgente)).isTrue();
        assertThat(avaliador.evaluate(expressao, normal)).isFalse();
        // E a recusada era de fato constante: dá false para os dois.
        assertThat(avaliador.evaluate("ticket.urgente == true", urgente)).isFalse();
        assertThat(avaliador.evaluate("ticket.urgente == true", normal)).isFalse();
    }

    private static DefaultExecutionContext contexto(Map<String, Object> triagem) {
        DefaultExecutionContext ctx = new DefaultExecutionContext("t", "u", "f",
                MessageWindowChatMemory.builder().maxMessages(2).build());
        ctx.set("triagem", triagem);
        return ctx;
    }
}
