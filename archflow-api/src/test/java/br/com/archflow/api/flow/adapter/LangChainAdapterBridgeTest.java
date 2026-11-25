package br.com.archflow.api.flow.adapter;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.langchain4j.core.spi.LangChainRegistry;
import br.com.archflow.langchain4j.provider.BaseUrlPolicy;
import br.com.archflow.langchain4j.provider.PropertyTenantKeyResolver;
import br.com.archflow.langchain4j.provider.TenantKeyResolver;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ── Chave da plataforma e destino da chave (RCHFLW-17 / RCHFLW-42) ──

    @Test
    @DisplayName("sem chave do tenant, a da plataforma chega ao api.key — nada no nó")
    void platformKeyReachesTheAdapter() throws Exception {
        TestAdapterFactory.created.clear();
        var resolver = new PropertyTenantKeyResolver(Map.of(), Map.of(PROVIDER, "chave-da-plataforma"),
                "openrouter", "");
        var component = new LangChainAdapterComponent(PROVIDER, "chat", Map.of(), resolver);

        component.execute("generate", "x", context("SYSTEM"));
        component.execute("generate", "x", context("tenant_sem_chave"));

        assertThat(TestAdapterFactory.created).extracting(p -> p.get("api.key"))
                .containsOnly("chave-da-plataforma");
    }

    @Test
    @DisplayName("sem chave em lugar nenhum: erro que diz onde configurar, não o 'api.key required' cru")
    void missingKeyFailsWithActionableMessage() {
        var component = new LangChainAdapterComponent("openrouter", "chat",
                Map.of("model", "openai/gpt-6-luna"), TenantKeyResolver.NOOP);

        assertThatThrownBy(() -> component.execute("generate", "x", context("tenant_a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[LLM-CHAVE]")
                .hasMessageContaining("'openrouter'")
                .hasMessageContaining("'tenant_a'")
                .hasMessageContaining("archflow.llm.keys.openrouter")
                .hasMessageContaining("ARCHFLOW_LLM_KEYS_OPENROUTER")
                .hasMessageNotContaining("api.key");
    }

    @Test
    @DisplayName("chave do resolver + base.url estranho no nó: falha e o adapter nem nasce")
    void governedKeyIsNotSentToAForeignHost() {
        TestAdapterFactory.created.clear();
        TenantKeyResolver resolver = (tenant, provider) -> Optional.of("da-governanca");
        for (String campo : List.of("base.url", "baseUrl", "endpoint")) {
            var component = new LangChainAdapterComponent(PROVIDER, "chat",
                    Map.of(campo, "https://coletor.example/v1"), resolver, BaseUrlPolicy.officialOnly());

            assertThatThrownBy(() -> component.execute("generate", "x", context("acme")))
                    .as(campo)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[LLM-HOST]")
                    .hasMessageContaining(campo)
                    .hasMessageContaining("coletor.example");
        }
        assertThat(TestAdapterFactory.created).as("nenhum adapter configurado com a chave").isEmpty();
    }

    @Test
    @DisplayName("host da lista do operador recebe a chave do resolver")
    void allowedHostKeepsWorking() throws Exception {
        TestAdapterFactory.created.clear();
        TenantKeyResolver resolver = (tenant, provider) -> Optional.of("da-governanca");
        var component = new LangChainAdapterComponent(PROVIDER, "chat",
                Map.of("base.url", "https://gateway.acme.com/v1"), resolver,
                BaseUrlPolicy.of(List.of("gateway.acme.com")));

        component.execute("generate", "x", context("acme"));

        assertThat(TestAdapterFactory.created).singleElement()
                .satisfies(props -> assertThat(props).containsEntry("api.key", "da-governanca"));
    }

    @Test
    @DisplayName("chave inline do próprio nó não é governada: o destino do nó vale (é do autor)")
    void inlineKeyWithCustomHostIsTheAuthorsBusiness() throws Exception {
        TestAdapterFactory.created.clear();
        var component = new LangChainAdapterComponent(PROVIDER, "chat",
                Map.of("apiKey", "do-autor", "base.url", "http://localhost:11434/v1"),
                TenantKeyResolver.NOOP, BaseUrlPolicy.officialOnly());

        component.execute("generate", "x", context("acme"));

        assertThat(TestAdapterFactory.created).singleElement()
                .satisfies(props -> assertThat(props).containsEntry("api.key", "do-autor"));
    }

    // ── Adapters que leem a chave de outra propriedade ──

    @Test
    @DisplayName("embedding openai com openai.api.key no nó continua funcionando sem chave governada")
    void openAiEmbeddingWithItsOwnKeyStillWorks() {
        var component = new LangChainAdapterComponent("openai", "embedding",
                Map.of("openai.api.key", "sk-do-autor", "openai.model", "text-embedding-3-small"),
                TenantKeyResolver.NOOP);

        assertThat(component.effectiveProperties("acme")).containsEntry("openai.api.key", "sk-do-autor");
    }

    @Test
    @DisplayName("embedding openai: a chave governada vai para openai.api.key, que é onde o adapter lê")
    void governedKeyReachesTheEmbeddingKeyProperty() {
        var resolver = new PropertyTenantKeyResolver(Map.of(), Map.of("openai", "sk-da-plataforma"),
                "openrouter", "");
        var component = new LangChainAdapterComponent("openai", "embedding",
                Map.of("openai.api.key", "sk-esquecida", "apiKey", "sk-alias"), resolver);

        Map<String, Object> props = component.effectiveProperties("acme");

        assertThat(props).containsEntry("openai.api.key", "sk-da-plataforma")
                .doesNotContainKey("api.key");
    }

    @Test
    @DisplayName("embedding openai: o alias apiKey do designer chega ao openai.api.key")
    void designerAliasReachesTheEmbeddingKeyProperty() {
        var component = new LangChainAdapterComponent("openai", "embedding",
                Map.of("apiKey", "sk-do-autor"), TenantKeyResolver.NOOP);

        assertThat(component.effectiveProperties("acme")).containsEntry("openai.api.key", "sk-do-autor");
    }

    @Test
    @DisplayName("embedding openai sem chave nenhuma: erro [LLM-CHAVE] que diz onde configurar")
    void openAiEmbeddingWithoutAnyKeyFailsActionably() {
        var component = new LangChainAdapterComponent("openai", "embedding", Map.of(), TenantKeyResolver.NOOP);

        assertThatThrownBy(() -> component.effectiveProperties("acme"))
                .hasMessageContaining("[LLM-CHAVE]").hasMessageContaining("archflow.llm.keys.openai");
    }

    @Test
    @DisplayName("tipo que não usa chave de LLM (vector store/memória) não ganha a exigência")
    void nonLlmAdapterTypesDoNotRequireAnLlmKey() {
        var component = new LangChainAdapterComponent("openai", "vectorstore", Map.of(), TenantKeyResolver.NOOP);

        assertThat(component.effectiveProperties("acme")).isEmpty();
    }

    // ── Preferência de provedor do OpenRouter (RCHFLW-41; o corpo cru: ZdrDoNoLlmChatNoFioTest) ──

    @Test
    @DisplayName("OpenRouter com ZDR configurado: a preferência do deployment vai ao adapter, e o nó não a troca")
    void openRouterNodeCarriesDeploymentPreferences() {
        TenantKeyResolver resolver = (tenant, provider) -> Optional.of("da-governanca");
        var prefs = br.com.archflow.model.config.ProviderPreferences.of("true", "deny");
        var component = new LangChainAdapterComponent("openrouter", "chat", Map.of("model", "openai/gpt-6-luna",
                "provider.preferences", Map.of(), "provider.preferences.required", false),
                resolver, BaseUrlPolicy.officialOnly(), prefs, true);

        assertThat(component.effectiveProperties("acme"))
                .containsEntry("provider.preferences", prefs)
                .containsEntry("provider.preferences.required", true);
    }

    @Test
    @DisplayName("adapter que não é o chat do OpenRouter não recebe (nem guarda do nó) as chaves da preferência")
    void otherAdaptersNeverCarryPreferenceKeys() {
        var component = new LangChainAdapterComponent("openai", "chat",
                Map.of("apiKey", "inline", "provider.preferences", Map.of("zdr", false)),
                TenantKeyResolver.NOOP, BaseUrlPolicy.officialOnly(),
                br.com.archflow.model.config.ProviderPreferences.of("true", "deny"), true);

        assertThat(component.effectiveProperties("acme"))
                .doesNotContainKeys("provider.preferences", "provider.preferences.required");
    }

    @Test
    @DisplayName("OpenRouter com required=true e nenhuma preferência: [LLM-ZDR], como no caminho dos agentes")
    void openRouterNodeRefusesWhenRequiredWithoutPreferences() {
        var component = new LangChainAdapterComponent("openrouter", "chat", Map.of("apiKey", "inline"),
                TenantKeyResolver.NOOP, BaseUrlPolicy.officialOnly(),
                br.com.archflow.model.config.ProviderPreferences.NONE, true);

        assertThatThrownBy(() -> component.effectiveProperties("acme"))
                .hasMessageContaining("[LLM-ZDR]").hasMessageContaining("required=true");
    }

    @Test
    @DisplayName("preferência não pesa num destino que não é o OpenRouter (gateway do operador)")
    void preferencesDoNotApplyToOtherHosts() {
        TenantKeyResolver resolver = (tenant, provider) -> Optional.of("da-governanca");
        var component = new LangChainAdapterComponent("openrouter", "chat",
                Map.of("base.url", "https://gateway.acme.com/v1"), resolver,
                BaseUrlPolicy.of(List.of("gateway.acme.com")),
                br.com.archflow.model.config.ProviderPreferences.of("true", null), true);

        assertThat(component.effectiveProperties("acme")).containsEntry("api.key", "da-governanca");
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

    @Test
    @DisplayName("chat: 'execute' (o default do motor) e ausente viram 'generate', mesmo com memória; o resto passa")
    void chatOperationTranslatesTheEngineDefault() throws Exception {
        var component = new LangChainAdapterComponent(PROVIDER, "chat", Map.of(), TenantKeyResolver.NOOP);

        assertThat(component.execute("execute", "oi", context("acme")))
                .as("a memória da execução é uma só: um nó não herda a conversa de outro sem pedir")
                .isEqualTo("eco:generate:oi");
        assertThat(LangChainAdapterComponent.chatOperation(null)).isEqualTo("generate");
        assertThat(LangChainAdapterComponent.chatOperation(" ")).isEqualTo("generate");
        assertThat(LangChainAdapterComponent.chatOperation("chat")).as("pedido explícito").isEqualTo("chat");
        assertThat(LangChainAdapterComponent.chatOperation("generate")).isEqualTo("generate");
    }

    @Test
    @DisplayName("chat: o adapter não vê llm.model nem a config resolvida das variáveis da execução")
    void chatContextHidesModelOverrides() {
        ExecutionContext ctx = context("acme");
        ctx.set(ExecutionKeys.LLM_MODEL, "caro/modelo-escolhido-pela-entrada");
        ctx.set(ExecutionKeys.LLM_RESOLVED_CONFIG, ResolvedLLMConfig.builder().model("x")
                .additionalConfig(Map.of("baseUrl", "https://atacante.example")).build());
        ctx.set("message", "oi");

        ExecutionContext visto = ChatNodeContext.of(ctx);

        assertThat(visto.get(ExecutionKeys.LLM_MODEL)).isEmpty();
        assertThat(visto.get(ExecutionKeys.LLM_RESOLVED_CONFIG)).isEmpty();
        assertThat(visto.getVariables()).doesNotContainKeys(ExecutionKeys.LLM_MODEL,
                ExecutionKeys.LLM_RESOLVED_CONFIG).containsEntry("message", "oi");
        assertThat(visto.get("message")).hasValue("oi");
        assertThat(visto.getChatMemory()).isSameAs(ctx.getChatMemory());
        assertThat(visto.getTenantId()).isEqualTo("acme");
        visto.set("saida", 1);
        assertThat(ctx.get("saida")).as("escrita vai para a execução").hasValue(1);
        assertThat(ctx.get(ExecutionKeys.LLM_MODEL)).as("o contexto original não muda").isPresent();
        assertThat(ChatNodeContext.of(null)).isNull();
    }

    @Test
    @DisplayName("chat: entrada não-texto vira o texto da mensagem")
    void chatInputBecomesText() throws Exception {
        assertThat(LangChainAdapterComponent.messageText("já é texto")).isEqualTo("já é texto");
        assertThat(LangChainAdapterComponent.messageText(Map.of("input", "do input", "message", "x")))
                .isEqualTo("do input");
        assertThat(LangChainAdapterComponent.messageText(Map.of("text", "do text"))).isEqualTo("do text");
        assertThat(LangChainAdapterComponent.messageText(Map.of("prompt", "do prompt"))).isEqualTo("do prompt");
        assertThat(LangChainAdapterComponent.messageText(Map.of("message", " ", "pedido", 1)))
                .as("texto em branco não é mensagem: o modelo vê os dados")
                .asString().contains("\"pedido\":1");
        assertThat(LangChainAdapterComponent.messageText(List.of(1, 2))).isEqualTo("[1,2]");
        assertThat(LangChainAdapterComponent.messageText(7)).isEqualTo("7");
        assertThat(LangChainAdapterComponent.messageText(null)).isNull();

        var component = new LangChainAdapterComponent(PROVIDER, "chat", Map.of(), TenantKeyResolver.NOOP);
        assertThat(component.execute("generate", Map.of("message", "oi"), context("acme")))
                .as("a operação explícita também recebe texto").isEqualTo("eco:generate:oi");
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
