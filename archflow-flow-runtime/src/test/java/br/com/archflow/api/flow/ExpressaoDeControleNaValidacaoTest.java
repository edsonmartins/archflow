package br.com.archflow.api.flow;

import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.engine.exceptions.ValidationError;
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
 * RCHFLW-116 no validador que roda antes do primeiro passo (o mesmo do servidor e do standalone): a condição
 * gerada pelo assistente ({@code ticket.urgente == true}) é recusada com o nó e o campo; a forma certa passa.
 */
@DisplayName("ExecutableNodesValidator: expressão dos nós condition/switch")
class ExpressaoDeControleNaValidacaoTest {

    private static Map<String, Object> no(String id, String type, String label, Map<String, Object> config,
                                          String... alvos) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("componentId", type);
        node.put("label", label);
        node.put("configuration", config);
        node.put("connections", java.util.Arrays.stream(alvos).map(a -> Map.of("targetId", a)).toList());
        return node;
    }

    /** Receber → Classificar (llm-chat) → condição → dois ramos, a forma que o assistente gerou. */
    private static Flow fluxo(String tipoDoControle, Map<String, Object> config) {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);
        return new DefaultWorkflowDeserializer(factory).toFlow(Map.of("id", "triagem", "metadata",
                Map.of("name", "triagem"), "steps", List.of(
                        no("receber", "input", "Receber Ticket", Map.of(), "classificar"),
                        no("classificar", "llm-chat", "Classificar Assunto", Map.of(), "decide"),
                        no("decide", tipoDoControle, "É Urgente?", config, "humano", "responder"),
                        no("humano", "output", "Escalar para Humano", Map.of()),
                        no("responder", "output", "Responder com LLM", Map.of()))));
    }

    private static void valida(Flow flow) throws FlowValidationException {
        new ExecutableNodesValidator(new DefaultFlowValidator()).validate(flow);
    }

    @Test
    @DisplayName("a condição gerada é recusada antes de executar, nomeando o nó e o campo")
    void condicaoGeradaRecusada() {
        Flow flow = fluxo("condition", Map.of("conditionExpression", "ticket.urgente == true"));

        assertThatThrownBy(() -> valida(flow))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("o nó \"É Urgente?\" (decide), campo conditionExpression:")
                .hasMessageContaining("«ticket.urgente» não é um valor")
                .satisfies(e -> assertThat(((FlowValidationException) e).getErrors()).singleElement()
                        .satisfies(err -> {
                            assertThat(err.code()).isEqualTo(ExecutableNodesValidator.EXPRESSION_CODE);
                            assertThat(err.field()).isEqualTo("step.configuration.conditionExpression");
                            assertThat(err.context()).containsEntry("node", "decide")
                                    .containsEntry("problems", List.of("BARE_OPERAND"));
                        }));
    }

    @Test
    @DisplayName("um campo da saída do LLM Chat é recusado: o tipo vem do documento")
    void campoDoLlmChat() {
        Flow flow = fluxo("condition", Map.of("conditionExpression", "${classificar.urgente} == true"));

        assertThatThrownBy(() -> valida(flow))
                .hasMessageContaining("'classificar' é um LLM Chat");
    }

    @Test
    @DisplayName("switch sem referência é recusado; o que lê passo inexistente só avisa (pode vir na entrada)")
    void switchRecusado() {
        assertThatThrownBy(() -> valida(fluxo("switch", Map.of("switchExpression", "triagem.decision",
                "switchCases", "urgente"))))
                .hasMessageContaining("campo switchExpression: o switch não lê nenhum ${…}");
        assertThatCode(() -> valida(fluxo("switch", Map.of("switchExpression", "${triagem.decision}",
                "switchCases", "urgente")))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a forma do avaliador sobre um passo que existe passa, e expressão ausente não é deste validador")
    void formaCertaPassa() {
        assertThatCode(() -> valida(fluxo("condition",
                Map.of("conditionExpression", "${classificar} contains 'URGENTE'")))).doesNotThrowAnyException();
        assertThatCode(() -> valida(fluxo("condition",
                Map.of("conditionExpression", "${input.ticket.urgente} == true")))).doesNotThrowAnyException();
        assertThatCode(() -> valida(fluxo("switch",
                Map.of("switchExpression", "${input}", "switchCases", "oi")))).doesNotThrowAnyException();
        // Sem expressão, o passo é que falha ao executar ("'conditionExpression' é obrigatória").
        assertThatCode(() -> valida(fluxo("condition", Map.of()))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("chave aninhada da entrada passa quando declarada nos campos da Entrada; payload do invoke também")
    void campoDeclaradoNaEntradaERaizesDoServidor() {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);
        java.util.function.Function<Map<String, Object>, Flow> comEntrada = entrada -> new DefaultWorkflowDeserializer(
                factory).toFlow(Map.of("id", "f", "metadata", Map.of("name", "f"), "steps", List.of(
                        no("receber", "input", "Receber", entrada, "decide"),
                        no("decide", "condition", "Urgente?", Map.of("conditionExpression",
                                "${ticket.urgente} == true && ${payload.canal} == 'whatsapp'"), "fim"),
                        no("fim", "output", "Fim", Map.of()))));

        assertThatCode(() -> valida(comEntrada.apply(Map.of("ioFields", List.of("ticket")))))
                .doesNotThrowAnyException();
        // Sem a declaração, o servidor não recusa: a chave pode vir no JSON de entrada do run (fluxos criados
        // por API contam com isso). Só o editor impede, pedindo a declaração na Entrada.
        assertThatCode(() -> valida(comEntrada.apply(Map.of()))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a recusa soma com as outras: um erro por nó de controle inválido")
    void umErroPorNo() {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);
        Flow flow = new DefaultWorkflowDeserializer(factory).toFlow(Map.of("id", "f", "metadata", Map.of("name", "f"),
                "steps", List.of(
                        no("a", "condition", "A", Map.of("conditionExpression", "urgente"), "b"),
                        no("b", "condition", "B", Map.of("conditionExpression", "${x.y} == 1 || z == 2")))));

        assertThatThrownBy(() -> valida(flow))
                .satisfies(e -> assertThat(((FlowValidationException) e).getErrors())
                        .extracting(ValidationError::message)
                        .containsExactly(
                                "o nó \"A\" (a), campo conditionExpression: «urgente» não é um valor: escreva a "
                                        + "referência como ${…} ou o texto entre aspas ('…') — sem ${}, o "
                                        + "avaliador compara o próprio texto e o ramo nunca muda",
                                // ${x.y} (passo inexistente) só avisa no servidor; «z» continua recusado.
                                "o nó \"B\" (b), campo conditionExpression: «z» não é um valor: "
                                        + "escreva a referência como ${…} ou o texto entre aspas ('…') — sem ${}, "
                                        + "o avaliador compara o próprio texto e o ramo nunca muda"));
    }

    /** Receber → triagem (condição) → LLM Chat com a "Mensagem do usuário" dada (RCHFLW-120). */
    private static Flow comMensagem(String tipoDoChat, Object userMessage) {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);
        Map<String, Object> config = new HashMap<>();
        config.put("userMessage", userMessage);
        return new DefaultWorkflowDeserializer(factory).toFlow(Map.of("id", "f", "metadata", Map.of("name", "f"),
                "steps", List.of(
                        no("receber", "input", "Receber", Map.of(), "classificar"),
                        no("classificar", "llm-chat", "Classificar", Map.of(), "decide"),
                        no("decide", "condition", "Urgente?", Map.of("conditionExpression",
                                "${classificar} contains 'URGENTE'"), "responder"),
                        no("responder", tipoDoChat, "Responder", config))));
    }

    @Test
    @DisplayName("RCHFLW-120: a Mensagem do usuário que nunca funciona é recusada, nomeando nó e campo")
    void mensagemDoUsuarioRecusada() {
        assertThatThrownBy(() -> valida(comMensagem("llm-chat", "Cliente: ${receber")))
                .hasMessageContaining("o nó \"Responder\" (responder), campo userMessage:")
                .hasMessageContaining("«${receber» não é uma referência")
                .satisfies(e -> assertThat(((FlowValidationException) e).getErrors()).singleElement()
                        .satisfies(err -> {
                            assertThat(err.code()).isEqualTo(ExecutableNodesValidator.EXPRESSION_CODE);
                            assertThat(err.field()).isEqualTo("step.configuration.userMessage");
                            assertThat(err.context()).containsEntry("node", "responder")
                                    .containsEntry("problems", List.of("MALFORMED_TEMPLATE"));
                        }));
        assertThatThrownBy(() -> valida(comMensagem("llm-streaming", "${classificar.resposta}")))
                .hasMessageContaining("campo userMessage: ${classificar.resposta}: 'classificar' é um LLM Chat");
    }

    @Test
    @DisplayName("RCHFLW-120: referência a passo que existe, texto literal e passo desconhecido (só aviso) passam")
    void mensagemDoUsuarioQuePassa() {
        assertThatCode(() -> valida(comMensagem("llm-chat", "Cliente: ${receber}"))).doesNotThrowAnyException();
        assertThatCode(() -> valida(comMensagem("llm-chat", "Responda com cordialidade.")))
                .doesNotThrowAnyException();
        assertThatCode(() -> valida(comMensagem("llm-chat", "${pedido.texto}"))).doesNotThrowAnyException();
        // Valor que não é texto não é template: a ponte o ignora (vale a entrada do passo), como o painel em branco.
        assertThatCode(() -> valida(comMensagem("llm-chat", 42))).doesNotThrowAnyException();
    }

    /** Um LLM Chat com {@code configuration.userMessage} e um {@code config.userMessage} que sobrou (legado). */
    private static Flow comAsDuasChaves(String emConfiguration, String emConfig) {
        PortableFlowStepFactory factory = new PortableFlowStepFactory(new DefaultComponentCatalog(), null, null);
        Map<String, Object> responder = no("responder", "llm-chat", "Responder",
                new HashMap<>(Map.of("userMessage", emConfiguration)));
        responder.put("config", Map.of("userMessage", emConfig));
        return new DefaultWorkflowDeserializer(factory).toFlow(Map.of("id", "f", "metadata", Map.of("name", "f"),
                "steps", List.of(no("receber", "input", "Receber", Map.of(), "responder"), responder)));
    }

    @Test
    @DisplayName("RCHFLW-120: com config e configuration, vale o de configuration — o mesmo que a fábrica executa")
    void mensagemDoUsuarioNaOrdemDaFabrica() {
        // O valor que roda (configuration) é válido; o que sobrou em config, não: nada a recusar.
        assertThatCode(() -> valida(comAsDuasChaves("${receber}", "Cliente: ${receber")))
                .doesNotThrowAnyException();
        // Ao contrário, é o de configuration que roda e que tem de ser recusado.
        assertThatThrownBy(() -> valida(comAsDuasChaves("Cliente: ${receber", "${receber}")))
                .hasMessageContaining("campo userMessage:").hasMessageContaining("«${receber» não é uma referência");
        // Em branco por cima é "sem campo" no motor (vale a entrada do passo): o config por baixo não é checado.
        assertThatCode(() -> valida(comAsDuasChaves("   ", "${receber"))).doesNotThrowAnyException();
    }
}
