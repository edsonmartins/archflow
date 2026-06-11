package br.com.archflow.security.auth;

import java.time.Instant;

/**
 * Registro de tokens revogados antes da expiração (logout, comprometimento).
 *
 * <p>Implementações devem reter cada token APENAS até seu instante de
 * expiração — depois disso o próprio JWT é inválido e a entrada pode ser
 * descartada, mantendo o conjunto pequeno.
 *
 * <p>{@link InMemoryTokenBlacklist} cobre deployments single-instance; para
 * clusters, implemente esta interface sobre um store compartilhado (ex.:
 * Redis com TTL = expiração do token).
 */
public interface TokenBlacklist {

    /**
     * Revoga um token até seu instante de expiração.
     *
     * @param token     o token (a implementação pode armazenar apenas um hash)
     * @param expiresAt expiração do token — após esse instante a entrada é descartável
     */
    void revoke(String token, Instant expiresAt);

    /**
     * @return {@code true} se o token foi revogado e ainda não expirou
     */
    boolean isRevoked(String token);
}
