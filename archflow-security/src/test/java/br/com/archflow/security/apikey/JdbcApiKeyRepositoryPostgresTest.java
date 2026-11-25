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
 * Integração com PostgreSQL real: aplica as migrations
 * {@code V5_1__create_security.sql} e {@code V5_1_1__add_tenant_to_api_keys.sql} e exercita o
 * {@link JdbcApiKeyRepository}, incluindo escopos, timestamps opcionais e sobrevivência a "restart".
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Persistência de API keys (PostgreSQL real)")
class JdbcApiKeyRepositoryPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;
    /** {@code enabled} e {@code tenant_id} da linha legada logo depois da V5_1_1. */
    private static boolean legacyEnabledAfterMigration;
    private static String legacyTenantAfterMigration;
    private JdbcApiKeyRepository apiKeys;

    @BeforeAll
    static void applyMigration() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;

        apply("/db/migration/V5_1__create_security.sql");
        // Uma chave de antes da V5_1_1: ativa, sem tenant, dono vindo do X-User-Id.
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("INSERT INTO api_keys (id, key_id, key_secret, owner_id, scopes,"
                    + " enabled, created_at) VALUES ('legacy-1', 'ak_legacy', 'hash', 'anonymous', 'ADMIN',"
                    + " TRUE, CURRENT_TIMESTAMP)");
        }
        apply("/db/migration/V5_1_1__add_tenant_to_api_keys.sql");
        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT enabled, tenant_id FROM api_keys WHERE id = 'legacy-1'")) {
            rs.next();
            legacyEnabledAfterMigration = rs.getBoolean("enabled");
            legacyTenantAfterMigration = rs.getString("tenant_id");
        }
    }

    private static void apply(String migration) throws Exception {
        String ddl;
        try (var in = JdbcApiKeyRepositoryPostgresTest.class.getResourceAsStream(migration)) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(ddl);
        }
    }

    @Test
    @DisplayName("V5_1_1 desativa as chaves anteriores a ela: dono não verificado, tenant que ninguém escolheu")
    void legacyKeysAreDisabledByTheMigration() {
        // Ativa, ela viraria uma credencial viva no tenant padrão, invisível para qualquer login.
        assertThat(legacyEnabledAfterMigration).isFalse();
        assertThat(legacyTenantAfterMigration).isEqualTo("__default__");
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
        key.setTenantId("tenant-a");
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
        assertThat(loaded.getTenantId()).isEqualTo("tenant-a");
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
