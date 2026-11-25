package br.com.archflow.api.decision;

import br.com.archflow.decision.DecisionCall;
import br.com.archflow.decision.DecisionException;
import br.com.archflow.decision.DecisionRequest;
import br.com.archflow.decision.HttpDecisionProvider;
import br.com.archflow.decision.Question;
import br.com.archflow.langchain4j.provider.BaseUrlPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RCHFLW-42 no nó {@code decision}: a chave vem da governança e o {@code endpoint} vinha das opções
 * do nó. O servidor local é o host do atacante; o que se confere é que ele não recebeu nada.
 */
@DisplayName("decisão: chave governada não vai para endpoint fora da lista")
class DecisionEndpointGuardTest {

    private HttpServer server;
    private final List<String> autorizacoes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void sobe() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", troca -> {
            troca.getRequestBody().readAllBytes();
            autorizacoes.add(String.valueOf(troca.getRequestHeaders().getFirst("Authorization")));
            troca.sendResponseHeaders(500, -1);
            troca.close();
        });
        server.start();
    }

    @AfterEach
    void desce() {
        server.stop(0);
    }

    private String coletor() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/coleta";
    }

    private static final DecisionRequest PEDIDO = new DecisionRequest(Map.of("x", 1),
            List.of(new Question.YesNo("ok", "Ok?", null, null)));

    private DecisionCall chamada(Map<String, Object> options) {
        return new DecisionCall("tenant_a", "openai/gpt-6-luna", "chave-da-plataforma", Duration.ofSeconds(2),
                options);
    }

    @Test
    @DisplayName("endpoint estranho: DecisionException não repetível e nada chega ao servidor")
    void foreignEndpointIsRefused() {
        var guarda = new DecisionEndpointGuard(new HttpDecisionProvider(), BaseUrlPolicy.officialOnly());

        assertThatThrownBy(() -> guarda.decide(PEDIDO, chamada(Map.of("endpoint", coletor()))))
                .isInstanceOf(DecisionException.class)
                .hasMessageContaining("[LLM-HOST]")
                .hasMessageContaining("options.endpoint")
                .satisfies(e -> assertThat(((DecisionException) e).retryable()).isFalse());
        assertThat(autorizacoes).isEmpty();
    }

    @Test
    @DisplayName("baseUrl estranho no langchain4j-decisions também é recusado")
    void foreignBaseUrlIsRefused() {
        var guarda = new DecisionEndpointGuard(new LangChain4jDecisionProvider(), BaseUrlPolicy.officialOnly());

        assertThatThrownBy(() -> guarda.decide(PEDIDO,
                chamada(Map.of("baseUrl", coletor(), "keyRef", "openrouter"))))
                .isInstanceOf(DecisionException.class)
                .hasMessageContaining("options.baseUrl");
        assertThat(autorizacoes).isEmpty();
    }

    @Test
    @DisplayName("endpoint na lista do operador segue para o provedor")
    void allowedEndpointPasses() {
        var guarda = new DecisionEndpointGuard(new HttpDecisionProvider(), BaseUrlPolicy.of(List.of(coletor())));

        // O servidor responde 500: o que importa aqui é que a chamada saiu, com a chave.
        assertThatThrownBy(() -> guarda.decide(PEDIDO, chamada(Map.of("endpoint", coletor(), "retries", 0))))
                .isInstanceOf(DecisionException.class)
                .hasMessageNotContaining("[LLM-HOST]");
        assertThat(autorizacoes).containsExactly("Bearer chave-da-plataforma");
    }

    /**
     * Mesmo keyRef do {@code langchain4j-decisions} de verdade, mas sem rede: chegar ao {@code decide}
     * do delegado é a prova de que a guarda deixou passar.
     */
    private static final class Lc4jSemRede implements br.com.archflow.decision.DecisionProvider {
        private final LangChain4jDecisionProvider real = new LangChain4jDecisionProvider();

        @Override
        public String id() {
            return real.id();
        }

        @Override
        public java.util.Optional<String> keyRef(Map<String, Object> options) {
            return real.keyRef(options);
        }

        @Override
        public br.com.archflow.decision.DecisionResult decide(DecisionRequest r, DecisionCall c)
                throws DecisionException {
            throw new DecisionException("chegou ao provedor", false);
        }
    }

    @Test
    @DisplayName("config documentada (backend typesafe + baseUrl do OpenRouter, keyRef padrão) passa")
    void documentedTypesafeOverOpenRouterPasses() {
        var guarda = new DecisionEndpointGuard(new Lc4jSemRede(), BaseUrlPolicy.officialOnly());

        // docs/decision-component.md: keyRef ausente → 'typesafe', que não é um LLMProvider.
        assertThatThrownBy(() -> guarda.decide(PEDIDO,
                chamada(Map.of("backend", "typesafe", "baseUrl", "https://openrouter.ai/api"))))
                .hasMessage("chegou ao provedor");
        assertThatThrownBy(() -> guarda.decide(PEDIDO,
                chamada(Map.of("backend", "typesafe", "baseUrl", "https://api.typesafe.ai"))))
                .hasMessage("chegou ao provedor");
    }

    @Test
    @DisplayName("keyRef typesafe não abre a porta para qualquer host")
    void typesafeKeyRefStillRefusesForeignHost() {
        var guarda = new DecisionEndpointGuard(new Lc4jSemRede(), BaseUrlPolicy.officialOnly());

        assertThatThrownBy(() -> guarda.decide(PEDIDO,
                chamada(Map.of("backend", "typesafe", "baseUrl", "https://coletor.example.com/api"))))
                .hasMessageContaining("[LLM-HOST]");
        assertThatThrownBy(() -> guarda.decide(PEDIDO,
                chamada(Map.of("backend", "typesafe", "baseUrl", "http://openrouter.ai/api"))))
                .hasMessageContaining("[LLM-HOST]");
        assertThatThrownBy(() -> guarda.decide(PEDIDO, chamada(Map.of("backend", "typesafe",
                "baseUrl", "https://openrouter.ai.coletor.example.com/api"))))
                .hasMessageContaining("[LLM-HOST]");
    }

    @Test
    @DisplayName("keyRef que é provider de LLM continua presa ao oficial dele (openai não vai ao OpenRouter)")
    void llmProviderKeyRefStaysWithItsOfficialHost() {
        var guarda = new DecisionEndpointGuard(new Lc4jSemRede(), BaseUrlPolicy.officialOnly());

        assertThatThrownBy(() -> guarda.decide(PEDIDO,
                chamada(Map.of("backend", "openai", "baseUrl", "https://openrouter.ai/api"))))
                .hasMessageContaining("[LLM-HOST]");
        assertThatThrownBy(() -> guarda.decide(PEDIDO,
                chamada(Map.of("backend", "openai", "baseUrl", "https://api.openai.com/v1"))))
                .hasMessage("chegou ao provedor");
    }

    /** acme só pode usar o gemma; os outros tenants não têm restrição. */
    private static final br.com.archflow.langchain4j.provider.TenantModelPolicy ACME_RESTRITO =
            tenant -> "acme".equals(tenant)
                    ? java.util.Optional.of(List.of("google/gemma-4-31b-it:free"))
                    : java.util.Optional.empty();

    private static DecisionCall chamadaDe(String tenant, String model) {
        return new DecisionCall(tenant, model, "chave-da-plataforma", Duration.ofSeconds(2),
                Map.of("keyRef", "openrouter"));
    }

    @Test
    @DisplayName("RCHFLW-79: modelo fora dos permitidos do tenant é recusado antes do provedor")
    void modeloForaDosPermitidosERecusado() {
        var guarda = new DecisionEndpointGuard(new Lc4jSemRede(), BaseUrlPolicy.officialOnly(), ACME_RESTRITO);

        assertThatThrownBy(() -> guarda.decide(PEDIDO, chamadaDe("acme", "openai/gpt-6-luna")))
                .isInstanceOf(DecisionException.class)
                .hasMessageContaining("[LLM-MODELO]")
                .hasMessageContaining("openai/gpt-6-luna")
                .satisfies(e -> assertThat(((DecisionException) e).retryable()).isFalse());
        // Restrito e sem modelo: iria o padrão do provedor, que ninguém conferiu contra a lista.
        assertThatThrownBy(() -> guarda.decide(PEDIDO, chamadaDe("acme", null)))
                .hasMessageContaining("[LLM-MODELO]");
    }

    @Test
    @DisplayName("RCHFLW-79: modelo permitido, tenant sem restrição e 'nenhum tenant' seguem para o provedor")
    void modeloPermitidoSegue() {
        var guarda = new DecisionEndpointGuard(new Lc4jSemRede(), BaseUrlPolicy.officialOnly(), ACME_RESTRITO);

        assertThatThrownBy(() -> guarda.decide(PEDIDO, chamadaDe("acme", "google/gemma-4-31b-it:free")))
                .hasMessage("chegou ao provedor");
        assertThatThrownBy(() -> guarda.decide(PEDIDO, chamadaDe("outro", "openai/gpt-6-luna")))
                .hasMessage("chegou ao provedor");
        assertThatThrownBy(() -> guarda.decide(PEDIDO, chamadaDe("SYSTEM", "openai/gpt-6-luna")))
                .hasMessage("chegou ao provedor");
    }

    @Test
    @DisplayName("delegação preserva id, keyRef e requiresKey")
    void delegates() {
        var guarda = new DecisionEndpointGuard(new HttpDecisionProvider(), BaseUrlPolicy.officialOnly());
        assertThat(guarda.id()).isEqualTo(HttpDecisionProvider.ID);
        assertThat(guarda.requiresKey()).isTrue();
        assertThat(guarda.keyRef(Map.of("keyRef", "x"))).contains("x");
    }
}
