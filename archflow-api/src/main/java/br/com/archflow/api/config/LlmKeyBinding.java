package br.com.archflow.api.config;

import br.com.archflow.langchain4j.provider.BaseUrlPolicy;
import br.com.archflow.langchain4j.provider.PropertyTenantKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lê do {@link Environment} as chaves de LLM e os destinos permitidos.
 *
 * <p>Formato (YAML; os valores vêm do ambiente, nunca do repositório):
 * <pre>
 * archflow:
 *   llm:
 *     provider: openrouter
 *     api-key: ${ARCHFLOW_LLM_API_KEY:}          # default, só para o provider acima
 *     keys:
 *       openrouter: ${ARCHFLOW_LLM_KEYS_OPENROUTER:}
 *     tenant-keys:
 *       "[tenant_a]":
 *         openrouter: ${TENANT_A_OPENROUTER_KEY:}
 *     allowed-base-urls: https://gateway.acme.com/v1
 * </pre>
 *
 * <p>Pelo relaxed binding, {@code ARCHFLOW_LLM_KEYS_OPENROUTER} já preenche
 * {@code archflow.llm.keys.openrouter} sem YAML nenhum. Para o tenant, a variável
 * {@code ARCHFLOW_LLM_TENANTKEYS_ACME_OPENROUTER} funciona com ids simples (chega em minúsculas);
 * ids com {@code _}, {@code -} ou maiúsculas precisam da forma com colchetes acima.
 *
 * <p>Classe à parte da configuração para ser testável com um {@code Environment} montado à mão.
 */
public final class LlmKeyBinding {

    private static final Logger log = LoggerFactory.getLogger(LlmKeyBinding.class);

    private LlmKeyBinding() {
    }

    /** O resolver do deployment: tenant, depois plataforma por provider, depois a default. */
    public static PropertyTenantKeyResolver tenantKeyResolver(Environment env) {
        Binder binder = Binder.get(env);
        Map<String, Map<String, String>> porTenant =
                binder.bind("archflow.llm.tenant-keys", tenantKeysBindable()).orElse(Map.of());
        Map<String, String> daPlataforma = binder.bind("archflow.llm.keys",
                Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        var resolver = new PropertyTenantKeyResolver(porTenant, daPlataforma,
                env.getProperty("archflow.llm.provider", "openai"),
                env.getProperty("archflow.llm.api-key", ""));
        // Só nomes e contagem — nunca valores.
        log.info("[LLM-CHAVE] chaves da plataforma para {}; {} tenant(s) com chave própria",
                resolver.platformProviders(), resolver.tenantCount());
        return resolver;
    }

    /** Oficiais + {@code archflow.llm.allowed-base-urls} + o {@code archflow.llm.base-url} do operador. */
    public static BaseUrlPolicy baseUrlPolicy(Environment env) {
        List<String> lista = new ArrayList<>(Binder.get(env)
                .bind("archflow.llm.allowed-base-urls", Bindable.listOf(String.class))
                .orElse(List.of()));
        String doOperador = env.getProperty("archflow.llm.base-url", "");
        if (!doOperador.isBlank()) {
            lista.add(doOperador);
        }
        return BaseUrlPolicy.of(lista);
    }

    @SuppressWarnings("unchecked")
    private static Bindable<Map<String, Map<String, String>>> tenantKeysBindable() {
        ResolvableType chavesDoTenant = ResolvableType.forClassWithGenerics(Map.class, String.class, String.class);
        return (Bindable<Map<String, Map<String, String>>>) (Bindable<?>) Bindable.of(
                ResolvableType.forClassWithGenerics(Map.class, ResolvableType.forClass(String.class),
                        chavesDoTenant));
    }
}
