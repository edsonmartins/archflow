package br.com.archflow.standalone;

import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.standalone.StandaloneRunner.CliArgs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Os desenhos de ramificação que a documentação ensina ({@code docs/decision-component.md}, e os mesmos que o
 * copiloto e o {@code nl-to-flow} montam) executados de ponta a ponta no runner standalone, depois da regra de
 * falhar fechado dos nós de controle (RCHFLW-123/135).
 *
 * <p>O provedor do exemplo da doc é o {@code llm}, que só existe no servidor; aqui o {@code rules} faz o papel
 * dele — o que muda é quem responde, não o que a decisão grava ({@code route}, {@code decision},
 * {@code answers}). A decisão que falha é a de regras sem regra para a pergunta: com o {@code onError: ESCALATE}
 * padrão ela grava {@code route: ESCALATE}, {@code decision: null} e {@code answers: {}}.</p>
 *
 * <p>Os nós terminais são {@code condition}s com uma expressão constante só para dizer qual ramo chegou ao fim: a
 * saída de um nó de controle traz a {@code expression}, e a de um nó Saída seria a mesma entrada nos dois ramos.</p>
 */
@DisplayName("exemplos de condição da documentação, depois de falhar fechado")
class ExemplosDeCondicaoDocumentadosTest {

    @TempDir
    Path dir;

    private static final String LENIENTE = "ARCHFLOW_FLOW_LENIENT_CONDITIONS";

    private FlowResult rodar(String json, Map<String, String> ambiente, String entrada) throws Exception {
        Path arquivo = dir.resolve("fluxo.json");
        Files.writeString(arquivo, json);
        try (StandaloneRunner runner = new StandaloneRunner(ambiente)) {
            return runner.run(CliArgs.parse(new String[]{arquivo.toString(), "--input", entrada,
                    "--timeout", "60"}));
        }
    }

    /** O nó terminal que diz o ramo: a saída dele carrega a expressão com o nome. */
    private static String fim(String id) {
        return """
               {"id": "%1$s", "type": "condition", "componentId": "condition", "label": "%1$s",
                "configuration": {"conditionExpression": "'%1$s' == '%1$s'"}}""".formatted(id);
    }

    private static String chegouEm(FlowResult r) {
        return String.valueOf(r.getOutput().orElse(null));
    }

    /** As regras da triagem; {@code null} faz a decisão falhar (sem regra para a pergunta). */
    private static String regras(boolean comRegras) {
        return comRegras ? """
                , "providerOptions": {"rules": {"rules": {
                    "assunto": {"match": {"financeiro": ["pix", "boleto"], "suporte_tecnico": ["fora do ar"]},
                                "otherwise": "outro"},
                    "urgente": {"matchAny": ["fora do ar", "urgente"]}}}}""" : "";
    }

    private static String triagem(boolean comRegras, String proximo) {
        return """
               {"id": "triagem", "type": "decision", "componentId": "decision", "label": "Triagem",
                "configuration": {"provider": "rules",
                  "questions": {
                    "assunto": {"type": "choice", "instructions": "Qual o assunto do pedido?",
                                "criteria": {"financeiro": "cobrança, pagamento",
                                             "suporte_tecnico": "defeito, sistema fora do ar",
                                             "outro": "qualquer outro assunto"}},
                    "urgente": {"type": "noul", "instructions": "O pedido é urgente?",
                                "criteria": {"true": "algo parado ou fora do ar", "false": "pode esperar"}}},
                  "primary": "assunto", "gate": ["urgente"]%s},
                "connections": [{"sourceId": "triagem", "targetId": "%s"}]}""".formatted(regras(comRegras),
                proximo);
    }

