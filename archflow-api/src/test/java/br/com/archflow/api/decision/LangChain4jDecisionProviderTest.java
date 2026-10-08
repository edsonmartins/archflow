package br.com.archflow.api.decision;

import br.com.archflow.decision.Answer;
import br.com.archflow.decision.DecisionCall;
import br.com.archflow.decision.DecisionException;
import br.com.archflow.decision.DecisionRequest;
import br.com.archflow.decision.DecisionResult;
import br.com.archflow.decision.Question;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.decision.response.RefusalAnswer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("provedor de decisão da langchain4j")
class LangChain4jDecisionProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String RESPOSTA = """
            {"model":"jev-1.13-20260917","id":"x",
             "answers":{
               "bug":{"type":"noul","noul":0.97},
               "time":{"type":"choice","choice":"pagamento",
                       "probabilities":{"pagamento":0.8,"conta":0.2},"confidence":0.6},
               "urg":{"type":"score","score":1.4,"legend":{"0":"baixa","1":"media","2":"alta"},
                      "probabilities":{"0":0.1,"1":0.5,"2":0.4},"confidence":0.7}},
             "usage":{"input_tokens":120,"output_tokens":30}}
            """;

    private HttpServer server;
    private final List<String> corpos = new CopyOnWriteArrayList<>();
    private final List<String> autorizacoes = new CopyOnWriteArrayList<>();
    private final AtomicInteger chamadas = new AtomicInteger();
    private volatile int[] status = {200};

    @BeforeEach
    void sobe() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", troca -> {
            int n = chamadas.getAndIncrement();
            corpos.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            autorizacoes.add(String.valueOf(troca.getRequestHeaders().getFirst("Authorization")));
            int s = status[Math.min(n, status.length - 1)];
            byte[] corpo = (s == 200 ? RESPOSTA : "{\"error\":\"x\"}").getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(s, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        server.start();
    }

    @AfterEach
    void desce() {
        server.stop(0);
    }

    private DecisionCall chamada(Map<String, Object> extras) {
        Map<String, Object> options = new java.util.LinkedHashMap<>(extras);
        options.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        return new DecisionCall("t1", "jev-1.13", "chave-do-tenant", Duration.ofSeconds(5), options);
    }

    private static DecisionRequest pedido() {
        return new DecisionRequest("minha tela fica em branco ao pagar", List.of(
                new Question.YesNo("bug", "É defeito?", "quebrado", "dúvida"),
                new Question.Choice("time", "Quem cuida?", Map.of("pagamento", "cobrança", "conta", "login")),
                new Question.Score("urg", "Urgência?", List.of("baixa", "media", "alta"))));
    }

    @Test
    @DisplayName("fala o protocolo no servidor configurado e lê as três formas de resposta")
    void falaOProtocolo() throws Exception {
        DecisionResult r = new LangChain4jDecisionProvider().decide(pedido(), chamada(Map.of()));

        assertThat(autorizacoes.get(0)).isEqualTo("Bearer chave-do-tenant");
        JsonNode corpo = JSON.readTree(corpos.get(0));
        assertThat(corpo.get("model").asText()).isEqualTo("jev-1.13");
        assertThat(corpo.get("questions").get("time").get("type").asText()).isEqualTo("choice");
        assertThat(corpo.get("questions").get("time").get("criteria").get("pagamento").asText())
                .isEqualTo("cobrança");
        assertThat(corpo.get("questions").get("urg").get("type").asText()).isEqualTo("score");
        assertThat(corpo.get("questions").get("bug").get("type").asText()).isEqualTo("noul");

        assertThat(r.answers().get("bug")).isEqualTo(new Answer.YesNo(0.97));
        Answer.Choice time = (Answer.Choice) r.answers().get("time");
        assertThat(time.choice()).isEqualTo("pagamento");
        assertThat(time.confidence()).isEqualTo(0.6);
        Answer.Score urg = (Answer.Score) r.answers().get("urg");
        assertThat(urg.score()).isEqualTo(1.4);
        assertThat(urg.probabilities()).containsEntry("1", 0.5);
        assertThat(urg.legend()).containsEntry("2", "alta");
        assertThat(r.usage().inputTokens()).isEqualTo(120);
        assertThat(r.usage().costUsd()).as("a langchain4j não informa o custo").isNull();
    }

    @Test
    @DisplayName("429 é repetido pela langchain4j; 401 não é")
    void repeticao() {
        status = new int[]{429, 200};
        assertThat(callOk()).isTrue();
        assertThat(chamadas.get()).isGreaterThanOrEqualTo(2);

        chamadas.set(0);
        status = new int[]{401};
        assertThatThrownBy(() -> new LangChain4jDecisionProvider().decide(pedido(), chamada(Map.of())))
                .isInstanceOfSatisfying(DecisionException.class, e -> assertThat(e.retryable()).isFalse());
        assertThat(chamadas.get()).isEqualTo(1);
    }

    private boolean callOk() {
        try {
            new LangChain4jDecisionProvider().decide(pedido(), chamada(Map.of("maxRetries", 2)));
            return true;
        } catch (DecisionException e) {
            return false;
        }
    }

    @Test
    @DisplayName("recusa vira Refused: confiança 0 e valor nulo — nunca um palpite")
    void recusa() throws Exception {
        Answer a = LangChain4jDecisionProvider.resposta(
                new Question.Choice("time", "", Map.of("a", "", "b", "")), RefusalAnswer.of());

        assertThat(a).isInstanceOf(Answer.Refused.class);
        assertThat(a.confidence()).isZero();
        assertThat(a.value()).isNull();
    }

    @Test
    @DisplayName("confiança ausente na resposta é derivada da distribuição (distância da uniforme)")
    void confiancaDerivada() throws Exception {
        var ca = dev.langchain4j.model.decision.response.ChoiceAnswer.builder().value("a")
                .options(List.of("a", "b")).probability("a", 0.9).probability("b", 0.1).build();

        Answer.Choice r = (Answer.Choice) LangChain4jDecisionProvider.resposta(
                new Question.Choice("q", "", Map.of("a", "", "b", "")), ca);

        assertThat(r.confidence()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("pergunta que a langchain4j não aceita (choice com uma opção) é erro claro, sem chamar")
    void perguntaInvalida() {
        DecisionRequest umaOpcao = new DecisionRequest("x", List.of(
                new Question.Choice("q", "Qual?", Map.of("so", "única"))));

        assertThatThrownBy(() -> new LangChain4jDecisionProvider().decide(umaOpcao, chamada(Map.of())))
                .isInstanceOfSatisfying(DecisionException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("'q'");
                });
        assertThat(chamadas.get()).isZero();
    }

    @Test
    @DisplayName("modelo obrigatório, backend conhecido, e a referência da chave segue o backend")
    void configuracao() {
        var p = new LangChain4jDecisionProvider();
        DecisionCall semModelo = new DecisionCall("t", null, "k", Duration.ofSeconds(1), Map.of());
        assertThatThrownBy(() -> p.decide(pedido(), semModelo)).hasMessageContaining("model");
        assertThatThrownBy(() -> p.decide(pedido(), chamada(Map.of("backend", "xpto"))))
                .hasMessageContaining("backend");

        assertThat(p.keyRef(Map.of())).contains("typesafe");
        assertThat(p.keyRef(Map.of("backend", "openai"))).contains("openai");
        assertThat(p.keyRef(Map.of("keyRef", "openrouter"))).contains("openrouter");
    }

    /** Pelo OpenRouter, no caminho estável — só roda com {@code OPENROUTER_API_KEY} no ambiente. */
    @Test
    @EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
    @DisplayName("[real] decide com o Jev via OpenRouter (/api/v1/systemone)")
    void servicoReal() throws Exception {
        DecisionCall real = new DecisionCall("t1", "typesafe/jev-1.13", System.getenv("OPENROUTER_API_KEY"),
                Duration.ofSeconds(30), Map.of("baseUrl", "https://openrouter.ai/api"));
        DecisionRequest p = new DecisionRequest("Esqueci minha senha e não consigo entrar.", List.of(
                new Question.Choice("time", "Qual time cuida?",
                        Map.of("acesso", "Login e senha.", "pagamentos", "Cobrança."))));

        DecisionResult r = new LangChain4jDecisionProvider().decide(p, real);

        assertThat(((Answer.Choice) r.answers().get("time")).choice()).isEqualTo("acesso");
    }
}
