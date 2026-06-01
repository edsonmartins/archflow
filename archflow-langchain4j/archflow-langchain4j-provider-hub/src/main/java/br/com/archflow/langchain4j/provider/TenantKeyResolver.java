package br.com.archflow.langchain4j.provider;

import java.util.Optional;

/**
 * SPI para resolução da chave de API por tenant. O archflow não armazena chaves;
 * cada produto implementa esta interface sobre seu próprio storage (governança,
 * secret manager, etc.).
 *
 * <p>Quando retorna vazio, o resolver cai na chave inline da config
 * ({@code additionalConfig.apiKey}) ou em nenhuma chave (providers que não exigem).
 *
 * @since 1.0.0
 */
@FunctionalInterface
public interface TenantKeyResolver {

    /**
     * Resolve a chave de API para um (tenant, provider).
     *
     * @param tenantId tenant atual (pode ser {@code null} para contexto global)
     * @param provider identificador do provider (ex.: "openai", "openrouter")
     * @return a chave, ou {@link Optional#empty()} para cair no fallback
     */
    Optional<String> resolveApiKey(String tenantId, String provider);

    /** Resolver que nunca fornece chave (default — produto sobrepõe). */
    TenantKeyResolver NOOP = (tenantId, provider) -> Optional.empty();
}
