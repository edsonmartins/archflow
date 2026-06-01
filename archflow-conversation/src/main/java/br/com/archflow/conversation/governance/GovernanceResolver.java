package br.com.archflow.conversation.governance;

/**
 * Resolve a governança efetiva por tenant (cache + store + defaults).
 *
 * <p>Contrato unificado dos dois {@code AgentGovernanceService}. Decisão herdada do
 * design 0002: <b>nunca retorna {@code null}</b> — na ausência de perfil devolve um
 * snapshot de defaults com {@code fromDatabase=false}.
 *
 * @since 1.0.0
 */
public interface GovernanceResolver {

    /** Snapshot para o tenant (defaults se não houver perfil). */
    GovernanceSnapshot resolve(String tenantId);

    /** Snapshot por id de perfil (defaults se não encontrado). */
    GovernanceSnapshot resolveByProfileId(String profileId);

    /** Invalida o cache de um tenant. */
    void invalidate(String tenantId);

    /** Invalida todo o cache. */
    void invalidateAll();
}
