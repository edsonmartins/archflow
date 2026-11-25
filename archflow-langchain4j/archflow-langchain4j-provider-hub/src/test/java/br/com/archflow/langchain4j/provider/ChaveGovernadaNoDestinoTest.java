package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RCHFLW-42 no caminho dos agentes: um {@code baseUrl} vindo de um patch de fluxo/agente/passo não
 * leva a chave do resolver (ou a {@code archflow.llm.api-key}) a um host fora da lista.
 *
 * <p>O servidor local faz o papel do host estranho e conta o que recebeu do socket — "nenhuma
 * requisição saiu" é afirmado no fio, não na config resolvida.
 */
@DisplayName("chave governada só vai a destino permitido (hub)")
class ChaveGovernadaNoDestinoTest {

    private static final String RESPOSTA = """
            {"id":"c1","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"oi"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}
            """;

    private HttpServer server;
    private final List<String> autorizacoes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void sobe() throws IOException {
        LLMProviderHub.reset();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", troca -> {
            troca.getRequestBody().readAllBytes();
            autorizacoes.add(String.valueOf(troca.getRequestHeaders().getFirst("Authorization")));
            byte[] resposta = RESPOSTA.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, resposta.length);
            troca.getResponseBody().write(resposta);
            troca.close();
        });
        server.start();
    }

    @AfterEach
    void desce() {
        server.stop(0);
        LLMProviderHub.reset();
    }

    private String hostEstranho() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static ResolvedLLMConfig plataforma(Map<String, Object> extra) {
        return ResolvedLLMConfig.builder()
                .provider("openrouter").model("openai/gpt-6-luna").maxTokens(64).temperature(0.2)
                .additionalConfig(extra)
                .build();
    }

    private static DefaultLLMConfigResolver resolvedor(TenantKeyResolver chaves, BaseUrlPolicy politica) {
        return new DefaultLLMConfigResolver(LLMProviderHub.getInstance(), chaves, TierModelResolver.NOOP,
                null, false, politica);
    }

    private LLMResolutionRequest pedidoComBaseUrlNoPasso(ResolvedLLMConfig plataforma) {
        return LLMResolutionRequest.builder(plataforma).tenantId("tenant_a")
                .stepPatch(LLMConfigPatch.builder().additional("baseUrl", hostEstranho()).build())
                .build();
    }

    @Test
    @DisplayName("chave do resolver + baseUrl estranho no passo → falha e nada sai")
    void resolverKeyToForeignHostFails() {
        TenantKeyResolver chaves = (t, p) -> Optional.of("chave-do-tenant");
        var r = resolvedor(chaves, BaseUrlPolicy.officialOnly());

        assertThatThrownBy(() -> r.resolveModel(pedidoComBaseUrlNoPasso(plataforma(Map.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[LLM-HOST]")
                .hasMessageContaining("127.0.0.1");
        assertThatThrownBy(() -> r.resolveEndpoint(pedidoComBaseUrlNoPasso(plataforma(Map.of()))))
                .hasMessageContaining("[LLM-HOST]");
        assertThat(autorizacoes).as("nenhuma requisição pode ter chegado ao host estranho").isEmpty();
    }

    @Test
    @DisplayName("archflow.llm.api-key (default da plataforma) também é governada")
    void platformDefaultKeyIsGoverned() {
        var r = resolvedor(TenantKeyResolver.NOOP, BaseUrlPolicy.officialOnly());

        assertThatThrownBy(() -> r.resolveModel(
                pedidoComBaseUrlNoPasso(plataforma(Map.of("apiKey", "chave-da-plataforma")))))
                .hasMessageContaining("[LLM-HOST]");
        assertThat(autorizacoes).isEmpty();
    }

    @Test
    @DisplayName("host na lista do operador recebe a chave do tenant no Authorization")
    void allowedHostReceivesTheKey() {
        TenantKeyResolver chaves = (t, p) -> Optional.of("chave-do-" + t);
        var r = resolvedor(chaves, BaseUrlPolicy.of(List.of(hostEstranho())));

        ChatModel modelo = r.resolveModel(pedidoComBaseUrlNoPasso(plataforma(Map.of())));
        modelo.chat("oi");

        assertThat(autorizacoes).containsExactly("Bearer chave-do-tenant_a");
    }

    @Test
    @DisplayName("o archflow.llm.base-url do operador é permitido sem estar na lista")
    void operatorBaseUrlIsAllowed() {
        var r = resolvedor(TenantKeyResolver.NOOP, BaseUrlPolicy.officialOnly());
        var plataforma = plataforma(Map.of("apiKey", "chave-da-plataforma", "baseUrl", hostEstranho()));

        r.resolveModel(LLMResolutionRequest.builder(plataforma).build()).chat("oi");

        assertThat(autorizacoes).containsExactly("Bearer chave-da-plataforma");
    }

    @Test
    @DisplayName("chave inline do próprio fluxo vai para onde o fluxo mandar (é do autor)")
    void inlineKeyIsNotGoverned() {
        var r = resolvedor(TenantKeyResolver.NOOP, BaseUrlPolicy.officialOnly());
        var pedido = LLMResolutionRequest.builder(plataforma(Map.of()))
                .stepPatch(LLMConfigPatch.builder()
                        .additional("apiKey", "chave-do-autor").additional("baseUrl", hostEstranho()).build())
                .build();

        r.resolveModel(pedido).chat("oi");

        assertThat(autorizacoes).containsExactly("Bearer chave-do-autor");
    }

    @Test
    @DisplayName("a chave default da plataforma não acompanha um patch que troca de provider")
    void platformDefaultKeyDoesNotFollowProviderSwitch() {
        var r = resolvedor(TenantKeyResolver.NOOP, BaseUrlPolicy.officialOnly());
        var pedido = LLMResolutionRequest.builder(plataforma(Map.of("apiKey", "chave-do-openrouter")))
                .stepPatch(LLMConfigPatch.builder().provider("openai").build())
                .build();

        assertThat(r.resolverChave(pedido, "openai", r.resolve(pedido)).valor())
                .as("a chave cadastrada para o OpenRouter não pode ir à OpenAI")
                .isNull();
    }
}