    /** docs/decision-component.md, "urgente vai ao humano, o resto o LLM responde" (e_urgente). */
    private static String triagemPorUrgencia(boolean comRegras, String expressao) {
        return """
               {"id": "triagem-urgencia", "metadata": {"name": "Triagem", "version": "1.0.0"},
                "steps": [
                  {"id": "receber", "type": "input", "componentId": "input",
                   "connections": [{"sourceId": "receber", "targetId": "triagem"}]},
                  %s,
                  {"id": "e_urgente", "type": "condition", "componentId": "condition",
                   "configuration": {"conditionExpression": "%s"},
                   "connections": [{"sourceId": "e_urgente", "targetId": "humano", "branch": "true"},
                                   {"sourceId": "e_urgente", "targetId": "responder", "branch": "false"}]},
                  %s,
                  %s
                ]}""".formatted(triagem(comRegras, "e_urgente"), expressao, fim("humano"), fim("responder"));
    }

    private static final String E_URGENTE_DA_DOC =
            "${triagem.answers.urgente.result} == true || ${triagem.route} == 'ESCALATE'";

    @Test
    @DisplayName("e_urgente da doc: urgente ao humano, comum ao LLM, decisão que falhou ao humano")
    void triagemDaDoc() throws Exception {
        FlowResult urgente = rodar(triagemPorUrgencia(true, E_URGENTE_DA_DOC), Map.of(), "o sistema está fora do ar");
        assertThat(urgente.getStatus()).as(String.valueOf(urgente.getErrors())).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(chegouEm(urgente)).contains("'humano'").doesNotContain("'responder'");

        FlowResult comum = rodar(triagemPorUrgencia(true, E_URGENTE_DA_DOC), Map.of(), "qual o horário?");
        assertThat(comum.getStatus()).as(String.valueOf(comum.getErrors())).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(chegouEm(comum)).contains("'responder'").doesNotContain("'humano'");

        // A lógica de três valores: answers.urgente não existe, mas o || route == 'ESCALATE' decide sozinho.
        FlowResult falhou = rodar(triagemPorUrgencia(false, E_URGENTE_DA_DOC), Map.of(), "qual o horário?");
        assertThat(falhou.getStatus()).as(String.valueOf(falhou.getErrors())).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(chegouEm(falhou)).contains("'humano'").doesNotContain("'responder'");
    }

