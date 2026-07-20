package br.com.archflow.engine.execution;

import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConditionEvaluator")
class ConditionEvaluatorTest {

    private ConditionEvaluator evaluator;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        evaluator = new ConditionEvaluator();
        var chatMemory = MessageWindowChatMemory.builder().maxMessages(10).build();
        context = new DefaultExecutionContext("t", "u", "s", chatMemory);
    }

    @Test
    @DisplayName("condição vazia ou nula segue a transição")
    void blankFollows() {
        assertThat(evaluator.evaluate(null, context)).isTrue();
        assertThat(evaluator.evaluate("  ", context)).isTrue();
    }

    @Test
    @DisplayName("formato do designer: ${var} > literal numérico")
    void designerNumericComparison() {
        context.set("agent.confidence", 0.9);
        assertThat(evaluator.evaluate("${agent.confidence} > 0.8", context)).isTrue();
        assertThat(evaluator.evaluate("${agent.confidence} < 0.8", context)).isFalse();
        assertThat(evaluator.evaluate("${agent.confidence} >= 0.9", context)).isTrue();
        assertThat(evaluator.evaluate("${agent.confidence} <= 0.5", context)).isFalse();
    }

    @Test
    @DisplayName("igualdade e diferença com strings e números")
    void equality() {
        context.set("status", "aprovado");
        context.set("tentativas", 3);
        assertThat(evaluator.evaluate("${status} == 'aprovado'", context)).isTrue();
        assertThat(evaluator.evaluate("${status} != 'negado'", context)).isTrue();
        assertThat(evaluator.evaluate("${tentativas} == 3", context)).isTrue();
        assertThat(evaluator.evaluate("${tentativas} == '3'", context)).isTrue();
    }

    @Test
    @DisplayName("resolução aninhada em mapas: raiz no contexto + descida por chaves")
    void nestedMapResolution() {
        context.set("agent", Map.of("confidence", 0.95, "meta", Map.of("model", "gpt")));
        assertThat(evaluator.evaluate("${agent.confidence} > 0.8", context)).isTrue();
        assertThat(evaluator.evaluate("${agent.meta.model} == 'gpt'", context)).isTrue();
    }

    @Test
    @DisplayName("saída de step gravada com chave plana é resolvida")
    void flatStepOutputKey() {
        context.set("step.classify.output", "urgente");
        assertThat(evaluator.evaluate("${step.classify.output} == 'urgente'", context)).isTrue();
    }

    @Test
    @DisplayName("operando único é avaliado por veracidade")
    void truthiness() {
        context.set("flag", true);
        context.set("vazio", "");
        assertThat(evaluator.evaluate("${flag}", context)).isTrue();
        assertThat(evaluator.evaluate("${vazio}", context)).isFalse();
        assertThat(evaluator.evaluate("${inexistente}", context)).isFalse();
        assertThat(evaluator.evaluate("true", context)).isTrue();
        assertThat(evaluator.evaluate("false", context)).isFalse();
    }

    @Test
    @DisplayName("contains para strings e coleções")
    void containsOperator() {
        context.set("tags", List.of("vip", "beta"));
        context.set("texto", "pedido urgente do cliente");
        assertThat(evaluator.evaluate("${tags} contains 'vip'", context)).isTrue();
        assertThat(evaluator.evaluate("${tags} contains 'x'", context)).isFalse();
        assertThat(evaluator.evaluate("${texto} contains 'urgente'", context)).isTrue();
    }

    @Test
    @DisplayName("condição não avaliável segue a transição (permissivo, com warning)")
    void unevaluableFollows() {
        assertThat(evaluator.evaluate("${a} >", context)).isTrue();
    }

    @Test
    @DisplayName("isWellFormed distingue condições válidas de malformadas")
    void wellFormedness() {
        assertThat(evaluator.isWellFormed(null)).isTrue();
        assertThat(evaluator.isWellFormed("  ")).isTrue();
        assertThat(evaluator.isWellFormed("${a} > 1")).isTrue();
        assertThat(evaluator.isWellFormed("${tags} contains 'vip'")).isTrue();
        assertThat(evaluator.isWellFormed("${flag}")).isTrue();
        assertThat(evaluator.isWellFormed("${a} >")).isFalse();
        assertThat(evaluator.isWellFormed("== 1")).isFalse();
        assertThat(evaluator.isWellFormed("${a} contains ")).isFalse();
    }
}
