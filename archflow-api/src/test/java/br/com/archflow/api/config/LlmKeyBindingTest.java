package br.com.archflow.api.config;

import br.com.archflow.decision.DecisionKeys;
import br.com.archflow.langchain4j.provider.BaseUrlPolicy;
import br.com.archflow.langchain4j.provider.TenantKeyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fiação das chaves de LLM a partir do ambiente — é por variável de ambiente que a VPS configura,
 * então é o relaxed binding que tem de funcionar, não só o YAML.
 */
@DisplayName("Chaves de LLM do ambiente (tenant → plataforma)")
class LlmKeyBindingTest {

    private static StandardEnvironment ambiente(Map<String, Object> variaveis, Map<String, Object> yaml) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        // O nome "systemEnvironment" é o que faz o Binder aplicar o mapeamento de variável de ambiente.
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variaveis));
        env.getPropertySources().addLast(new MapPropertySource("yaml", yaml));
        return env;
    }

    @Test
    @DisplayName("ARCHFLOW_LLM_KEYS_OPENROUTER e a forma com colchetes do tenant")
    void bindsFromEnvironmentAndBrackets() {
        var env = ambiente(Map.of(
                        "ARCHFLOW_LLM_PROVIDER", "openrouter",
                        "ARCHFLOW_LLM_KEYS_OPENROUTER", "plataforma-openrouter",
                        "ARCHFLOW_LLM_TENANTKEYS_ACME_OPENROUTER", "do-acme"),
                Map.of("archflow.llm.tenant-keys[tenant_a].openrouter", "do-tenant-a"));

        TenantKeyResolver r = LlmKeyBinding.tenantKeyResolver(env);

        assertThat(r.resolveApiKey("tenant_a", "openrouter")).contains("do-tenant-a");
        assertThat(r.resolveApiKey("acme", "openrouter")).contains("do-acme");
        assertThat(r.resolveApiKey("outro", "openrouter")).contains("plataforma-openrouter");
        assertThat(r.resolveApiKey("SYSTEM", "openrouter")).contains("plataforma-openrouter");
    }

    @Test
    @DisplayName("o bean tenantKeyResolver é o do ambiente, não mais o NOOP")
    void beanIsThePropertyResolver() {
        var env = ambiente(Map.of("ARCHFLOW_LLM_KEYS_OPENROUTER", "plataforma-openrouter"), Map.of());

        TenantKeyResolver bean = new ArchflowBeanConfiguration().tenantKeyResolver(env);

        assertThat(bean.resolveApiKey("SYSTEM", "openrouter")).contains("plataforma-openrouter");
    }

    @Test
    @DisplayName("ARCHFLOW_LLM_API_KEY vale só para o ARCHFLOW_LLM_PROVIDER")
    void defaultKeyFollowsDefaultProvider() {
        var env = ambiente(Map.of("ARCHFLOW_LLM_PROVIDER", "openrouter", "ARCHFLOW_LLM_API_KEY", "k"), Map.of());

        TenantKeyResolver r = LlmKeyBinding.tenantKeyResolver(env);

        assertThat(r.resolveApiKey(null, "openrouter")).contains("k");
        assertThat(r.resolveApiKey(null, "openai")).isEmpty();
    }

    @Test
    @DisplayName("allowed-base-urls por vírgula + o base-url do operador")
    void baseUrlPolicyFromEnvironment() {
        var env = ambiente(Map.of(
                "ARCHFLOW_LLM_ALLOWED_BASE_URLS", "https://gw.acme.com/v1,proxy.acme.com",
                "ARCHFLOW_LLM_BASE_URL", "http://10.0.0.5:8080/v1"), Map.of());

        BaseUrlPolicy p = LlmKeyBinding.baseUrlPolicy(env);

        assertThat(p.isAllowed("openrouter", "https://gw.acme.com/x")).isTrue();
        assertThat(p.isAllowed("openrouter", "https://proxy.acme.com/v1")).isTrue();
        assertThat(p.isAllowed("openrouter", "http://10.0.0.5:8080/v1")).isTrue();
        assertThat(p.isAllowed("openrouter", "https://coletor.example/v1")).isFalse();
    }

    @Test
    @DisplayName("decisão: tenant → archflow.decision.keys.<ref> → chave de LLM da plataforma")
    void decisionKeysOrder() {
        var config = new ArchflowBeanConfiguration();
        var env = ambiente(Map.of(
                "ARCHFLOW_LLM_KEYS_OPENROUTER", "llm-da-plataforma",
                "ARCHFLOW_DECISION_KEYS_OPENROUTER", "decisao-da-plataforma"),
                Map.of("archflow.llm.tenant-keys[tenant_a].openrouter", "do-tenant-a"));
        DecisionKeys keys = config.decisionKeys(LlmKeyBinding.tenantKeyResolver(env), env);

        assertThat(keys.resolve("tenant_a", "openrouter")).contains("do-tenant-a");
        assertThat(keys.resolve("tenant_b", "openrouter"))
                .as("a chave de decisão cadastrada de propósito vence a de LLM da plataforma")
                .contains("decisao-da-plataforma");

        var semChaveDeDecisao = ambiente(Map.of("ARCHFLOW_LLM_KEYS_OPENROUTER", "llm-da-plataforma"), Map.of());
        DecisionKeys fallback = config.decisionKeys(LlmKeyBinding.tenantKeyResolver(semChaveDeDecisao),
                semChaveDeDecisao);
        assertThat(fallback.resolve("tenant_b", "openrouter")).contains("llm-da-plataforma");
    }

    @Test
    @DisplayName("resolver de produto: comportamento anterior (resolver, depois decision.keys)")
    void productResolverKeepsOldOrder() {
        var config = new ArchflowBeanConfiguration();
        var env = ambiente(Map.of("ARCHFLOW_DECISION_KEYS_OPENROUTER", "decisao"), Map.of());
        TenantKeyResolver doProduto = (t, p) -> "vip".equals(t) ? Optional.of("do-cofre") : Optional.empty();

        DecisionKeys keys = config.decisionKeys(doProduto, env);

        assertThat(keys.resolve("vip", "openrouter")).contains("do-cofre");
        assertThat(keys.resolve("outro", "openrouter")).contains("decisao");
    }
}
