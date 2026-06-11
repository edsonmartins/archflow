package br.com.archflow.security.auth;

import br.com.archflow.model.util.Hashing;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blacklist de tokens em memória para deployments single-instance.
 *
 * <p>Armazena o SHA-256 do token (nunca o token em claro) com o instante de
 * expiração; entradas expiradas são removidas de forma oportunista a cada
 * consulta/revogação — sem thread dedicada de limpeza.
 */
public class InMemoryTokenBlacklist implements TokenBlacklist {

    /** Purga completa quando o mapa passa deste tamanho ao revogar. */
    private static final int PURGE_THRESHOLD = 10_000;

    private final Map<String, Long> revokedUntil = new ConcurrentHashMap<>();

    @Override
    public void revoke(String token, Instant expiresAt) {
        if (token == null || expiresAt == null || expiresAt.isBefore(Instant.now())) {
            return; // token já expirado não precisa de entrada
        }
        if (revokedUntil.size() >= PURGE_THRESHOLD) {
            purgeExpired();
        }
        revokedUntil.put(hash(token), expiresAt.toEpochMilli());
    }

    @Override
    public boolean isRevoked(String token) {
        if (token == null) {
            return false;
        }
        String key = hash(token);
        Long expiry = revokedUntil.get(key);
        if (expiry == null) {
            return false;
        }
        if (expiry <= System.currentTimeMillis()) {
            revokedUntil.remove(key);
            return false;
        }
        return true;
    }

    /** Número de entradas ativas (para monitoração/testes). */
    public int size() {
        purgeExpired();
        return revokedUntil.size();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        revokedUntil.entrySet().removeIf(e -> e.getValue() <= now);
    }

    private static String hash(String token) {
        return Hashing.sha256Base64(token);
    }
}
