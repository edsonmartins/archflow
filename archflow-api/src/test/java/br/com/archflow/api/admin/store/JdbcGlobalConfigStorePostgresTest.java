package br.com.archflow.api.admin.store;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roundtrip do {@link JdbcGlobalConfigStore} contra PostgreSQL real, com o
 * schema criado pela migration {@code V6_4__create_global_config.sql}.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("JdbcGlobalConfigStore (PostgreSQL real)")
class JdbcGlobalConfigStorePostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource dataSource;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("get vazio, put insere, put de novo atualiza (upsert)")
    void roundtrip() {
        JdbcGlobalConfigStore store = new JdbcGlobalConfigStore(dataSource);

        assertThat(store.get("models")).isEmpty();

        store.put("models", "[{\"id\":\"gpt-4o\"}]");
        assertThat(store.get("models")).contains("[{\"id\":\"gpt-4o\"}]");

        store.put("models", "[]");
        assertThat(store.get("models")).contains("[]");

        // chaves independentes
        store.put("featureToggles", "{\"debugMode\":true}");
        assertThat(store.get("featureToggles")).contains("{\"debugMode\":true}");
        assertThat(store.get("models")).contains("[]");
    }
}
