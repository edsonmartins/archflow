package br.com.archflow.security.auth;

import java.time.Instant;

/**
 * Registro de tokens revogados antes da expiração (logout, comprometimento).
 *
 * <p>Implementações devem reter cada token APENAS até seu instante de
 * expiração — depois disso o próprio JWT é inválido e a entrada pode ser
 * descartada, mantendo o conjunto pequeno.
 *
 * <p>{@link InMemoryTokenBlacklist} cobre deployments single-instance; {@link JdbcTokenBlacklist}
 * (tabela {@code revoked_tokens}, ligada com {@code archflow.persistence.jdbc.enabled=true}) é o
 * store compartilhado entre réplicas — um logout feito numa réplica precisa valer nas outras.
 *
 * <p>A chave não é só o token: o {@code AuthService} também revoga uma <em>sessão</em> inteira
 * (o logout encerra o access e o refresh dela) registrando uma chave derivada do id da sessão.
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

    /**
     * Revoga o token só se ele ainda não estava revogado, numa operação atômica: entre chamadas
     * concorrentes com o mesmo token, exatamente uma recebe {@code true}.
     *
     * <p>É o que torna o refresh token de uso único (rotação): o refresh "consome" o token
     * apresentado com esta chamada. Ler com {@link #isRevoked} e depois {@link #revoke} deixaria dois
     * refreshes simultâneos do mesmo token passarem pela leitura antes de qualquer um gravar.
     *
     * @return {@code true} se esta chamada revogou o token; {@code false} se ele já estava revogado
     *         (ou se {@code expiresAt} já passou — não há o que consumir)
     */
    boolean revokeIfAbsent(String token, Instant expiresAt);
}
