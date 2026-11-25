package br.com.archflow.langchain4j.provider;

import java.util.Optional;
import java.util.Set;

/**
 * SPI para resolução da chave de API por tenant. O archflow não guarda chaves no documento do
 * fluxo; o servidor usa por padrão o {@link PropertyTenantKeyResolver} (propriedades/ambiente), e
 * um produto pode sobrepor o bean para ler do próprio storage (governança, secret manager, etc.).
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

    /** Resolver que nunca fornece chave (útil em teste; o servidor usa {@link PropertyTenantKeyResolver}). */
    TenantKeyResolver NOOP = (tenantId, provider) -> Optional.empty();

    /**
     * Os valores que significam "nenhum tenant". Eram três sentinelas espalhadas — o contexto de
     * execução usa {@code SYSTEM}, o {@code TenantContext} usa {@code __default__} e o cache do
     * adapter usa {@code __global__} — e quem procurasse a chave "do tenant SYSTEM" não acharia
     * nada sem saber por quê. Um lugar só, para o resolver e quem o chama concordarem.
     */
    Set<String> NO_TENANT_SENTINELS = Set.of("SYSTEM", "__default__", "__global__");

    /** {@code true} quando o id não identifica um tenant real (nulo, vazio ou sentinela). */
    static boolean isNoTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() || NO_TENANT_SENTINELS.contains(tenantId);
    }
}
