package br.com.archflow.api.agent.mcp;

import br.com.archflow.api.agent.vendax.VendaxResult;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.langchain4j.provider.ProviderEndpoint;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O custo do turno que o OpenRouter não mandou (VEN-1044, pedido 2).
 *
 * <p>Medido no VendaX em 13/11: 10 a 14 de cada 120 execuções do Agente da Diretoria saíam sem custo
 * declarado, e o gate de promoção — desconhecido não é zero — reprovava todas as versões. Três
 * coisas, na ordem do pedido: buscar o custo pela geração, dizer quais turnos ficaram sem custo, e
 * logar o caso.</p>
 */
@DisplayName("custo do turno que veio sem usage.cost")
class CustoQueFaltouTest {

    private static final ResolvedLLMConfig CONFIG = ResolvedLLMConfig.builder()
            .provider("openrouter").model("google/gemini-2.5-flash").maxTokens(4096).build();

    private static ChatResponse corpo(String json, AiMessage ai) {
        var raw = SuccessfulHttpResponse.builder().statusCode(200).body(json).build();
        return ChatResponse.builder().aiMessage(ai)
                .metadata(OpenAiChatResponseMetadata.builder().rawHttpResponse(raw).build()).build();
    }

    private static McpAgentRunner.Options opcoes(ContadorDeUso contador) {
        return new McpAgentRunner.Options(ToolAccessPolicy.allowAll()).comUso(contador);
    }

    @Nested
    @DisplayName("id da geração e origem do uso")
    class IdDaGeracao {

        @Test
        @DisplayName("o id vem do corpo, e o caminho é 'corpo'")
        void doCorpo() {
            var u = McpAgentRunner.usoOpenRouter("openrouter",
                    corpo("{\"id\":\"gen-123\",\"usage\":{\"prompt_tokens\":10}}", AiMessage.from("x")));

            assertThat(u.generationId()).isEqualTo("gen-123");
            assertThat(u.via()).isEqualTo(McpAgentRunner.VIA_CORPO);
            assertThat(u.costUsd()).isNull();
        }

        @Test
        @DisplayName("no stream, o id vem dos eventos, e o caminho é 'sse'")
        void doStream() {
            var metadata = OpenAiChatResponseMetadata.builder()
                    .rawServerSentEvents(List.of(
                            new ServerSentEvent("message", "{\"id\":\"gen-sse\",\"choices\":[]}"),
                            new ServerSentEvent("message", "{\"id\":\"gen-sse\",\"choices\":[]}")))
                    .build();
            var u = McpAgentRunner.usoOpenRouter("openrouter",
                    ChatResponse.builder().aiMessage(AiMessage.from("x")).metadata(metadata).build());

            assertThat(u.generationId()).isEqualTo("gen-sse");
            assertThat(u.via()).isEqualTo(McpAgentRunner.VIA_SSE);
            assertThat(u.costUsd()).isNull();
        }
    }

    @Nested
    @DisplayName("contador")
    class Contador {

        @Test
        @DisplayName("o custo que chega depois entra no turno e no total")
        void custoTardioEntraNoTotal() {
            ContadorDeUso c = new ContadorDeUso();
            c.somarTurno("openrouter", "m", null, null, 10L, 5L, 0.001, "gen-1");
            int turno = c.somarTurno("openrouter", "m", null, null, 10L, 5L, null, "gen-2");
            CompletableFuture<Double> consulta = new CompletableFuture<>();
            c.aguardarCusto(turno, consulta);

            assertThat(c.resumo().orElseThrow().custoDeclaradoUsd()).as("antes de chegar").isNull();
            consulta.complete(0.002);
            assertThat(c.aguardarCustosPendentes(Duration.ofSeconds(1))).isTrue();

            var r = c.resumo().orElseThrow();
            assertThat(r.custoDeclaradoUsd()).isCloseTo(0.003, org.assertj.core.data.Offset.offset(1e-12));
            assertThat(r.turnosSemCusto()).isZero();
            assertThat(r.usoPorTurno().get(1).generationId()).isEqualTo("gen-2");
        }

        @Test
        @DisplayName("consulta sem resposta: o turno segue sem custo, e a contagem diz quantos")
        void semRespostaContaOsTurnos() {
            ContadorDeUso c = new ContadorDeUso();
            c.somarTurno("openrouter", "m", null, null, 10L, 5L, 0.001, "gen-1");
            int turno = c.somarTurno("openrouter", "m", null, null, 10L, 5L, null, "gen-2");
            c.aguardarCusto(turno, CompletableFuture.completedFuture(null));
            c.aguardarCusto(turno, CompletableFuture.failedFuture(new RuntimeException("rede")));

            var r = c.resumo().orElseThrow();
            assertThat(r.custoDeclaradoUsd()).as("soma parcial não é total").isNull();
            assertThat(r.turnosSemCusto()).isEqualTo(1);

            var uso = VendaxResult.Uso.de(r, null);
            assertThat(uso.turnsWithoutCost()).isEqualTo(1);
            assertThat(uso.modelUsage()).extracting(VendaxResult.Uso.TurnoUso::generationId)
                    .containsExactly("gen-1", "gen-2");
        }

