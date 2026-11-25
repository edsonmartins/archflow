package br.com.archflow.api.flow;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.langchain4j.provider.BaseUrlPolicy;
import br.com.archflow.langchain4j.provider.PropertyTenantKeyResolver;
import br.com.archflow.model.config.ProviderPreferences;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
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
import static org.mockito.Mockito.mock;

/**
 * Um nó {@code llm-chat} como o designer grava (sem operação, sem chave), com
 * {@code provider: openrouter}, executado de ponta a ponta — factory → ponte → registry → adapter
 * OpenRouter real — com {@code archflow.llm.provider-preferences} no deployment (RCHFLW-41).
 *
 * <p>Confere-se o corpo cru que chega ao servidor, como no {@code PreferenciaDeProvedorNoCorpoTest}:
 * afirmar sobre as propriedades resolvidas não prova o que sai. O destino é {@code openrouter.ai} de
 * verdade (o campo só vai a esse host); o {@link ProxySelector} padrão da JVM desvia esse host para o
 * servidor local, então nada sai para a rede.
 */
@DisplayName("llm-chat openrouter: preferência de provedor (ZDR) do deployment no fio")
class ZdrDoNoLlmChatNoFioTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPENROUTER = "http://openrouter.ai/api/v1";

    private static final String RESPOSTA = """
            {"id":"c1","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"oi"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}
            """;

    private HttpServer server;
    private ProxySelector anterior;
    private final List<String> corpos = new CopyOnWriteArrayList<>();
    private final List<String> hosts = new CopyOnWriteArrayList<>();

    private final PropertyTenantKeyResolver chaves = new PropertyTenantKeyResolver(
            Map.of(), Map.of(), "openrouter", "chave-da-plataforma");

    @BeforeEach
    void sobe() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", troca -> {
            corpos.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            hosts.add(String.valueOf(troca.getRequestHeaders().getFirst("Host")));
            byte[] resposta = RESPOSTA.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, resposta.length);
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

    private StepResult executar(ProviderPreferences prefs, boolean required, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>(Map.of("provider", "openrouter",
                "model", "openai/gpt-6-luna", "base.url", OPENROUTER));
        config.putAll(extra);
        return executar(prefs, required, null, config);
    }

    private StepResult executar(ProviderPreferences prefs, boolean required, ResolvedLLMConfig plataforma,
                                Map<String, Object> config) {
        DefaultFlowStepFactory factory = new DefaultFlowStepFactory(new DefaultComponentCatalog(),
                mock(DynamicWorkflowService.class), null, null, null, null, chaves);
        // http:// só porque o servidor local não termina TLS; o host é o do OpenRouter. O gateway local
        // entra na lista como o archflow.llm.base-url entra em produção (LlmKeyBinding).
        factory.setBaseUrlPolicy(BaseUrlPolicy.of(List.of(OPENROUTER, gateway())));
        factory.setProviderPreferences(prefs, required);
        if (plataforma != null) {
            factory.setPlatformDefaultLLM(plataforma);
        }
        Map<String, Object> node = new HashMap<>();
        node.put("id", "s1");
        node.put("type", "llm-chat");
        node.put("componentId", "llm-chat");
        node.put("config", config);
        Flow flow = new DefaultWorkflowDeserializer(factory).toFlow(Map.of(
                "id", "wf", "metadata", Map.of("name", "zdr"), "steps", List.of(node)));
        ExecutionContext ctx = new DefaultExecutionContext("tenant_a", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
        ctx.set("input", "diga oi");
        return flow.getSteps().get(0).execute(ctx).join();
    }

    private JsonNode unicoCorpo() throws IOException {
        assertThat(corpos).hasSize(1);
        assertThat(hosts.get(0)).as("chegou como OpenRouter").startsWith("openrouter.ai");
        return JSON.readTree(corpos.get(0));
    }

    @Test
    @DisplayName("ZDR + deny no deployment: o corpo traz provider {zdr, data_collection}")
    void preferenciaNoCorpo() throws Exception {
        StepResult resultado = executar(new ProviderPreferences(true, "deny"), false, Map.of());

        assertThat(resultado.getStatus()).as(String.valueOf(resultado.getErrors()))
                .isEqualTo(StepStatus.COMPLETED);
        assertThat(JSON.convertValue(unicoCorpo().get("provider"), Map.class))
                .isEqualTo(Map.of("zdr", true, "data_collection", "deny"));
    }

    @Test
    @DisplayName("o nó não desliga a garantia: provider.preferences no JSON do fluxo é ignorado")
    void noNaoDesliga() throws Exception {
        StepResult resultado = executar(new ProviderPreferences(true, "deny"), true,
                Map.of("provider.preferences", Map.of(), "provider.preferences.required", false));

        assertThat(resultado.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(unicoCorpo().path("provider").path("zdr").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("required=true sem preferência: o passo falha [LLM-ZDR] e nenhuma requisição sai")
    void obrigatoriaSemPreferencia() {
        StepResult resultado = executar(ProviderPreferences.NONE, true, Map.of());

        assertThat(resultado.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(String.valueOf(resultado.getOutput()) + resultado.getErrors()).contains("[LLM-ZDR]");
        assertThat(corpos).as("nada pode ter saído").isEmpty();
    }

    @Test
    @DisplayName("nada configurado: corpo sem provider (controle)")
    void semPreferencia() throws Exception {
        assertThat(executar(ProviderPreferences.NONE, false, Map.of()).getStatus())
                .isEqualTo(StepStatus.COMPLETED);
        assertThat(corpos.get(0)).doesNotContain("\"provider\"");
    }

    // --- Gateway do operador: archflow.llm.provider=openrouter + archflow.llm.base-url=<gateway> ---
    // O agente do mesmo deployment leva ZDR a esse gateway (o hub decide pelo provider); o nó llm-chat
    // sem provider herda o base-url e ia sem ela, e required=true não o barrava.

    private String gateway() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private ResolvedLLMConfig plataformaNoGateway(String provider) {
        return ResolvedLLMConfig.builder().provider(provider).model("openai/gpt-6-luna").temperature(0.2)
                .additionalConfig(Map.of("baseUrl", gateway())).build();
    }

    @Test
    @DisplayName("nó sem provider herda o gateway da plataforma OpenRouter: o corpo traz provider")
    void gatewayDaPlataformaLeva() throws Exception {
        StepResult resultado = executar(new ProviderPreferences(true, "deny"), false,
                plataformaNoGateway("openrouter"), Map.of());

        assertThat(resultado.getStatus()).as(String.valueOf(resultado.getErrors()))
                .isEqualTo(StepStatus.COMPLETED);
        assertThat(corpos).hasSize(1);
        assertThat(hosts.get(0)).as("foi ao gateway").startsWith("127.0.0.1");
        assertThat(JSON.convertValue(JSON.readTree(corpos.get(0)).get("provider"), Map.class))
                .isEqualTo(Map.of("zdr", true, "data_collection", "deny"));
    }

    @Test
    @DisplayName("gateway da plataforma + required=true sem preferência: falha [LLM-ZDR], nada sai")
    void gatewayDaPlataformaObrigatoria() {
        StepResult resultado = executar(ProviderPreferences.NONE, true, plataformaNoGateway("openrouter"),
                Map.of());

        assertThat(resultado.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(String.valueOf(resultado.getOutput()) + resultado.getErrors()).contains("[LLM-ZDR]");
        assertThat(corpos).isEmpty();
    }

    @Test
    @DisplayName("o nó não se declara OpenRouter: openrouter-urls no JSON do fluxo é ignorado")
    void noNaoDeclaraOGateway() throws Exception {
        StepResult resultado = executar(new ProviderPreferences(true, "deny"), false, null,
                Map.of("provider", "openrouter", "model", "openai/gpt-6-luna", "base.url", gateway(),
                        "provider.preferences.openrouter-urls", List.of(gateway())));

        assertThat(resultado.getStatus()).as(String.valueOf(resultado.getErrors()))
                .isEqualTo(StepStatus.COMPLETED);
        assertThat(corpos).hasSize(1);
        assertThat(corpos.get(0)).as("host que o deployment não declarou").doesNotContain("\"provider\"");
    }
}
