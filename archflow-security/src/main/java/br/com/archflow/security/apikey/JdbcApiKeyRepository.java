package br.com.archflow.security.apikey;

import br.com.archflow.model.security.ApiKey;
import br.com.archflow.model.security.ApiKeyScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Implementação JDBC do {@link ApiKeyService.ApiKeyRepository} — chaves de API
 * persistidas na tabela {@code api_keys} (ver migration
 * {@code V5_1__create_security.sql}).
 *
 * <p>SQL ANSI portável (PostgreSQL, H2, MySQL). Os escopos são serializados como
 * lista dos nomes de constante de {@link ApiKeyScope} (robusto a mudanças nas
 * strings de permissão) numa coluna TEXT.
 *
 * <p>O {@code keySecret} já chega com hash (SHA-256) — nunca é registrado em log.
 */
public class JdbcApiKeyRepository implements ApiKeyService.ApiKeyRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcApiKeyRepository.class);

    private final DataSource dataSource;

    public JdbcApiKeyRepository(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public ApiKey save(ApiKey apiKey) {
        String updateSql = """
            UPDATE api_keys SET key_id = ?, key_secret = ?, name = ?, owner_id = ?, scopes = ?,
                   enabled = ?, expires_at = ?, last_used_at = ?, created_at = ?, created_by = ?
            WHERE id = ?
            """;
        String insertSql = """
            INSERT INTO api_keys (id, key_id, key_secret, name, owner_id, scopes, enabled,
                                  expires_at, last_used_at, created_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, apiKey.getKeyId());
                ps.setString(2, apiKey.getKeySecret());
                ps.setString(3, apiKey.getName());
                ps.setString(4, apiKey.getOwnerId());
                ps.setString(5, serializeScopes(apiKey.getScopes()));
                ps.setBoolean(6, apiKey.isEnabled());
                ps.setTimestamp(7, timestamp(apiKey.getExpiresAt()));
                ps.setTimestamp(8, timestamp(apiKey.getLastUsedAt()));
                ps.setTimestamp(9, timestamp(apiKey.getCreatedAt()));
                ps.setString(10, apiKey.getCreatedBy());
                ps.setString(11, apiKey.getId());
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, apiKey.getId());
                    ps.setString(2, apiKey.getKeyId());
                    ps.setString(3, apiKey.getKeySecret());
                    ps.setString(4, apiKey.getName());
                    ps.setString(5, apiKey.getOwnerId());
                    ps.setString(6, serializeScopes(apiKey.getScopes()));
                    ps.setBoolean(7, apiKey.isEnabled());
                    ps.setTimestamp(8, timestamp(apiKey.getExpiresAt()));
                    ps.setTimestamp(9, timestamp(apiKey.getLastUsedAt()));
                    ps.setTimestamp(10, timestamp(apiKey.getCreatedAt()));
                    ps.setString(11, apiKey.getCreatedBy());
                    ps.executeUpdate();
                }
            }
            return apiKey;

        } catch (SQLException e) {
            log.error("Failed to save api key {} (keyId {})", apiKey.getId(), apiKey.getKeyId(), e);
            throw new RuntimeException("Failed to save api key " + apiKey.getId(), e);
        }
    }

    @Override
    public Optional<ApiKey> findById(String id) {
        return queryOne("SELECT * FROM api_keys WHERE id = ?", id);
    }

    @Override
    public Optional<ApiKey> findByKeyId(String keyId) {
        return queryOne("SELECT * FROM api_keys WHERE key_id = ?", keyId);
    }

    private Optional<ApiKey> queryOne(String sql, String value) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapApiKey(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to load api key ({})", sql, e);
            throw new RuntimeException("Failed to load api key", e);
        }
    }

    @Override
    public List<ApiKey> findByOwnerId(String ownerId) {
        List<ApiKey> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM api_keys WHERE owner_id = ? ORDER BY created_at DESC")) {
            ps.setString(1, ownerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapApiKey(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list api keys for owner {}", ownerId, e);
            throw new RuntimeException("Failed to list api keys for owner " + ownerId, e);
        }
        return result;
    }

    @Override
    public void delete(ApiKey apiKey) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM api_keys WHERE id = ?")) {
            ps.setString(1, apiKey.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete api key {}", apiKey.getId(), e);
            throw new RuntimeException("Failed to delete api key " + apiKey.getId(), e);
        }
    }

    private ApiKey mapApiKey(ResultSet rs) throws SQLException {
        ApiKey apiKey = new ApiKey();
        apiKey.setId(rs.getString("id"));
        apiKey.setKeyId(rs.getString("key_id"));
        apiKey.setKeySecret(rs.getString("key_secret"));
        apiKey.setName(rs.getString("name"));
        apiKey.setOwnerId(rs.getString("owner_id"));
        apiKey.setScopes(deserializeScopes(rs.getString("scopes")));
        apiKey.setEnabled(rs.getBoolean("enabled"));
        apiKey.setExpiresAt(localDateTime(rs.getTimestamp("expires_at")));
        apiKey.setLastUsedAt(localDateTime(rs.getTimestamp("last_used_at")));
        apiKey.setCreatedAt(localDateTime(rs.getTimestamp("created_at")));
        apiKey.setCreatedBy(rs.getString("created_by"));
        return apiKey;
    }

    private static String serializeScopes(Set<ApiKeyScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ApiKeyScope scope : scopes) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(scope.name());
        }
        return sb.toString();
    }

    private static Set<ApiKeyScope> deserializeScopes(String raw) {
        Set<ApiKeyScope> scopes = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return scopes;
        }
        for (String token : raw.split(",")) {
            String name = token.trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                scopes.add(ApiKeyScope.valueOf(name));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring unknown api key scope '{}'", name);
            }
        }
        return scopes;
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
