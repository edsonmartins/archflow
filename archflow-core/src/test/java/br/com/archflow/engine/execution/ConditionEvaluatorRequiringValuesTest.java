package br.com.archflow.engine.execution;

import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RCHFLW-123: a avaliação dos nós {@code condition} não deixa uma referência sem valor escolher o ramo. A
 * composição segue a lógica de três valores — o que falta só deixa o resultado indeciso quando ele depende disso.
 */
@DisplayName("ConditionEvaluator.evaluateRequiringValues: referência sem valor não decide (RCHFLW-123)")
class ConditionEvaluatorRequiringValuesTest {

    private final ConditionEvaluator avaliador = new ConditionEvaluator(true);

    private static ExecutionContext ctx() {
        return new DefaultExecutionContext("t", null, null, null);
    }

    @Test
    @DisplayName("campo ausente de uma entrada em texto: indeciso, com a referência — o evaluate dava false")
    void campoAusenteIndeciso() {
        ExecutionContext c = ctx();
        c.set("receber", "quero reembolso de 900 reais");

        ConditionEvaluator.Verdict v = avaliador.evaluateRequiringValues("${receber.valor} > 500", c);

        assertThat(v.decided()).isFalse();
        assertThat(v.unresolved()).containsExactly("${receber.valor}");
        assertThat(avaliador.evaluate("${receber.valor} > 500", c)).as("o comportamento antigo").isFalse();
    }

    @Test
    @DisplayName("com o valor presente decide como o evaluate")
    void valorPresenteDecide() {
        ExecutionContext c = ctx();
        c.set("receber", Map.of("valor", 900));

        assertThat(avaliador.evaluateRequiringValues("${receber.valor} > 500", c))
                .isEqualTo(new ConditionEvaluator.Verdict(true, java.util.List.of()));
        assertThat(avaliador.evaluateRequiringValues("${receber.valor} <= 500", c).result()).isFalse();
    }

    @Test
    @DisplayName("três valores: desconhecido || verdadeiro é verdadeiro; desconhecido && falso é falso")
    void logicaDeTresValores() {
        ExecutionContext c = ctx();
        c.set("triagem", Map.of("route", "ESCALATE", "answers", Map.of()));

        var ou = avaliador.evaluateRequiringValues(
                "${triagem.answers.urgente.result} == true || ${triagem.route} == 'ESCALATE'", c);
        assertThat(ou.decided()).isTrue();
        assertThat(ou.result()).isTrue();

        var e = avaliador.evaluateRequiringValues(
                "${triagem.answers.urgente.result} == true && ${triagem.route} == 'AUTO'", c);
        assertThat(e.decided()).isTrue();
        assertThat(e.result()).isFalse();

        var depende = avaliador.evaluateRequiringValues(
                "${triagem.answers.urgente.result} == true || ${triagem.route} == 'AUTO'", c);
        assertThat(depende.decided()).isFalse();
        assertThat(depende.unresolved()).containsExactly("${triagem.answers.urgente.result}");
    }

    @Test
    @DisplayName("${input} sem dado vale \"\" (RCHFLW-101); ${input.campo} segue a regra geral")
    void inputVazio() {
        ExecutionContext semInput = ctx();
        var v = avaliador.evaluateRequiringValues("${input} != ''", semInput);
        assertThat(v.decided()).isTrue();
        assertThat(v.result()).isFalse();

        ExecutionContext vazio = ctx();
        vazio.set("input", "");
        assertThat(avaliador.evaluateRequiringValues("${input} == ''", vazio).result()).isTrue();

        assertThat(avaliador.evaluateRequiringValues("${input.valor} > 1", vazio).unresolved())
                .containsExactly("${input.valor}");
    }

    @Test
    @DisplayName("chave gravada com null é valor (o decision que falhou); só a comparação de ordem fica indecisa")
    void nuloGravado() {
        ExecutionContext c = ctx();
        Map<String, Object> falhou = new LinkedHashMap<>();
        falhou.put("decision", null);
        c.set("triagem", falhou);

        assertThat(ConditionEvaluator.hasPath("triagem.decision", c)).isTrue();
        assertThat(ConditionEvaluator.hasPath("triagem.route", c)).isFalse();
        var igual = avaliador.evaluateRequiringValues("${triagem.decision} == 'financeiro'", c);
        assertThat(igual.decided()).isTrue();
        assertThat(igual.result()).isFalse();

        var ordem = avaliador.evaluateRequiringValues("${triagem.decision} > 0.5", c);
        assertThat(ordem.decided()).isFalse();
        assertThat(ordem.unresolved()).containsExactly("${triagem.decision}");
    }

    @Test
    @DisplayName("truthy e contains com referência ausente também ficam indecisos; literal não é referência")
    void operandoUnicoEContains() {
        ExecutionContext c = ctx();
        assertThat(avaliador.evaluateRequiringValues("${aprovado}", c).unresolved()).containsExactly("${aprovado}");
        assertThat(avaliador.evaluateRequiringValues("${msg} contains 'urgente'", c).unresolved())
                .containsExactly("${msg}");
        assertThat(avaliador.evaluateRequiringValues("'a' == 'a'", c).result()).isTrue();
        assertThat(avaliador.evaluateRequiringValues("", c).result()).isTrue();
    }

    @Test
    @DisplayName("ordem entre número e texto («R$ 120», «1.200,00») fica indecisa; dois textos ordenam como antes")
    void ordemEntreNumeroETextoIndecisa() {
        ExecutionContext c = ctx();
        // O que um LLM em pt-BR devolve: o evaluate ordenava como texto, «1» antes de «5» e «R» depois.
        c.set("valor_llm", "1.200,00");
        c.set("com_moeda", "R$ 120");
        c.set("hoje", "2026-11-25");
        assertThat(avaliador.evaluate("${valor_llm} > 500", c)).as("o comportamento antigo").isFalse();
        assertThat(avaliador.evaluate("${com_moeda} > 500", c)).as("o comportamento antigo").isTrue();

        var milhar = avaliador.evaluateRequiringValues("${valor_llm} > 500", c);
        assertThat(milhar.decided()).isFalse();
        assertThat(milhar.unresolved()).isEmpty();
        assertThat(milhar.notNumbers()).containsExactly("${valor_llm} = «1.200,00»");
        assertThat(avaliador.evaluateRequiringValues("500 < ${com_moeda}", c).notNumbers())
                .containsExactly("${com_moeda} = «R$ 120»");
        // Três valores, como na referência sem valor: o resultado que não depende dela decide.
        assertThat(avaliador.evaluateRequiringValues("${com_moeda} > 500 || 'a' == 'a'", c).decided()).isTrue();
        assertThat(avaliador.evaluateRequiringValues("${hoje} > '2026-01-01'", c))
                .isEqualTo(new ConditionEvaluator.Verdict(true, java.util.List.of()));
        assertThat(avaliador.evaluateRequiringValues("${com_moeda} == 120", c).decided()).isTrue();
    }

    @Test
    @DisplayName("expressão malformada continua lançando, como no evaluate")
    void malformadaLanca() {
        assertThatThrownBy(() -> avaliador.evaluateRequiringValues("${a} == 1 &&", ctx()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
