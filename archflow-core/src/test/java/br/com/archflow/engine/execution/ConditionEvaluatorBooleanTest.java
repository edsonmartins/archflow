package br.com.archflow.engine.execution;

import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gramática não tinha composição booleana: qualquer decisão com duas
 * condições precisava virar dois steps. E uma condição não avaliável SEGUIA a
 * transição — armadilha para quem expressa política como condição, porque um
 * erro de digitação libera o caminho em vez de barrá-lo.
 */
@DisplayName("ConditionEvaluator — composição booleana e modo estrito")
class ConditionEvaluatorBooleanTest {

    private final ConditionEvaluator permissivo = new ConditionEvaluator();
    private final ConditionEvaluator estrito = new ConditionEvaluator(true);
    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new DefaultExecutionContext("acme", "u", "s", null);
        ctx.set("severidade", "ALTA");
        ctx.set("confianca", 0.9);
        ctx.set("aprovado", true);
        ctx.set("mensagem", "erro && falha");
    }

    @Test
    @DisplayName("&& exige as duas pontas")
    void andRequiresBoth() {
        assertThat(permissivo.evaluate("${severidade} == 'ALTA' && ${confianca} > 0.8", ctx)).isTrue();
        assertThat(permissivo.evaluate("${severidade} == 'ALTA' && ${confianca} > 0.95", ctx)).isFalse();
    }

    @Test
    @DisplayName("|| basta uma ponta")
    void orNeedsOne() {
        assertThat(permissivo.evaluate("${severidade} == 'BAIXA' || ${aprovado}", ctx)).isTrue();
        assertThat(permissivo.evaluate("${severidade} == 'BAIXA' || ${confianca} < 0.1", ctx)).isFalse();
    }

    @Test
    @DisplayName("&& liga mais forte que || (precedência usual)")
    void andBindsTighterThanOr() {
        // false || (true && true) → true. Se a precedência fosse invertida,
        // (false || true) && true também daria true, então o caso decisivo é
        // este: true || (false && false) → true; (true || false) && false → false.
        assertThat(permissivo.evaluate("${aprovado} || ${confianca} < 0.1 && ${confianca} > 5", ctx))
                .isTrue();
    }

    @Test
    @DisplayName("operador dentro de aspas não divide a expressão")
    void operatorInsideQuotesDoesNotSplit() {
        assertThat(permissivo.evaluate("${mensagem} == 'erro && falha'", ctx)).isTrue();
    }

    @Test
    @DisplayName("três operandos encadeados")
    void threeOperands() {
        assertThat(permissivo.evaluate(
                "${severidade} == 'ALTA' && ${aprovado} && ${confianca} > 0.5", ctx)).isTrue();
        assertThat(permissivo.evaluate(
                "${severidade} == 'ALTA' && ${aprovado} && ${confianca} > 0.99", ctx)).isFalse();
    }

    @Test
    @DisplayName("modo permissivo: expressão malformada LIBERA o caminho (legado)")
    void permissiveModeFollowsOnMalformed() {
        assertThat(permissivo.evaluate("${severidade} == 'ALTA' &&", ctx)).isTrue();
    }

    @Test
    @DisplayName("modo estrito: expressão malformada BLOQUEIA o caminho")
    void strictModeBlocksOnMalformed() {
        assertThat(estrito.evaluate("${severidade} == 'ALTA' &&", ctx))
                .as("politica expressa como condicao nao pode liberar por erro de digitacao")
                .isFalse();
    }

    @Test
    @DisplayName("modo estrito não muda o resultado de condição válida")
    void strictModeDoesNotChangeValidConditions() {
        assertThat(estrito.evaluate("${severidade} == 'ALTA' && ${aprovado}", ctx)).isTrue();
        assertThat(estrito.evaluate("${severidade} == 'BAIXA'", ctx)).isFalse();
        assertThat(estrito.evaluate("", ctx)).isTrue();
    }

    @Test
    @DisplayName("isWellFormed valida cada operando da composição")
    void wellFormedChecksEachOperand() {
        assertThat(permissivo.isWellFormed("${a} == 1 && ${b} == 2")).isTrue();
        assertThat(permissivo.isWellFormed("${a} == 1 || ${b} >")).isFalse();
        assertThat(permissivo.isWellFormed("${a} == 1 &&")).isFalse();
    }
}
