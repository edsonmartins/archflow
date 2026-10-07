package br.com.archflow.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O contrato do protocolo de decisões por HTTP, contra um servidor simulado. A resposta de exemplo é
 * a que o serviço real devolveu numa chamada de verificação (US$ 0,0000184, 0,56 s).
 */
@DisplayName("provedor HTTP de decisões")
class HttpDecisionProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String RESPOSTA = """
            {"model":"typesafe/jev-1.13-20260917","provider":"TypeSafe","id":"gen-dec-1",
             "answers":{
               "is_bug":{"type":"noul","noul":0.97},
               "team":{"type":"choice","choice":"payments",
                       "probabilities":{"account":0,"frontend":0.47,"payments":0.53},"confidence":0.29},
               "urgency":{"type":"score","score":1.99,
                          "legend":{"0":"Pode esperar","1":"Esta semana","2":"Agora"},
                          "probabilities":{"0":0,"1":0,"2":1},"confidence":0.99}},
             "usage":{"input_tokens":439,"output_tokens":70,"cost":0.000018438}}
            """;

    private HttpServer server;
    private final List<String> corpos = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> cabecalhos = new CopyOnWriteArrayList<>();
    private final AtomicInteger chamadas = new AtomicInteger();
    private volatile int[] statusEmSequencia = {200};
    private volatile String corpoDaResposta = RESPOSTA;

    @BeforeEach
    void sobe() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/decisions", troca -> {
            int n = chamadas.getAndIncrement();
            corpos.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Map<String, String> h = new LinkedHashMap<>();
            troca.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
            cabecalhos.add(h);
            int status = statusEmSequencia[Math.min(n, statusEmSequencia.length - 1)];
            byte[] corpo = (status == 200 ? corpoDaResposta : "{\"error\":\"x\"}")
                    .getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(status, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        server.start();
    }

    @AfterEach
    void desce() {
        server.stop(0);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/decisions";
    }

    private static DecisionRequest pedido() {
        return new DecisionRequest(Map.of("ticket", "tela em branco ao pagar"), List.of(
                new Question.YesNo("is_bug", "Defeito?", "quebrado", "dúvida"),
                new Question.Choice("team", "Quem cuida?",
                        Map.of("payments", "cobrança", "frontend", "tela", "account", "login")),
                new Question.Score("urgency", "Urgência?",
                        List.of("Pode esperar", "Esta semana", "Agora"))));
    }

    private DecisionCall chamada(Map<String, Object> extras) {
        Map<String, Object> options = new LinkedHashMap<>(extras);
        options.put("endpoint", endpoint());
        return new DecisionCall("t1", "typesafe/jev-1.13", "chave-secreta", Duration.ofSeconds(5), options);
    }

    @Test
    @DisplayName("monta o pedido no formato do protocolo e autentica com a chave resolvida")
    void montaOPedido() throws Exception {
        new HttpDecisionProvider().decide(pedido(), chamada(Map.of()));

        JsonNode corpo = JSON.readTree(corpos.get(0));
        assertThat(corpo.get("model").asText()).isEqualTo("typesafe/jev-1.13");
        assertThat(corpo.get("state").get("ticket").asText()).isEqualTo("tela em branco ao pagar");
        JsonNode q = corpo.get("questions");
        assertThat(q.get("is_bug").get("type").asText()).isEqualTo("noul");
        assertThat(q.get("is_bug").get("criteria").get("true").asText()).isEqualTo("quebrado");
        assertThat(q.get("team").get("type").asText()).isEqualTo("choice");
        assertThat(q.get("team").get("criteria").get("payments").asText()).isEqualTo("cobrança");
        assertThat(q.get("urgency").get("type").asText()).isEqualTo("score");
        assertThat(q.get("urgency").get("criteria").isArray()).isTrue();
        assertThat(q.get("urgency").get("criteria")).hasSize(3);
        assertThat(cabecalhos.get(0).get("authorization")).isEqualTo("Bearer chave-secreta");
    }

    @Test
    @DisplayName("lê as três formas de resposta, o modelo que respondeu e o uso com custo")
    void leAResposta() throws Exception {
        DecisionResult r = new HttpDecisionProvider().decide(pedido(), chamada(Map.of()));

        assertThat(r.model()).isEqualTo("typesafe/jev-1.13-20260917");
        assertThat(r.provider()).isEqualTo("TypeSafe");
        assertThat(r.calibrated()).isTrue();
        assertThat(r.answers().get("is_bug")).isEqualTo(new Answer.YesNo(0.97));
        Answer.Choice team = (Answer.Choice) r.answers().get("team");
        assertThat(team.choice()).isEqualTo("payments");
        assertThat(team.confidence()).isEqualTo(0.29);
        assertThat(team.probabilities()).containsEntry("frontend", 0.47);
        Answer.Score urgencia = (Answer.Score) r.answers().get("urgency");
        assertThat(urgencia.score()).isEqualTo(1.99);
        assertThat(urgencia.legend()).containsEntry("2", "Agora");
        assertThat(r.usage().costUsd()).isEqualTo(0.000018438);
        assertThat(r.usage().inputTokens()).isEqualTo(439);
    }

    @Test
    @DisplayName("429 melhora tentando de novo: repete uma vez e devolve a resposta")
    void repeteEm429() throws Exception {
        statusEmSequencia = new int[]{429, 200};

        DecisionResult r = new HttpDecisionProvider().decide(pedido(), chamada(Map.of("backoffMs", 1)));

        assertThat(r.answers()).hasSize(3);
        assertThat(chamadas.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("401 não melhora tentando de novo: uma chamada só, não repetível")
    void naoRepeteEm401() {
        statusEmSequencia = new int[]{401};

        assertThatThrownBy(() -> new HttpDecisionProvider().decide(pedido(), chamada(Map.of())))
                .isInstanceOfSatisfying(DecisionException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("401");
                });
        assertThat(chamadas.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("sobrecarga persistente (529): desiste depois das repetições, como repetível")
    void desisteDepoisDasRepeticoes() {
        statusEmSequencia = new int[]{529};

        assertThatThrownBy(() -> new HttpDecisionProvider()
                .decide(pedido(), chamada(Map.of("retries", 2, "backoffMs", 1))))
                .isInstanceOfSatisfying(DecisionException.class, e -> assertThat(e.retryable()).isTrue());
        assertThat(chamadas.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("resposta que não casa vira erro, nunca uma decisão inventada")
    void respostaForaDoContrato() {
        // sem a pergunta 'team'
        corpoDaResposta = "{\"answers\":{\"is_bug\":{\"type\":\"noul\",\"noul\":0.9}," +
                "\"urgency\":{\"type\":\"score\",\"score\":1,\"confidence\":1}}}";
        assertThatThrownBy(() -> new HttpDecisionProvider().decide(pedido(), chamada(Map.of())))
                .hasMessageContaining("team");

        // opção que ninguém declarou
        corpoDaResposta = RESPOSTA.replace("\"choice\":\"payments\"", "\"choice\":\"outra\"");
        assertThatThrownBy(() -> new HttpDecisionProvider().decide(pedido(), chamada(Map.of())))
                .hasMessageContaining("outra");

        // não é JSON
        corpoDaResposta = "<html>";
        assertThatThrownBy(() -> new HttpDecisionProvider().decide(pedido(), chamada(Map.of())))
                .isInstanceOfSatisfying(DecisionException.class, e -> assertThat(e.retryable()).isFalse());
    }

    @Test
    @DisplayName("cabeçalhos extras entram, mas não substituem a Authorization")
    void cabecalhosExtras() throws Exception {
        new HttpDecisionProvider().decide(pedido(), chamada(Map.of("headers",
                Map.of("X-Origem", "teste", "Authorization", "Bearer outra"))));

        assertThat(cabecalhos.get(0).get("x-origem")).isEqualTo("teste");
        assertThat(cabecalhos.get(0).get("authorization")).isEqualTo("Bearer chave-secreta");
    }

    @Test
    @DisplayName("sem modelo, nem chama")
    void semModelo() {
        DecisionCall semModelo = new DecisionCall("t1", null, "k", Duration.ofSeconds(1), Map.of());
        assertThatThrownBy(() -> new HttpDecisionProvider().decide(pedido(), semModelo))
                .hasMessageContaining("model");
        assertThat(chamadas.get()).isZero();
    }

    @Test
    @DisplayName("a chave a resolver é a do openrouter, a menos que o nó aponte outra")
    void referenciaDaChave() {
        var p = new HttpDecisionProvider();
        assertThat(p.keyRef(Map.of())).contains("openrouter");
        assertThat(p.keyRef(Map.of("keyRef", "typesafe"))).contains("typesafe");
        assertThat(p.requiresKey()).isTrue();
    }

    /**
     * Contra o serviço real — só roda com {@code OPENROUTER_API_KEY} no ambiente, para o contrato
     * alfa ser verificado de vez em quando sem a chave morar no repositório.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
    @DisplayName("[real] responde às três perguntas com a estrutura do contrato")
    void servicoReal() throws Exception {
        DecisionCall real = new DecisionCall("t1", "typesafe/jev-1.13",
                System.getenv("OPENROUTER_API_KEY"), Duration.ofSeconds(30), Map.of());

        DecisionResult r = new HttpDecisionProvider().decide(pedido(), real);

        assertThat(r.answers()).containsKeys("is_bug", "team", "urgency");
        assertThat(((Answer.Choice) r.answers().get("team")).choice())
                .isIn("payments", "frontend", "account");
        assertThat(r.usage().costUsd()).isNotNull();
    }
}
