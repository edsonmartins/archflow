package br.com.archflow.langchain4j.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Precedência da chave do deployment: tenant {@literal >} plataforma por provider {@literal >}
 * {@code archflow.llm.api-key} (só para o provider default).
 */
@DisplayName("PropertyTenantKeyResolver")
class PropertyTenantKeyResolverTest {

    private static PropertyTenantKeyResolver resolver() {
        return new PropertyTenantKeyResolver(
                Map.of("tenant_a", Map.of("openrouter", "chave-do-tenant-a")),
                Map.of("anthropic", "chave-anthropic-da-plataforma"),
                "openrouter", "chave-default-da-plataforma");
    }

    @Test
    @DisplayName("a chave do tenant vence a da plataforma")
    void tenantKeyWins() {
        assertThat(resolver().resolveApiKey("tenant_a", "openrouter")).contains("chave-do-tenant-a");
    }

    @Test
    @DisplayName("ids que diferem só na caixa são tenants distintos: 'TENANT_A' não herda a chave de 'tenant_a'")
    void tenantIdIsCaseSensitive() {
        assertThat(resolver().resolveApiKey("TENANT_A", "openrouter")).contains("chave-default-da-plataforma");
        assertThat(resolver().resolveTenantKey("Tenant_A", "openrouter")).isEmpty();
    }

    @Test
    @DisplayName("tenant sem chave própria cai na da plataforma (decisão de produto)")
    void tenantWithoutKeyFallsBackToPlatform() {
        assertThat(resolver().resolveApiKey("tenant_b", "openrouter")).contains("chave-default-da-plataforma");
        assertThat(resolver().resolveApiKey("tenant_a", "anthropic")).contains("chave-anthropic-da-plataforma");
    }

    @Test
    @DisplayName("archflow.llm.api-key só vale para o archflow.llm.provider")
    void defaultKeyOnlyForDefaultProvider() {
        assertThat(resolver().resolveApiKey(null, "openai"))
                .as("a chave do OpenRouter não pode ir para a OpenAI")
                .isEmpty();
        assertThat(resolver().resolveApiKey(null, "OpenRouter")).contains("chave-default-da-plataforma");
    }

    @Test
    @DisplayName("a chave da plataforma por provider vence a default")
    void perProviderPlatformKeyBeatsDefault() {
        var r = new PropertyTenantKeyResolver(Map.of(), Map.of("openrouter", "especifica"),
                "openrouter", "default");
        assertThat(r.resolveApiKey(null, "openrouter")).contains("especifica");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "SYSTEM", "__default__", "__global__"})
    @DisplayName("sentinelas de 'sem tenant' vão direto à plataforma")
    void noTenantSentinelsUsePlatform(String tenant) {
        var r = new PropertyTenantKeyResolver(
                Map.of("SYSTEM", Map.of("openrouter", "nao-deveria")), Map.of(), "openrouter", "plataforma");
        assertThat(r.resolveApiKey(tenant, "openrouter")).contains("plataforma");
        assertThat(r.resolveTenantKey(tenant, "openrouter")).isEmpty();
    }

    @Test
    @DisplayName("valor em branco conta como ausente")
    void blankValuesAreAbsent() {
        var r = new PropertyTenantKeyResolver(Map.of("tenant_a", Map.of("openrouter", "  ")),
                Map.of("openrouter", ""), "openrouter", " ");
        assertThat(r.resolveApiKey("tenant_a", "openrouter")).isEmpty();
        assertThat(r.platformProviders()).isEmpty();
    }

    @Test
    @DisplayName("nada configurado → vazio (quem chama transforma em erro claro)")
    void nothingConfigured() {
        var r = new PropertyTenantKeyResolver(null, null, null, null);
        assertThat(r.resolveApiKey("tenant_a", "openrouter")).isEmpty();
    }

    @Test
    @DisplayName("toString não expõe valores")
    void toStringHidesSecrets() {
        assertThat(resolver().toString()).doesNotContain("chave-").contains("openrouter", "anthropic");
    }
}
