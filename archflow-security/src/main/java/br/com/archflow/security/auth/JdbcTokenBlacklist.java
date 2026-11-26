package br.com.archflow.security.auth;

import br.com.archflow.model.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * {@link TokenBlacklist} sobre a tabela {@code revoked_tokens} (migration
 * {@code V5_1_3__create_revoked_tokens.sql}) — o mesmo banco dos usuários e das chaves de API.
 *
 * <p>Existe porque a revogação em memória é por réplica: com duas instâncias atrás do balanceador,
 * o logout feito numa delas não valia na outra, e o refresh token consumido numa ainda renovava na
 * outra. Aqui toda réplica lê e grava o mesmo conjunto, e a unicidade da chave primária é o que faz
 * o consumo do refresh token ({@link #revokeIfAbsent}) ter um único vencedor entre réplicas.
 *
 * <p>Guarda só o SHA-256 da chave (nunca o token em claro) e a expiração em epoch millis. Linhas
 * vencidas são apagadas a cada revogação — depois da expiração o próprio JWT já é inválido.
 *
 * <p>SQL ANSI portável (PostgreSQL, H2, MySQL).
 */
public class JdbcTokenBlacklist implements TokenBlacklist {

    private static final Logger log = LoggerFactory.getLogger(JdbcTokenBlacklist.class);

    private final DataSource dataSource;

    public JdbcTokenBlacklist(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public void revoke(String token, Instant expiresAt) {
        if (token == null || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            return; // token já expirado não precisa de entrada
        }
        String key = Hashing.sha256Base64(token);
        try (Connection conn = dataSource.getConnection()) {
            purgeExpired(conn);
            if (insert(conn, key, expiresAt)) {
                return;
            }
            // Já revogado: revogar de novo é idempotente, mas nunca encurta a revogação.
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE revoked_tokens SET expires_at = ? WHERE token_hash = ? AND expires_at < ?")) {
                ps.setLong(1, expiresAt.toEpochMilli());
                ps.setString(2, key);
                ps.setLong(3, expiresAt.toEpochMilli());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            // Falha alta: um logout que "deu certo" sem gravar deixaria a sessão viva.
            log.error("Failed to revoke token", e);
            throw new IllegalStateException("Failed to revoke token", e);
        }
    }

    @Override
    public boolean revokeIfAbsent(String token, Instant expiresAt) {
        if (token == null || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            return false;
        }
        try (Connection conn = dataSource.getConnection()) {
            // A linha vencida de um token que já expirou sairia daqui; um token vivo nunca tem linha
            // vencida, então a disputa fica só na chave primária do INSERT.
            purgeExpired(conn);
            return insert(conn, Hashing.sha256Base64(token), expiresAt);
        } catch (SQLException e) {
            log.error("Failed to consume token", e);
            throw new IllegalStateException("Failed to consume token", e);
        }
    }

    @Override
    public boolean isRevoked(String token) {
        if (token == null) {
            return false;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM revoked_tokens WHERE token_hash = ? AND expires_at > ?")) {
            ps.setString(1, Hashing.sha256Base64(token));
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            // Sem resposta do store não há como afirmar que o token não foi revogado: a exceção
            // chega ao filtro, que recusa a requisição com 503 (não 401: o token pode estar
            // perfeitamente válido) em vez de liberá-la.
            log.error("Failed to check token revocation", e);
            throw new IllegalStateException("Failed to check token revocation", e);
        }
    }

    /** @return {@code true} se inseriu; {@code false} se a chave já existia */
    private static boolean insert(Connection conn, String key, Instant expiresAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO revoked_tokens (token_hash, expires_at) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setLong(2, expiresAt.toEpochMilli());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (JdbcUserRepository.isUniqueViolation(e)) {
                return false;
            }
            throw e;
        }
    }

    private static void purgeExpired(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM revoked_tokens WHERE expires_at <= ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}
