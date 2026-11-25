package br.com.archflow.langchain4j.openrouter;

import br.com.archflow.model.config.ProviderPreferences;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A preferência de provedor (ZDR) do deployment sai no corpo da requisição do adapter de chat do
 * OpenRouter — o caminho dos nós {@code llm-chat}/{@code llm-streaming} (RCHFLW-41).
 *
 * <h2>Como o destino é o OpenRouter sem sair da máquina</h2>
 *
 * <p>O campo só vai a host {@code openrouter.ai}; um servidor em {@code 127.0.0.1} nunca o receberia, e
 * um gancho de teste no adapter seria um jeito de produção desligar a regra. Então o destino é mesmo
 * {@code http://openrouter.ai/api/v1}, e o {@link ProxySelector} padrão da JVM (que o cliente HTTP do
 * langchain4j usa) desvia só esse host para o servidor local, que lê o corpo cru do socket. Nada é
 * resolvido por DNS e nada sai para a rede.
 */
@DisplayName("OpenRouter (nós de fluxo): preferência de provedor no corpo da requisição")
class PreferenciaDoNoOpenRouterNoCorpoTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPENROUTER = "http://openrouter.ai/api/v1";
    private static final ProviderPreferences ZDR = new ProviderPreferences(true, "deny");

    private static final String RESPOSTA = """
            {"id":"c1","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"oi"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}
            """;

    /** Uma requisição como o servidor a leu: host pedido e corpo. */
    record Pedido(String host, String corpo) {
        JsonNode json() throws IOException {
            return JSON.readTree(corpo);
        }
    }

    private HttpServer server;
    private ProxySelector anterior;
    private final List<Pedido> pedidos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void sobe() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", troca -> {
            String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String host = String.valueOf(troca.getRequestHeaders().getFirst("Host"));
            pedidos.add(new Pedido(host, corpo));
            // O "provider local" do fallback falha de propósito: é como se exercita o fallback.
            boolean falha = troca.getRequestURI().getPath().startsWith("/falha");
            byte[] resposta = (falha ? "{\"error\":{\"message\":\"x\"}}" : RESPOSTA)
                    .getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(falha ? 400 : 200, resposta.length);
            troca.getResponseBody().write(resposta);
            troca.close();
        });
        server.start();
        anterior = ProxySelector.getDefault();
        InetSocketAddress local = server.getAddress();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return ProviderPreferences.isOpenRouterHost(uri)
                        ? List.of(new Proxy(Proxy.Type.HTTP, local))
                        : List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        });
    }

    @AfterEach
    void desce() {
        ProxySelector.setDefault(anterior);
        server.stop(0);
    }

    private String local(String caminho) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + caminho;
    }

    private static OpenRouterChatAdapter adapter(String baseUrl, Object prefs, Object required,
                                                 Map<String, Object> extra) {
        Map<String, Object> props = new HashMap<>(Map.of(
                "api.key", "chave-de-teste",
                "model.name", "openai/gpt-6-luna",
                "base.url", baseUrl));
        if (prefs != null) {
            props.put(OpenRouterChatAdapter.PREFERENCES_PROPERTY, prefs);
        }
        if (required != null) {
            props.put(OpenRouterChatAdapter.PREFERENCES_REQUIRED_PROPERTY, required);
        }
        props.putAll(extra);
        OpenRouterChatAdapter adapter = new OpenRouterChatAdapter();
        adapter.configure(props);
        return adapter;
    }

    private static ExecutionContext contexto() {
        return new DefaultExecutionContext("acme", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(20).build());
    }

    private Pedido unico() {
        assertThat(pedidos).hasSize(1);
        return pedidos.get(0);
    }

    @Test
    @DisplayName("destino openrouter.ai: o corpo traz provider {zdr, data_collection}")
    void openRouterLevaAPreferencia() throws Exception {
        adapter(OPENROUTER, ZDR, false, Map.of()).execute("generate", "oi", contexto());

        Pedido pedido = unico();
        assertThat(pedido.host()).as("passou mesmo pelo host do OpenRouter").startsWith("openrouter.ai");
        assertThat(JSON.convertValue(pedido.json().get("provider"), Map.class))
                .isEqualTo(Map.of("zdr", true, "data_collection", "deny"));
    }

    @Test
    @DisplayName("operação chat (memória da execução) também leva")
    void chatTambemLeva() throws Exception {
        adapter(OPENROUTER, ZDR, false, Map.of()).execute("chat", "oi", contexto());

        assertThat(unico().json().path("provider").path("zdr").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("preferência como mapa {zdr, data_collection}: só o que foi declarado vai")
    void mapaSoODeclarado() throws Exception {
        adapter(OPENROUTER, Map.of("zdr", true), "true", Map.of()).execute("generate", "oi", contexto());

        assertThat(JSON.convertValue(unico().json().get("provider"), Map.class)).isEqualTo(Map.of("zdr", true));
    }

    @Test
    @DisplayName("required=true sem preferência: configure falha [LLM-ZDR] e nenhuma requisição sai")
    void obrigatoriaSemPreferenciaFalha() {
        assertThatThrownBy(() -> adapter(OPENROUTER, ProviderPreferences.NONE, true, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[LLM-ZDR]")
                .hasMessageContaining("required=true");
        assertThat(pedidos).isEmpty();
    }

    @Test
    @DisplayName("required=true sem preferência e fallback no OpenRouter: o fallback também não nasce")
    void obrigatoriaValeParaOFallback() {
        assertThatThrownBy(() -> adapter(local("/v1"), ProviderPreferences.NONE, true,
                Map.of("fallback.base.url", OPENROUTER)))
                .hasMessageContaining("[LLM-ZDR]");
        assertThat(pedidos).isEmpty();
    }

    @Test
    @DisplayName("destino que não é o OpenRouter: sem o campo, e required não pesa")
    void outroHostNaoRecebe() throws Exception {
        adapter(local("/v1"), ZDR, true, Map.of()).execute("generate", "oi", contexto());

        assertThat(unico().json().has("provider")).as("campo desconhecido fora do OpenRouter vira 400").isFalse();
    }

    @Test
    @DisplayName("sem preferência e required=false: o corpo não ganha provider (controle)")
    void semPreferenciaCorpoInalterado() throws Exception {
        adapter(OPENROUTER, null, null, Map.of()).execute("generate", "oi", contexto());

        assertThat(unico().corpo()).doesNotContain("\"provider\"");
    }

    @Test
    @DisplayName("fallback no OpenRouter leva; o principal fora dele não")
    void fallbackNoOpenRouterLeva() throws Exception {
        adapter(local("/falha/v1"), ZDR, false, Map.of("fallback.base.url", OPENROUTER,
                "fallback.model.name", "google/gemma-4-31b-it:free"))
                .execute("generate", "oi", contexto());

        assertThat(pedidos).hasSizeGreaterThanOrEqualTo(2);
        Pedido ultimo = pedidos.get(pedidos.size() - 1);
        assertThat(ultimo.host()).startsWith("openrouter.ai");
        assertThat(ultimo.json().path("model").asText()).isEqualTo("google/gemma-4-31b-it:free");
        assertThat(ultimo.json().path("provider").path("data_collection").asText()).isEqualTo("deny");
        assertThat(pedidos.subList(0, pedidos.size() - 1))
                .allSatisfy(p -> assertThat(p.corpo()).doesNotContain("\"provider\""));
    }

    @Test
    @DisplayName("modelo por contexto (config resolvida) com destino OpenRouter também leva")
    void modeloPorContextoLeva() throws Exception {
        OpenRouterChatAdapter adapter = adapter(local("/v1"), ZDR, false, Map.of());
        ExecutionContext ctx = contexto();
        ctx.set(ExecutionKeys.LLM_RESOLVED_CONFIG, ResolvedLLMConfig.builder()
                .provider("openrouter").model("nvidia/nemotron-3-ultra-550b-a55b:free").temperature(0.2)
                .additionalConfig(Map.of("baseUrl", OPENROUTER))
                .build());

        adapter.execute("generate", "oi", ctx);

        Pedido pedido = unico();
        assertThat(pedido.host()).startsWith("openrouter.ai");
        assertThat(pedido.json().path("model").asText()).isEqualTo("nvidia/nemotron-3-ultra-550b-a55b:free");
        assertThat(pedido.json().path("provider").path("zdr").asBoolean()).isTrue();
    }

    // --- Gateway do operador à frente do OpenRouter (archflow.llm.base-url com provider openrouter) ---
    // O hub dos agentes decide pelo provider e manda a preferência a esse gateway; o nó decidia só pelo
    // host e ia sem ela. O deployment declara o endereço; o adapter o trata como o OpenRouter.

    @Test
    @DisplayName("gateway declarado como OpenRouter: o corpo traz provider (o caminho não conta)")
    void gatewayDeclaradoLeva() throws Exception {
        adapter(local("/v1"), ZDR, false, Map.of(
                OpenRouterChatAdapter.PREFERENCES_OPENROUTER_URLS_PROPERTY, List.of(local("/outro"))))
                .execute("generate", "oi", contexto());

        Pedido pedido = unico();
        assertThat(pedido.host()).as("é o gateway, não o openrouter.ai").startsWith("127.0.0.1");
        assertThat(JSON.convertValue(pedido.json().get("provider"), Map.class))
                .isEqualTo(Map.of("zdr", true, "data_collection", "deny"));
    }

    @Test
    @DisplayName("gateway declarado + required=true sem preferência: configure falha [LLM-ZDR]")
    void gatewayDeclaradoObrigatoria() {
        assertThatThrownBy(() -> adapter(local("/v1"), ProviderPreferences.NONE, true,
                Map.of(OpenRouterChatAdapter.PREFERENCES_OPENROUTER_URLS_PROPERTY, local("/v1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[LLM-ZDR]");
        assertThat(pedidos).isEmpty();
    }

    @Test
    @DisplayName("gateway declarado não contamina o fallback em outro host")
    void gatewayNaoContaminaFallback() throws Exception {
        int porta = server.getAddress().getPort();
        adapter(local("/falha/v1"), ZDR, false, Map.of(
                OpenRouterChatAdapter.PREFERENCES_OPENROUTER_URLS_PROPERTY, List.of(local("/v1")),
                "fallback.base.url", "http://localhost:" + porta + "/v1"))
                .execute("generate", "oi", contexto());

        assertThat(pedidos).hasSizeGreaterThanOrEqualTo(2);
        assertThat(pedidos.get(0).json().path("provider").path("zdr").asBoolean()).isTrue();
        Pedido ultimo = pedidos.get(pedidos.size() - 1);
        assertThat(ultimo.host()).startsWith("localhost");
        assertThat(ultimo.corpo()).doesNotContain("\"provider\"");
    }

    @Test
    @DisplayName("regra de destino: esquema, host e porta têm de bater; openrouter.ai sempre vale")
    void regraDeDestino() {
        List<String> gateway = List.of("https://gw.corp/v1");
        assertThat(OpenRouterChatAdapter.isOpenRouterDestination("https://gw.corp/api", gateway)).isTrue();
        assertThat(OpenRouterChatAdapter.isOpenRouterDestination("https://gw.corp:443/v1", gateway)).isTrue();
        assertThat(OpenRouterChatAdapter.isOpenRouterDestination("http://gw.corp/v1", gateway)).isFalse();
        assertThat(OpenRouterChatAdapter.isOpenRouterDestination("https://gw.corp:8443/v1", gateway)).isFalse();
        assertThat(OpenRouterChatAdapter.isOpenRouterDestination("https://gw.corp.evil/v1", gateway)).isFalse();
        assertThat(OpenRouterChatAdapter.isOpenRouterDestination("https://gw.corp/v1", null)).isFalse();
        assertThat(OpenRouterChatAdapter.isOpenRouterDestination("https://openrouter.ai/api/v1", List.of()))
                .isTrue();
    }
}