    @Test
    @DisplayName("sem o || ESCALATE, a decisão que falhou derruba a condição — antes ia ao 'responder' (aberto)")
    void semOEscalateFalha() throws Exception {
        String soResposta = "${triagem.answers.urgente.result} == true";

        FlowResult falhou = rodar(triagemPorUrgencia(false, soResposta), Map.of(), "qual o horário?");
        assertThat(falhou.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(String.valueOf(falhou.getErrors()))
                .contains("a referência ${triagem.answers.urgente.result} não tem valor");

        FlowResult leniente = rodar(triagemPorUrgencia(false, soResposta), Map.of(LENIENTE, "true"), "qual o horário?");
        assertThat(leniente.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(chegouEm(leniente)).as("o comportamento antigo: falso, e o LLM responde").contains("'responder'");
    }

    /** "Por categoria, o mesmo vale com um switch sobre ${triagem.decision} (um caso por opção ...)". */
    private static String triagemPorCategoria(boolean comRegras, String expressao, boolean comDefault) {
        return """
               {"id": "triagem-categoria", "metadata": {"name": "Triagem", "version": "1.0.0"},
                "steps": [
                  {"id": "receber", "type": "input", "componentId": "input",
                   "connections": [{"sourceId": "receber", "targetId": "triagem"}]},
                  %s,
                  {"id": "por_assunto", "type": "switch", "componentId": "switch",
                   "configuration": {"switchExpression": "%s",
                                     "switchCases": "financeiro\\nsuporte_tecnico\\noutro"},
                   "connections": [
                     {"sourceId": "por_assunto", "targetId": "financeiro", "branch": "financeiro"},
                     {"sourceId": "por_assunto", "targetId": "suporte", "branch": "suporte_tecnico"},
                     {"sourceId": "por_assunto", "targetId": "outro", "branch": "outro"}%s]},
                  %s, %s, %s%s
                ]}""".formatted(triagem(comRegras, "por_assunto"), expressao,
                comDefault ? ",\n{\"sourceId\": \"por_assunto\", \"targetId\": \"humano\", \"branch\": \"default\"}"
                        : "",
                fim("financeiro"), fim("suporte"), fim("outro"), comDefault ? ", " + fim("humano") : "");
    }

    @Test
    @DisplayName("switch em ${triagem.decision} com default: a decisão que falhou (decision null) vai ao default")
    void switchPorCategoriaComDefault() throws Exception {
        FlowResult ok = rodar(triagemPorCategoria(true, "${triagem.decision}", true), Map.of(), "o pix não caiu");
        assertThat(ok.getStatus()).as(String.valueOf(ok.getErrors())).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(chegouEm(ok)).contains("'financeiro'").doesNotContain("'humano'");

        FlowResult falhou = rodar(triagemPorCategoria(false, "${triagem.decision}", true), Map.of(), "o pix não caiu");
        assertThat(falhou.getStatus()).as(String.valueOf(falhou.getErrors())).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(chegouEm(falhou)).contains("'humano'");
    }

    @Test
    @DisplayName("switch em ${triagem.decision} só com um caso por opção (sem default): a decisão que falhou "
            + "agora FALHA (RCHFLW-135); leniente conclui sem chegar a saída nenhuma, como antes")
    void switchPorCategoriaSemDefault() throws Exception {
        FlowResult falhou = rodar(triagemPorCategoria(false, "${triagem.decision}", false), Map.of(), "oi");
        assertThat(falhou.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(String.valueOf(falhou.getErrors())).contains("não casa com nenhum caso");

        FlowResult leniente = rodar(triagemPorCategoria(false, "${triagem.decision}", false),
                Map.of(LENIENTE, "true"), "oi");
        assertThat(leniente.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(chegouEm(leniente)).contains("branch=default");
    }

    @Test
    @DisplayName("switch em ${triagem.answers.assunto.choice} (a forma que a doc desaconselha): a decisão que "
            + "falhou não vai mais ao default — o passo falha; leniente devolve o default")
    void switchPelaChoiceDaResposta() throws Exception {
        String pelaChoice = "${triagem.answers.assunto.choice}";

        FlowResult ok = rodar(triagemPorCategoria(true, pelaChoice, true), Map.of(), "o sistema está fora do ar");
        assertThat(ok.getStatus()).as(String.valueOf(ok.getErrors())).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(chegouEm(ok)).contains("'suporte'").doesNotContain("'humano'");

        FlowResult falhou = rodar(triagemPorCategoria(false, pelaChoice, true), Map.of(), "oi");
        assertThat(falhou.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(String.valueOf(falhou.getErrors()))
                .contains("a referência ${triagem.answers.assunto.choice} não tem valor");

        FlowResult leniente = rodar(triagemPorCategoria(false, pelaChoice, true), Map.of(LENIENTE, "true"), "oi");
        assertThat(leniente.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(chegouEm(leniente)).contains("'humano'");
    }

    @Test
    @DisplayName("nó solto num fluxo com arestas é recusado antes do 1º passo — também com o modo leniente")
    void noSoltoNaoTemModoLeniente() {
        String fluxo = """
               {"id": "com-solto", "metadata": {"name": "Com solto"},
                "steps": [
                  {"id": "receber", "type": "input", "componentId": "input",
                   "connections": [{"sourceId": "receber", "targetId": "saida"}]},
                  {"id": "saida", "type": "output", "componentId": "output"},
                  {"id": "antiga", "type": "output", "componentId": "output", "label": "Saída antiga"}
                ]}""";

        for (Map<String, String> ambiente : java.util.List.of(Map.<String, String>of(), Map.of(LENIENTE, "true"))) {
            assertThatThrownBy(() -> rodar(fluxo, ambiente, "oi"))
                    .as("ambiente %s", ambiente)
                    .isInstanceOf(FlowValidationException.class)
                    .hasMessageContaining("\"Saída antiga\" (antiga)");
        }
    }
}