        @Test
        @DisplayName("consulta que não termina no prazo não segura quem espera")
        void prazoEsgotado() {
            ContadorDeUso c = new ContadorDeUso();
            int turno = c.somarTurno("openrouter", "m", null, null, 1L, 1L, null, "gen-1");
            c.aguardarCusto(turno, new CompletableFuture<>());

            long inicio = System.nanoTime();
            assertThat(c.aguardarCustosPendentes(Duration.ofMillis(50))).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(2));
            assertThat(c.resumo().orElseThrow().custoDeclaradoUsd()).isNull();
        }

        @Test
        @DisplayName("falha antes de qualquer uso: total desconhecido mesmo com o turno preenchido")
        void falhaForaDeTurno() {
            ContadorDeUso c = new ContadorDeUso();
            c.marcarCustoDeclaradoDesconhecido();
            c.somarTurno("openrouter", "m", null, null, 1L, 1L, 0.001, "gen-1");

            var r = c.resumo().orElseThrow();
            assertThat(r.custoDeclaradoUsd()).isNull();
            assertThat(r.turnosSemCusto()).isZero();
        }
    }

    @Nested
    @DisplayName("no laço")
    class NoLaco {

        private record Resolvedor(ChatModel model, Optional<ProviderEndpoint> endpoint)
                implements LLMConfigResolver {
            @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return CONFIG; }
            @Override public ChatModel resolveModel(LLMResolutionRequest r) { return model; }
            @Override public Optional<ProviderEndpoint> resolveEndpoint(LLMResolutionRequest r) {
                return endpoint;
            }
        }

        /** Primeiro turno com custo e chamando tool; o segundo, final, sem custo. */
        private final ChatModel modelo = new ChatModel() {
            int turno;
            @Override public ChatResponse chat(ChatRequest request) {
                if (turno++ == 0) {
                    return corpo("{\"id\":\"gen-a\",\"usage\":{\"cost\":0.001}}",
                            AiMessage.from(ToolExecutionRequest.builder()
                                    .id("c1").name("consultar").arguments("{}").build()));
                }
                return corpo("{\"id\":\"gen-b\"}", AiMessage.from("pronto"));
            }
        };

        private final McpClient cliente = new McpClient() {
            @Override public void connect() { }
            @Override public CompletableFuture<McpModel.ServerInfo> initialize() {
                return CompletableFuture.completedFuture(null);
            }
            @Override public void initialized() { }
            @Override public boolean isConnected() { return true; }
            @Override public void close() { }
            @Override public McpModel.ServerMetadata getServerMetadata() {
                return new McpModel.ServerMetadata("fake");
            }
            @Override public McpModel.ServerCapabilities getServerCapabilities() {
                return McpModel.ServerCapabilities.toolsOnly();
            }
            @Override public CompletableFuture<List<McpModel.Tool>> listTools() {
                return CompletableFuture.completedFuture(List.of(new McpModel.Tool("consultar", "c",
                        Map.of("type", "object", "properties", Map.of()))));
            }
            @Override public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments a) {
                return CompletableFuture.completedFuture(McpModel.ToolResult.text("{}"));
            }
        };

        private static final ProviderEndpoint ENDPOINT =
                new ProviderEndpoint("openrouter", "https://openrouter.example/api/v1", "sk-tenant");

        @Test
        @DisplayName("o turno sem custo é consultado pela geração, e o total fecha antes do resultado")
        void consultaEFecha() {
            List<String> consultados = Collections.synchronizedList(new ArrayList<>());
            CustoDaGeracao custo = (endpoint, id) -> {
                consultados.add(endpoint.apiKey() + ":" + id);
                return CompletableFuture.supplyAsync(() -> 0.004,
                        CompletableFuture.delayedExecutor(50, java.util.concurrent.TimeUnit.MILLISECONDS));
            };
            ContadorDeUso contador = new ContadorDeUso();

            new McpAgentRunner(new Resolvedor(modelo, Optional.of(ENDPOINT)), CONFIG, null, 0, null,
                    custo, Duration.ofSeconds(2))
                    .run("acme", "sys", "oi", cliente, opcoes(contador));

            assertThat(consultados).as("só o turno sem custo, com a chave do tenant")
                    .containsExactly("sk-tenant:gen-b");
            var r = contador.resumo().orElseThrow();
            assertThat(r.custoDeclaradoUsd()).isCloseTo(0.005, org.assertj.core.data.Offset.offset(1e-12));
            assertThat(r.turnosSemCusto()).isZero();
        }

        @Test
        @DisplayName("desligado (sem consulta), o turno fica sem custo, como antes")
        void desligado() {
            ContadorDeUso contador = new ContadorDeUso();

            new McpAgentRunner(new Resolvedor(modelo, Optional.of(ENDPOINT)), CONFIG)
                    .run("acme", "sys", "oi", cliente, opcoes(contador));

            var r = contador.resumo().orElseThrow();
            assertThat(r.custoDeclaradoUsd()).isNull();
            assertThat(r.turnosSemCusto()).isEqualTo(1);
            assertThat(r.usoPorTurno().get(1).generationId()).isEqualTo("gen-b");
        }

        @Test
        @DisplayName("sem endpoint resolvido, não consulta")
        void semEndpoint() {
            AtomicInteger chamadas = new AtomicInteger();
            ContadorDeUso contador = new ContadorDeUso();

            new McpAgentRunner(new Resolvedor(modelo, Optional.empty()), CONFIG, null, 0, null,
                    (e, id) -> {
                        chamadas.incrementAndGet();
                        return CompletableFuture.completedFuture(1.0);
                    }, Duration.ofSeconds(1))
                    .run("acme", "sys", "oi", cliente, opcoes(contador));

            assertThat(chamadas).hasValue(0);
            assertThat(contador.resumo().orElseThrow().custoDeclaradoUsd()).isNull();
        }
    }

    @Nested
    @DisplayName("consulta HTTP ao OpenRouter")
    class ConsultaHttp {

        private HttpServer servidor;

        @AfterEach
        void parar() {
            if (servidor != null) {
                servidor.stop(0);
            }
        }

        private ProviderEndpoint subir(List<Integer> status, List<String> autorizacoes, List<String> ids)
                throws Exception {
            AtomicInteger n = new AtomicInteger();
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/api/v1/generation", troca -> {
                autorizacoes.add(troca.getRequestHeaders().getFirst("Authorization"));
                ids.add(troca.getRequestURI().getRawQuery());
                int s = status.get(Math.min(n.getAndIncrement(), status.size() - 1));
                byte[] corpo = (s == 200 ? "{\"data\":{\"id\":\"gen-x\",\"total_cost\":0.0042}}"
                        : "{\"error\":{\"message\":\"Generation not found\"}}").getBytes(StandardCharsets.UTF_8);
                troca.sendResponseHeaders(s, corpo.length);
                troca.getResponseBody().write(corpo);
                troca.close();
            });
            servidor.start();
            return new ProviderEndpoint("openrouter",
                    "http://127.0.0.1:" + servidor.getAddress().getPort() + "/api/v1/", "sk-tenant");
        }

        private CustoDaGeracao consulta(Duration prazo) {
            return new CustoDaGeracao.OpenRouter(HttpClient.newHttpClient(), prazo, Duration.ofMillis(20));
        }

        @Test
        @DisplayName("404 enquanto a estatística não existe, depois o custo — com a chave e o id")
        void repeteAte200() throws Exception {
            List<String> auth = Collections.synchronizedList(new ArrayList<>());
            List<String> ids = Collections.synchronizedList(new ArrayList<>());
            ProviderEndpoint e = subir(List.of(404, 404, 200), auth, ids);

            Double custo = consulta(Duration.ofSeconds(3)).consultar(e, "gen-x&y").get();

            assertThat(custo).isEqualTo(0.0042);
            assertThat(auth).hasSize(3).allMatch("Bearer sk-tenant"::equals);
            assertThat(ids).allMatch("id=gen-x%26y"::equals);
        }

        @Test
        @DisplayName("401 não repete: a chave não muda no meio da consulta")
        void naoAutorizadoDesiste() throws Exception {
            List<String> auth = Collections.synchronizedList(new ArrayList<>());
            ProviderEndpoint e = subir(List.of(401), auth, new ArrayList<>());

            assertThat(consulta(Duration.ofSeconds(3)).consultar(e, "gen-x").get()).isNull();
            assertThat(auth).hasSize(1);
        }

        @Test
        @DisplayName("404 até o prazo: desiste com null, sem lançar")
        void prazo() throws Exception {
            ProviderEndpoint e = subir(List.of(404), new ArrayList<>(), new ArrayList<>());

            long inicio = System.nanoTime();
            assertThat(consulta(Duration.ofMillis(200)).consultar(e, "gen-x").get()).isNull();
            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("sem chave ou sem id, nem tenta")
        void semChave() throws Exception {
            CustoDaGeracao c = consulta(Duration.ofSeconds(1));
            assertThat(c.consultar(new ProviderEndpoint("openrouter", "http://127.0.0.1:1", null), "gen")
                    .get()).isNull();
            assertThat(c.consultar(new ProviderEndpoint("openrouter", "http://127.0.0.1:1", "k"), " ")
                    .get()).isNull();
        }
    }

    @Test
    @DisplayName("a chave não aparece no toString do endpoint")
    void chaveForaDoLog() {
        assertThat(new ProviderEndpoint("openrouter", "https://x", "sk-secreta").toString())
                .doesNotContain("sk-secreta");
    }
}
