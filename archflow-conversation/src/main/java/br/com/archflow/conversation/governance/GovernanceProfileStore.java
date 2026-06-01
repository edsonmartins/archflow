package br.com.archflow.conversation.governance;

import java.util.Optional;

/**
 * SPI de persistência de perfis de governança. O archflow define o contrato; o
 * PRODUTO implementa (JPA, etc.) e cuida da (de)serialização do JSON de settings
 * e de qualquer mapeamento de tenant (ex.: o hack base64 do gestor fica aqui,
 * fora do core).
 *
 * @since 1.0.0
 */
public interface GovernanceProfileStore {

    /** Perfil ativo do tenant, se houver. */
    Optional<GovernanceProfile> findActiveByTenant(String tenantId);

    /** Perfil ativo por id, se houver. */
    Optional<GovernanceProfile> findActiveById(String profileId);

    /** Store vazio (sem perfis) — útil para defaults/testes. */
    GovernanceProfileStore EMPTY = new GovernanceProfileStore() {
        @Override public Optional<GovernanceProfile> findActiveByTenant(String tenantId) { return Optional.empty(); }
        @Override public Optional<GovernanceProfile> findActiveById(String profileId) { return Optional.empty(); }
    };
}
