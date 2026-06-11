package br.com.archflow.conversation.persistence.jdbc;

import br.com.archflow.conversation.prompt.PromptVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests JdbcPromptRegistry against H2 (portable ANSI SQL).
 */
@DisplayName("JdbcPromptRegistry")
class JdbcPromptRegistryTest {

    private static javax.sql.DataSource dataSource;
    private JdbcPromptRegistry registry;

    @BeforeAll
    static void createDataSource() throws SQLException {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:promptreg;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS prompt_versions (
                    tenant_id   VARCHAR(64)  NOT NULL,
                    prompt_id   VARCHAR(128) NOT NULL,
                    version     INT          NOT NULL,
                    template    TEXT         NOT NULL,
                    active      BOOLEAN      NOT NULL DEFAULT FALSE,
                    created_at  TIMESTAMP    NOT NULL,
                    PRIMARY KEY (tenant_id, prompt_id, version)
                )
            """);
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("DELETE FROM prompt_versions");
        }
        registry = new JdbcPromptRegistry(dataSource);
    }

    @Test
    @DisplayName("first registered version becomes active automatically")
    void firstVersionActive() {
        PromptVersion v1 = registry.register("acme", "greeting", "Hello {{name}}");

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.active()).isTrue();
        assertThat(registry.getActive("acme", "greeting")).isPresent();
    }

    @Test
    @DisplayName("subsequent versions are inactive and numbered sequentially")
    void subsequentVersionsInactive() {
        registry.register("acme", "greeting", "v1");
        PromptVersion v2 = registry.register("acme", "greeting", "v2");

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.active()).isFalse();
        assertThat(registry.getActive("acme", "greeting").orElseThrow().version()).isEqualTo(1);
    }

    @Test
    @DisplayName("activateVersion swaps the active flag atomically")
    void activateVersion() {
        registry.register("acme", "greeting", "v1");
        registry.register("acme", "greeting", "v2");

        registry.activateVersion("acme", "greeting", 2);

        assertThat(registry.getActive("acme", "greeting").orElseThrow().version()).isEqualTo(2);
        assertThat(registry.getVersion("acme", "greeting", 1).orElseThrow().active()).isFalse();
    }

    @Test
    @DisplayName("activateVersion on unknown version throws")
    void activateUnknownVersion() {
        registry.register("acme", "greeting", "v1");

        assertThatThrownBy(() -> registry.activateVersion("acme", "greeting", 99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("versions are isolated per tenant")
    void tenantIsolation() {
        registry.register("acme", "greeting", "acme template");
        registry.register("globex", "greeting", "globex template");

        assertThat(registry.getActive("acme", "greeting").orElseThrow().template())
                .isEqualTo("acme template");
        assertThat(registry.getActive("globex", "greeting").orElseThrow().template())
                .isEqualTo("globex template");
    }

    @Test
    @DisplayName("listVersions returns newest first; listPromptIds is distinct")
    void listVersionsAndIds() {
        registry.register("acme", "greeting", "v1");
        registry.register("acme", "greeting", "v2");
        registry.register("acme", "farewell", "bye");

        List<PromptVersion> versions = registry.listVersions("acme", "greeting");
        assertThat(versions).extracting(PromptVersion::version).containsExactly(2, 1);
        assertThat(registry.listPromptIds("acme")).containsExactly("farewell", "greeting");
    }
}
