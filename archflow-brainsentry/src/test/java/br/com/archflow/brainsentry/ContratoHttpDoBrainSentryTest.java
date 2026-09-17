package br.com.archflow.brainsentry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O client contra o formato que o Brain Sentry de fato responde.
 *
 * <p>Os testes anteriores dublavam o client inteiro, e por isso nenhum viu que a busca responde
 * {@code {"results": [...]}} e o client lia uma lista: contra o servidor real a leitura falhava
 * sempre, o adaptador engolia a exceção e a memória nunca voltava. Aqui o servidor é HTTP de
 * verdade, com as formas copiadas de {@code brain-sentry-go/internal/dto}.</p>
 */
@DisplayName("BrainSentryClient — contrato HTTP")
class ContratoHttpDoBrainSentryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final List<JsonNode> corpos = new CopyOnWriteArrayList<>();
    private final List<String> autorizacoes = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> respostaDaBusca = new AtomicReference<>();
    private final AtomicInteger statusDaCriacao = new AtomicInteger(201);
    private final AtomicInteger chamadas = new AtomicInteger();

    /** Uma memória como {@code dto.MemoryResponse} a serializa. */
    private static final String MEMORIA = """
            {"id":"m-1","content":"cliente prefere caixa fechada","category":"CONTEXT",
             "importance":"IMPORTANT","memoryType":"EPISODIC",
             "tags":["archflow","tenant:acme","context:cli-7"],"tenantId":"bs-acme",
             "createdAt":"2026-09-10T12:00:00Z","updatedAt":"2026-09-10T12:00:00Z",
             "version":1,"accessCount":0,"injectionCount":0,"helpfulCount":0,
             "notHelpfulCount":0,"helpfulnessRate":0,"relevanceScore":0.8}
            """;

    @BeforeEach
    void subir() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/memories", exchange -> {
            chamadas.incrementAndGet();
            autorizacoes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] entrada = exchange.getRequestBody().readAllBytes();
            if (entrada.length > 0) {
                corpos.add(MAPPER.readTree(entrada));
            }
            String caminho = exchange.getRequestURI().getPath();
            int status;
            String saida;
            if (caminho.endsWith("/search")) {
                status = 200;
                saida = respostaDaBusca.get();
            } else {
                status = statusDaCriacao.get();
                saida = status / 100 == 2 ? MEMORIA : "{\"error\":\"falhou\"}";
            }
            byte[] bytes = saida.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        respostaDaBusca.set("{\"results\":[" + MEMORIA + "],\"total\":1,\"searchTimeMs\":12}");
    }

    @AfterEach
    void descer() {
        server.stop(0);
    }

    private BrainSentryClient client() {
        return new BrainSentryClient(BrainSentryConfig.of(baseUrl, "bs_chave-do-acme", null));
    }

    /** O defeito: a resposta real é um objeto, e o client lia uma lista. */
    @Test
    @DisplayName("lê a resposta da busca no formato do servidor: {results, total, searchTimeMs}")
    void leOFormatoReal() throws Exception {
        List<Memory> memorias = client().searchMemories("caixa", 5);

        assertThat(memorias)
                .as("antes, a leitura falhava e o adaptador devolvia vazio — a memória nunca voltava")
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.id()).isEqualTo("m-1");
                    assertThat(m.tags()).contains("tenant:acme", "context:cli-7");
                });
    }

    @Test
    @DisplayName("as tags vão no corpo, como recorte do servidor")
    void tagsNoCorpo() throws Exception {
        client().searchMemories("caixa", 5, List.of("tenant:acme", "context:cli-7"));

        JsonNode corpo = corpos.get(corpos.size() - 1);
        assertThat(corpo.path("query").asText())
                .as("o escopo é filtro, não texto da consulta")
                .isEqualTo("caixa");
        assertThat(corpo.path("tags").isArray()).isTrue();
        assertThat(corpo.path("tags").toString()).isEqualTo("[\"tenant:acme\",\"context:cli-7\"]");
        assertThat(corpo.path("limit").asInt()).isEqualTo(5);
    }

    /** No servidor, tags ausentes significam "sem recorte"; lista vazia não deve ir no corpo. */
    @Test
    @DisplayName("sem tags, o campo não vai")
    void semTags() throws Exception {
        client().searchMemories("caixa", 5);

        assertThat(corpos.get(corpos.size() - 1).has("tags")).isFalse();
    }

    @Test
    @DisplayName("a chave de serviço vai como Bearer — o servidor a reconhece pelo prefixo bs_")
    void chaveDeServico() throws Exception {
        client().searchMemories("caixa", 5);

        assertThat(autorizacoes).containsOnly("Bearer bs_chave-do-acme");
    }

    @Test
    @DisplayName("lista na raiz continua aceita, para servidores antigos")
    void listaNaRaiz() throws Exception {
        respostaDaBusca.set("[" + MEMORIA + "]");

        assertThat(client().searchMemories("caixa", 5)).hasSize(1);
    }

    /** "Vazio" já significa "nenhum resultado"; uma forma desconhecida não pode virar isso. */
    @Test
    @DisplayName("resposta sem 'results' é erro, não lista vazia")
    void formaDesconhecida() {
        respostaDaBusca.set("{\"items\":[]}");

        assertThatThrownBy(() -> client().searchMemories("caixa", 5))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("results");
    }

    @Test
    @DisplayName("resultado vazio é lista vazia")
    void vazio() throws Exception {
        respostaDaBusca.set("{\"results\":[],\"total\":0,\"searchTimeMs\":3}");

        assertThat(client().searchMemories("caixa", 5)).isEmpty();
    }

    /**
     * Com o circuito aberto pela busca, a gravação também não sai. Antes, cada gravação esperava o
     * timeout inteiro com o servidor fora do ar.
     */
    @Test
    @DisplayName("gravação com o circuito aberto é recusada sem ir ao servidor")
    void gravacaoRespeitaOCircuito() {
        CircuitBreaker circuito = new CircuitBreaker("teste", 1, Duration.ofMinutes(5));
        circuito.recordFailure();
        BrainSentryClient client = new BrainSentryClient(
                BrainSentryConfig.of(baseUrl, "bs_chave-do-acme", null), circuito);

        assertThatThrownBy(() -> client.createMemory("x", "CONTEXT", "MINOR", "EPISODIC", List.of()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("OPEN");
        assertThat(chamadas.get()).isZero();
    }

    @Test
    @DisplayName("5xx na gravação conta para abrir o circuito")
    void erroDoServidorConta() {
        CircuitBreaker circuito = new CircuitBreaker("teste", 1, Duration.ofMinutes(5));
        BrainSentryClient client = new BrainSentryClient(
                BrainSentryConfig.of(baseUrl, "bs_chave-do-acme", null), circuito);
        statusDaCriacao.set(503);

        assertThatThrownBy(() -> client.createMemory("x", "CONTEXT", "MINOR", "EPISODIC", List.of()))
                .isInstanceOf(IOException.class);
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /** 4xx é o servidor recusando esta requisição, não o servidor fora do ar. */
    @Test
    @DisplayName("4xx na gravação não abre o circuito")
    void recusaNaoConta() {
        CircuitBreaker circuito = new CircuitBreaker("teste", 1, Duration.ofMinutes(5));
        BrainSentryClient client = new BrainSentryClient(
                BrainSentryConfig.of(baseUrl, "bs_chave-do-acme", null), circuito);
        statusDaCriacao.set(400);

        assertThatThrownBy(() -> client.createMemory("x", "CONTEXT", "MINOR", "EPISODIC", List.of()))
                .isInstanceOf(IOException.class);
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("gravação bem-sucedida lê a memória criada")
    void gravacao() throws Exception {
        Memory m = client().createMemory("cliente prefere caixa fechada", "CONTEXT", "IMPORTANT",
                "EPISODIC", List.of("archflow", "tenant:acme", "context:cli-7"));

        assertThat(m.id()).isEqualTo("m-1");
        assertThat(corpos.get(0).path("tags").toString()).contains("context:cli-7");
        assertThat(m.createdAt()).isEqualTo(java.time.Instant.parse("2026-09-10T12:00:00Z"));
    }
}
