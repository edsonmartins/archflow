package br.com.archflow.security.apikey;

import br.com.archflow.model.security.ApiKey;
import br.com.archflow.model.security.ApiKeyScope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integração com PostgreSQL real: aplica a migration
 * {@code V5_1__create_security.sql} e exercita o {@link JdbcApiKeyRepository},
 * incluindo escopos, timestamps opcionais e sobrevivência a "restart".
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Persistência de API keys (PostgreSQL real)")
class JdbcApiKeyRepositoryPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;
    private JdbcApiKeyRepository apiKeys;

    @BeforeAll
    static void applyMigration() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;

        String ddl;
        try (var in = JdbcApiKeyRepositoryPostgresTest.class.getResourceAsStream(
                "/db/migration/V5_1__create_security.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(ddl);
        }
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("DELETE FROM api_keys");
        }
        apiKeys = new JdbcApiKeyRepository(dataSource);
    }

    private static ApiKey newKey(String ownerId) {
        ApiKey key = ApiKey.create("ci-key", ownerId,
                Set.of(ApiKeyScope.WORKFLOW_READ, ApiKeyScope.AGENT_READ));
        key.setKeySecret("sha256-hash-value");
        key.setCreatedBy("admin");
        return key;
    }

    @Test
    @DisplayName("round-trip preserva escopos e metadados")
    void roundTripWithScopes() {
        ApiKey key = newKey("owner-1");
        apiKeys.save(key);

        ApiKey loaded = apiKeys.findById(key.getId()).orElseThrow();
        assertThat(loaded.getKeyId()).isEqualTo(key.getKeyId());
        assertThat(loaded.getKeySecret()).isEqualTo("sha256-hash-value");
        assertThat(loaded.getOwnerId()).isEqualTo("owner-1");
        assertThat(loaded.getCreatedBy()).isEqualTo("admin");
        assertThat(loaded.getScopes())
                .containsExactlyInAnyOrder(ApiKeyScope.WORKFLOW_READ, ApiKeyScope.AGENT_READ);
    }

    @Test
    @DisplayName("findByKeyId e findByOwnerId")
    void lookups() {
        ApiKey a = newKey("owner-1");
        ApiKey b = newKey("owner-1");
        ApiKey c = newKey("owner-2");
        apiKeys.save(a);
        apiKeys.save(b);
        apiKeys.save(c);

        assertThat(apiKeys.findByKeyId(a.getKeyId())).isPresent();
        assertThat(apiKeys.findByKeyId("nope")).isEmpty();
        assertThat(apiKeys.findByOwnerId("owner-1")).hasSize(2);
        assertThat(apiKeys.findByOwnerId("owner-2")).hasSize(1);
    }

    @Test
    @DisplayName("revogar (update enabled=false) não duplica a linha")
    void revokeUpdatesInPlace() {
        ApiKey key = newKey("owner-1");
        apiKeys.save(key);

        key.setEnabled(false);
        apiKeys.save(key);

        assertThat(apiKeys.findByOwnerId("owner-1")).hasSize(1);
        assertThat(apiKeys.findById(key.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("timestamps opcionais persistem (expiresAt/lastUsedAt)")
    void optionalTimestamps() {
        ApiKey key = newKey("owner-1");
        LocalDateTime expiry = LocalDateTime.of(2030, 1, 1, 12, 0, 0);
        key.setExpiresAt(expiry);
        // lastUsedAt deixado nulo de propósito
        apiKeys.save(key);

        ApiKey loaded = apiKeys.findById(key.getId()).orElseThrow();
        assertThat(loaded.getExpiresAt()).isEqualTo(expiry);
        assertThat(loaded.getLastUsedAt()).isNull();
    }

    @Test
    @DisplayName("delete remove a chave")
    void deleteRemoves() {
        ApiKey key = newKey("owner-1");
        apiKeys.save(key);

        apiKeys.delete(key);

        assertThat(apiKeys.findById(key.getId())).isEmpty();
    }

    @Test
    @DisplayName("chave sobrevive a uma nova instância (restart)")
    void survivesRestart() {
        ApiKey key = newKey("owner-1");
        apiKeys.save(key);

        JdbcApiKeyRepository fresh = new JdbcApiKeyRepository(dataSource);
        assertThat(fresh.findByKeyId(key.getKeyId())).isPresent();
    }
}
