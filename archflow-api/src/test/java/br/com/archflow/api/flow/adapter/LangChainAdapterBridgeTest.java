package br.com.archflow.api.flow.adapter;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainRegistry;
import br.com.archflow.langchain4j.provider.TenantKeyResolver;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A camada de adapters LangChain4j era listada pelo designer e nunca
 * instanciada: {@code createAdapter} não tinha chamador de produção algum, e um
 * nó apontando para "openai" falhava com "component not found" porque adapter
 * não é {@code AIComponent}.
 *
 * <p>Os testes usam o provider de teste registrado via SPI em
 * {@code src/test/resources/META-INF/services} — não tocam rede nem exigem
 * chave real.
 */
@DisplayName("Ponte adapter LangChain4j → AIComponent")
class LangChainAdapterBridgeTest {

    /** Registrado pelo {@link TestAdapterFactory} do SPI de teste. */
    private static final String PROVIDER = "provider-de-teste";

    private static ExecutionContext context(String tenantId) {
        return new DefaultExecutionContext(tenantId, "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    @Test
    @DisplayName("o provider de teste está visível no registry (pré-condição)")
    void providerIsRegistered() {
        assertThat(LangChainRegistry.getProvidersOfType("chat")).contains(PROVIDER);
    }

    @Test
    @DisplayName("executa através do adapter, repassando operação e input")
    void executesThroughTheAdapter() throws Exception {
        var component = new LangChainAdapterComponent(
                PROVIDER, "chat", Map.of("model", "m1"), TenantKeyResolver.NOOP);

        Object out = component.execute("generate", "oi", context("acme"));

        assertThat(out).isEqualTo("eco:generate:oi");
    }

    @Test
    @DisplayName("a criação é preguiçosa — construir o nó não exige chave nem rede")
    void adapterIsCreatedLazily() {
        TestAdapterFactory.created.clear();

        new LangChainAdapterComponent(PROVIDER, "chat", Map.of(), TenantKeyResolver.NOOP);

        assertThat(TestAdapterFactory.created)
                .as("abrir um workflow no designer nao pode explodir por falta de chave")
                .isEmpty();
    }

    @Test
    @DisplayName("a chave vem do resolver do tenant, não do JSON do workflow")
    void apiKeyComesFromTheTenantResolver() throws Exception {
        TestAdapterFactory.created.clear();
        TenantKeyResolver resolver = (tenant, provider) -> Optional.of("chave-de-" + tenant);
        var component = new LangChainAdapterComponent(PROVIDER, "chat", Map.of(), resolver);

        component.execute("generate", "x", context("acme"));

        assertThat(TestAdapterFactory.created).singleElement()
                .satisfies(props -> assertThat(props).containsEntry("api.key", "chave-de-acme"));
    }

    @Test
    @DisplayName("o resolver tem precedência sobre uma chave inline esquecida no nó")
    void resolverWinsOverInlineKey() throws Exception {
        TestAdapterFactory.created.clear();
        TenantKeyResolver resolver = (tenant, provider) -> Optional.of("da-governanca");
        var component = new LangChainAdapterComponent(
                PROVIDER, "chat", Map.of("apiKey", "esquecida-no-json"), resolver);

        component.execute("generate", "x", context("acme"));

        assertThat(TestAdapterFactory.created).singleElement()
                .satisfies(props -> assertThat(props).containsEntry("api.key", "da-governanca"));
    }

    @Test
    @DisplayName("aliases do designer viram os nomes que o adapter lê")
    void designerAliasesAreTranslated() throws Exception {
        TestAdapterFactory.created.clear();
        var component = new LangChainAdapterComponent(
                PROVIDER, "chat", Map.of("model", "gpt-x", "temperature", 0.2),
                TenantKeyResolver.NOOP);

        component.execute("generate", "x", context("acme"));

        assertThat(TestAdapterFactory.created).singleElement().satisfies(props -> {
            assertThat(props).containsEntry("model.name", "gpt-x");
            assertThat(props).containsEntry("temperature", 0.2);
        });
    }

    @Test
    @DisplayName("cada tenant recebe o seu adapter — a chave de um não vaza para o outro")
    void adapterIsCachedPerTenant() throws Exception {
        TestAdapterFactory.created.clear();
        TenantKeyResolver resolver = (tenant, provider) -> Optional.of("chave-de-" + tenant);
        var component = new LangChainAdapterComponent(PROVIDER, "chat", Map.of(), resolver);

        component.execute("generate", "a", context("tenant-a"));
        component.execute("generate", "a2", context("tenant-a"));
        component.execute("generate", "b", context("tenant-b"));

        assertThat(TestAdapterFactory.created)
                .as("um adapter por tenant, reusado entre chamadas do mesmo tenant")
                .hasSize(2);
        assertThat(TestAdapterFactory.created).extracting(p -> p.get("api.key"))
                .containsExactlyInAnyOrder("chave-de-tenant-a", "chave-de-tenant-b");
    }

    @Test
    @DisplayName("metadata sintetizada identifica provider e tipo")
    void metadataIdentifiesTheAdapter() {
        var component = new LangChainAdapterComponent(
                PROVIDER, "chat", Map.of(), TenantKeyResolver.NOOP);

        assertThat(component.componentId()).isEqualTo(PROVIDER + ":chat");
        assertThat(component.getMetadata().id()).isEqualTo(PROVIDER + ":chat");
        assertThat(component.getMetadata().tags()).contains("langchain4j", PROVIDER);
    }

    // ── Discriminação de nós ─────────────────────────────────────────

    @Test
    @DisplayName("tipo de nó do designer vira tipo de adapter")
    void mapsDesignerNodeTypes() {
        assertThat(AdapterNodeTypes.adapterTypeFor("llm-chat")).isEqualTo("chat");
        assertThat(AdapterNodeTypes.adapterTypeFor("vector-store")).isEqualTo("vectorstore");
        assertThat(AdapterNodeTypes.adapterTypeFor("rag")).isEqualTo("chain");
        assertThat(AdapterNodeTypes.adapterTypeFor("tool")).isNull();
    }

    @Test
    @DisplayName("só roteia para adapter quando o registry tem aquele provider naquele tipo")
    void onlyRoutesWhenTheRegistryHasIt() {
        assertThat(AdapterNodeTypes.isAdapterNode("llm-chat", PROVIDER)).isTrue();
        assertThat(AdapterNodeTypes.isAdapterNode("llm-chat", "provider-inexistente"))
                .as("qualquer outra coisa cai no catalogo — zero regressao")
                .isFalse();
        assertThat(AdapterNodeTypes.isAdapterNode("tool", PROVIDER)).isFalse();
        assertThat(AdapterNodeTypes.isAdapterNode("llm-chat", null)).isFalse();
    }

    // ── Adapter de teste ─────────────────────────────────────────────

    /** Registrado via SPI em {@code src/test/resources/META-INF/services}. */
    public static final class TestAdapterFactory
            implements br.com.archflow.langchain4j.core.spi.LangChainAdapterFactory {

        /** Propriedades de cada adapter criado — permite asserção sobre a config efetiva. */
        static final List<Map<String, Object>> created = new CopyOnWriteArrayList<>();

        @Override public String getProvider() {
            return PROVIDER;
        }

        @Override public boolean supports(String type) {
            return "chat".equalsIgnoreCase(type);
        }

        @Override public LangChainAdapter createAdapter(Map<String, Object> properties) {
            created.add(Map.copyOf(properties));
            return new LangChainAdapter() {
                @Override public void configure(Map<String, Object> props) { }
                @Override public void validate(Map<String, Object> props) { }
                @Override public void shutdown() { }
                @Override public Object execute(String operation, Object input, ExecutionContext ctx) {
                    return "eco:" + operation + ":" + input;
                }
            };
        }
    }
}
