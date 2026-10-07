package br.com.archflow.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("provedor por regras")
class RulesDecisionProviderTest {

    private static final Question.Choice TIME = new Question.Choice("time", "",
            Map.of("pagamento", "", "conta", "", "outro", ""));
    private static final Question.YesNo BUG = new Question.YesNo("bug", "", null, null);
    private static final Question.Score URG = new Question.Score("urg", "", List.of("baixa", "media", "alta"));

    private static DecisionCall regras(Map<String, Object> rules) {
        return new DecisionCall("t", null, null, Duration.ofSeconds(1), Map.of("rules", rules));
    }

    private static Map<String, Object> todas() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("time", Map.of("match", new LinkedHashMap<>(Map.of(
                "pagamento", List.of("cobrança", "re:bole?to|pix"), "conta", List.of("senha"))),
                "otherwise", "outro"));
        r.put("bug", Map.of("matchAny", List.of("erro", "não funciona")));
        r.put("urg", Map.of("match", Map.of("2", List.of("fora do ar"), "1", List.of("lento")),
                "otherwise", 0));
        return r;
    }

    private DecisionResult decide(Object estado) throws Exception {
        return new RulesDecisionProvider().decide(
                new DecisionRequest(estado, List.of(TIME, BUG, URG)), regras(todas()));
    }

    @Test
    @DisplayName("regra que casa sozinha é certeza; texto e regex, sem diferenciar maiúsculas")
    void casaSozinha() throws Exception {
        Answer.Choice time = (Answer.Choice) decide("Meu BOLETO não funciona").answers().get("time");

        assertThat(time.choice()).isEqualTo("pagamento");
        assertThat(time.confidence()).isEqualTo(1.0);
        assertThat(decide("Meu BOLETO não funciona").answers().get("bug")).isEqualTo(new Answer.YesNo(1.0));
    }

    @Test
    @DisplayName("duas opções casando é ambiguidade: a probabilidade se reparte e a confiança cai")
    void ambiguidade() throws Exception {
        Answer.Choice time = (Answer.Choice) decide("esqueci a senha e a cobrança veio errada")
                .answers().get("time");

        assertThat(time.probabilities().get("pagamento")).isEqualTo(0.5);
        assertThat(time.probabilities().get("conta")).isEqualTo(0.5);
        assertThat(time.confidence()).as("2 de 3 opções casando: longe de certeza").isLessThan(0.5);
    }

    @Test
    @DisplayName("nada casou e há 'otherwise': é um palpite, confiança 0 — a rota escala")
    void otherwiseEPalpite() throws Exception {
        DecisionResult r = decide("bom dia");

        Answer.Choice time = (Answer.Choice) r.answers().get("time");
        assertThat(time.choice()).isEqualTo("outro");
        assertThat(time.confidence()).isZero();
        assertThat(r.answers().get("bug")).isEqualTo(new Answer.YesNo(0.0));
        assertThat(((Answer.Score) r.answers().get("urg")).score()).isZero();
    }

    @Test
    @DisplayName("score: o nível casado é o valor; vários níveis dão a média e menos confiança")
    void score() throws Exception {
        assertThat(((Answer.Score) decide("o sistema está fora do ar").answers().get("urg")).score())
                .isEqualTo(2.0);
        Answer.Score dois = (Answer.Score) decide("fora do ar e lento").answers().get("urg");
        assertThat(dois.score()).isEqualTo(1.5);
        assertThat(dois.confidence()).isLessThan(1.0);
    }

    @Test
    @DisplayName("estado que não é texto é julgado pelo JSON")
    void estadoEstruturado() throws Exception {
        assertThat(decide(Map.of("ticket", "senha perdida")).answers().get("time"))
                .isInstanceOfSatisfying(Answer.Choice.class, c -> assertThat(c.choice()).isEqualTo("conta"));
    }

    @Test
    @DisplayName("sem 'otherwise' e sem casar, ou sem regras para a pergunta, é erro — não chute")
    void semRegraEErro() {
        Map<String, Object> semOtherwise = Map.of("time", Map.of("match", Map.of("pagamento", List.of("pix"))));
        assertThatThrownBy(() -> new RulesDecisionProvider().decide(
                new DecisionRequest("bom dia", List.of(TIME)), regras(semOtherwise)))
                .hasMessageContaining("nenhuma regra casou");
        assertThatThrownBy(() -> new RulesDecisionProvider().decide(
                new DecisionRequest("x", List.of(TIME)), regras(Map.of())))
                .hasMessageContaining("sem regras");
    }
}
