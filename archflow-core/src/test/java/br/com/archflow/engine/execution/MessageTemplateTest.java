package br.com.archflow.engine.execution;

import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RCHFLW-120: a "Mensagem do usuário" do LLM Chat. Depois de um decision ou condition, o LLM recebia o JSON da
 * classificação como mensagem; o template diz qual valor é a mensagem, lido como uma condição o leria.
 */
@DisplayName("MessageTemplate: referências ${…} resolvidas como nas condições")
class MessageTemplateTest {

    private static ExecutionContext contexto(Map<String, Object> variaveis) {
        ExecutionContext ctx = new DefaultExecutionContext("t", "u", "run",
                MessageWindowChatMemory.builder().maxMessages(5).build());
        variaveis.forEach(ctx::set);
        return ctx;
    }

    /** Ordem fixa: o JSON do mapa é comparado como texto. */
    private static Map<String, Object> ordenado(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static final ExecutionContext TRIAGEM = contexto(Map.of(
            "input", ordenado("route", "REVIEW", "answers", Map.of("urgente", Map.of("result", false))),
            "receber", "Queria saber o horário",
            "triagem", Map.of("route", "REVIEW", "answers", Map.of("assunto", Map.of("choice", "outro"))),
            "step.receber.output", "Queria saber o horário",
            "node.1", Map.of("valor", 7)));

    @Test
    @DisplayName("texto sem referência vai como está")
    void literal() {
        assertThat(MessageTemplate.render("Responda em uma frase.", TRIAGEM)).isEqualTo("Responda em uma frase.");
        assertThat(MessageTemplate.render("custa $10 {sem chave}", TRIAGEM)).isEqualTo("custa $10 {sem chave}");
    }

    @Test
    @DisplayName("${passo} e ${passo.campo}, misturados a texto")
    void passoECampo() {
        assertThat(MessageTemplate.render("${receber}", TRIAGEM)).isEqualTo("Queria saber o horário");
        assertThat(MessageTemplate.render("Cliente: ${receber} (assunto ${triagem.answers.assunto.choice})", TRIAGEM))
                .isEqualTo("Cliente: Queria saber o horário (assunto outro)");
        assertThat(MessageTemplate.render("${ receber }", TRIAGEM)).isEqualTo("Queria saber o horário");
    }

    @Test
    @DisplayName("${input} é a entrada do passo — a saída do anterior —, como numa condição; objeto vira JSON")
    void inputEObjetoComoJson() {
        assertThat(MessageTemplate.render("${input}", TRIAGEM))
                .isEqualTo("{\"route\":\"REVIEW\",\"answers\":{\"urgente\":{\"result\":false}}}");
        assertThat(MessageTemplate.render("${input.answers.urgente.result} / ${triagem.answers}", TRIAGEM))
                .isEqualTo("false / {\"assunto\":{\"choice\":\"outro\"}}");
        assertThat(MessageTemplate.render("lista ${l}", contexto(Map.of("l", List.of(1, "a")))))
                .isEqualTo("lista [1,\"a\"]");
    }

    @Test
    @DisplayName("o mesmo resolvedor das condições: chave inteira com ponto antes da descida")
    void mesmoResolvedorDasCondicoes() {
        assertThat(MessageTemplate.render("${step.receber.output}", TRIAGEM)).isEqualTo("Queria saber o horário");
        assertThat(MessageTemplate.render("${node.1.valor}", TRIAGEM)).isEqualTo("7");
        assertThat(new ConditionEvaluator().evaluate("${node.1.valor} == 7", TRIAGEM)).isTrue();
    }

    @Test
    @DisplayName("referência ausente falha com todas as que faltam, em ordem — nada de texto vazio nem ${…} literal")
    void ausenteFalha() {
        assertThatThrownBy(() -> MessageTemplate.render("${receber.texto} ${nada} ${receber.texto}", TRIAGEM))
                .isInstanceOfSatisfying(MessageTemplate.UnresolvedReferenceException.class, e -> {
                    assertThat(e.references()).containsExactly("receber.texto", "nada");
                    assertThat(e.getMessage()).contains("${receber.texto}").contains("${nada}");
                });
    }

    @Test
    @DisplayName("${ sem fechar, vazio ou aninhado não é referência")
    void malformado() {
        assertThatThrownBy(() -> MessageTemplate.render("Cliente: ${receber", TRIAGEM))
                .isInstanceOfSatisfying(MessageTemplate.MalformedTemplateException.class,
                        e -> assertThat(e.fragment()).isEqualTo("${receber"));
        assertThatThrownBy(() -> MessageTemplate.references("a ${} b"))
                .isInstanceOf(MessageTemplate.MalformedTemplateException.class);
        assertThatThrownBy(() -> MessageTemplate.references("${a${b}}"))
                .isInstanceOfSatisfying(MessageTemplate.MalformedTemplateException.class,
                        e -> assertThat(e.fragment()).isEqualTo("${a${b}"));
    }

    @Test
    @DisplayName("references: os caminhos na ordem, sem repetir")
    void referencias() {
        assertThat(MessageTemplate.references("${a} x ${b.c} ${a}")).containsExactly("a", "b.c");
        assertThat(MessageTemplate.references("sem nada")).isEmpty();
    }

    @Test
    @DisplayName("um valor com ${ dentro vai como texto, sem ser lido de novo")
    void valorNaoEReinterpretado() {
        ExecutionContext ctx = contexto(Map.of("msg", "o cliente escreveu ${nada}"));
        assertThat(MessageTemplate.render("> ${msg}", ctx)).isEqualTo("> o cliente escreveu ${nada}");
    }
}
