package br.com.archflow.langchain4j.provider;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolver de chave do deployment: chave do tenant, e na falta dela a da plataforma.
 *
 * <p>Ordem, do mais específico ao mais geral:
 * <ol>
 *   <li>{@code archflow.llm.tenant-keys.<tenant>.<provider>} — a chave própria do tenant;</li>
 *   <li>{@code archflow.llm.keys.<provider>} — a chave da plataforma para aquele provider
 *       (env {@code ARCHFLOW_LLM_KEYS_OPENROUTER});</li>
 *   <li>{@code archflow.llm.api-key} — a chave default da plataforma, <b>só</b> quando o provider
 *       pedido é o {@code archflow.llm.provider}. Ela foi cadastrada para aquele provider; mandá-la
 *       a outro entregaria a credencial de um fornecedor a um terceiro.</li>
 * </ol>
 *
 * <h2>Por que existe</h2>
 * O bean padrão era {@link TenantKeyResolver#NOOP}. O caminho dos agentes ainda achava a chave da
 * plataforma (pelo {@code additionalConfig.apiKey} do default), mas um nó {@code llm-chat} — que
 * vai pela ponte de adapters — só tinha a chave se alguém a escrevesse no JSON do fluxo, o que a
 * regra "segredo não mora no workflow" proíbe. Com este resolver os três consumidores (adapter,
 * hub de agentes e nó de decisão) leem a mesma fonte.
 *
 * <h2>Tenant sem chave própria cai na da plataforma</h2>
 * Decisão de produto: um tenant que não cadastrou chave usa a da plataforma, como já acontecia com
 * o {@code mcp-agent}. Os sentinelas de "sem tenant" ({@link TenantKeyResolver#isNoTenant}) pulam
 * direto para a plataforma.
 *
 * <p>Os valores nunca aparecem em log nem em {@link #toString()} — só os nomes dos providers.
 */
public final class PropertyTenantKeyResolver implements TenantKeyResolver {

    private final Map<String, Map<String, String>> tenantKeys;
    private final Map<String, String> platformKeys;
    private final String defaultProvider;
    private final String defaultApiKey;

    /**
     * @param tenantKeys    tenant → (provider → chave)
     * @param platformKeys  provider → chave da plataforma
     * @param defaultProvider o {@code archflow.llm.provider}
     * @param defaultApiKey o {@code archflow.llm.api-key} (vale só para o {@code defaultProvider})
     */
    public PropertyTenantKeyResolver(Map<String, ? extends Map<String, String>> tenantKeys,
                                     Map<String, String> platformKeys,
                                     String defaultProvider, String defaultApiKey) {
        Map<String, Map<String, String>> porTenant = new HashMap<>();
        if (tenantKeys != null) {
            tenantKeys.forEach((tenant, chaves) -> {
                if (tenant != null && chaves != null) {
                    porTenant.put(tenant, porProvider(chaves));
                }
            });
        }
        this.tenantKeys = Map.copyOf(porTenant);
        this.platformKeys = porProvider(platformKeys);
        this.defaultProvider = normalizar(defaultProvider);
        this.defaultApiKey = vazioVira(defaultApiKey);
    }

    @Override
    public Optional<String> resolveApiKey(String tenantId, String provider) {
        Optional<String> doTenant = resolveTenantKey(tenantId, provider);
        return doTenant.isPresent() ? doTenant : resolvePlatformKey(provider);
    }

    /**
     * Só a chave própria do tenant, sem cair na plataforma. O nó de decisão precisa disso para pôr
     * a sua chave de plataforma ({@code archflow.decision.keys.<ref>}) entre as duas.
     */
    public Optional<String> resolveTenantKey(String tenantId, String provider) {
        String p = normalizar(provider);
        if (p == null || TenantKeyResolver.isNoTenant(tenantId)) {
            return Optional.empty();
        }
        // Só o id exato. Tentar de novo em minúsculas (para casar com o relaxed binding, que entrega
        // em minúsculas o tenant de uma variável de ambiente) faria 'ACME', sem chave própria, usar
        // a chave de 'acme' — outro tenant — em vez de cair na da plataforma. Id com maiúsculas,
        // '-' ou '_' vem pela forma com colchetes no YAML (ver LlmKeyBinding).
        Map<String, String> chaves = tenantKeys.get(tenantId);
        return chaves == null ? Optional.empty() : Optional.ofNullable(chaves.get(p));
    }

    /** A chave da plataforma para o provider: a específica dele, ou a default se ele é o default. */
    public Optional<String> resolvePlatformKey(String provider) {
        String p = normalizar(provider);
        if (p == null) {
            return Optional.empty();
        }
        String daPlataforma = platformKeys.get(p);
        if (daPlataforma != null) {
            return Optional.of(daPlataforma);
        }
        return p.equals(defaultProvider) ? Optional.ofNullable(defaultApiKey) : Optional.empty();
    }

    /** Providers com chave de plataforma — para log de inicialização, nunca os valores. */
    public Set<String> platformProviders() {
        Set<String> nomes = new TreeSet<>(platformKeys.keySet());
        if (defaultApiKey != null && defaultProvider != null) {
            nomes.add(defaultProvider);
        }
        return nomes;
    }

    /** Quantos tenants têm ao menos uma chave própria. */
    public int tenantCount() {
        return (int) tenantKeys.values().stream().filter(m -> !m.isEmpty()).count();
    }

    @Override
    public String toString() {
        return "PropertyTenantKeyResolver[plataforma=" + platformProviders()
                + ", tenants=" + tenantCount() + "]";
    }

    private static Map<String, String> porProvider(Map<String, String> chaves) {
        Map<String, String> out = new HashMap<>();
        if (chaves != null) {
            chaves.forEach((provider, chave) -> {
                String p = normalizar(provider);
                String v = vazioVira(chave);
                if (p != null && v != null) {
                    out.put(p, v);
                }
            });
        }
        return Map.copyOf(out);
    }

    private static String normalizar(String provider) {
        return provider == null || provider.isBlank() ? null : provider.trim().toLowerCase(Locale.ROOT);
    }

    /** Valor em branco conta como ausente — {@code ARCHFLOW_LLM_KEYS_OPENAI=} não é uma chave. */
    private static String vazioVira(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }
}
