package br.com.archflow.security.auth;

import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integração com PostgreSQL real: aplica a migration
 * {@code V5_1__create_security.sql} como está no repositório (valida o DDL) e
 * exercita o {@link JdbcUserRepository}, incluindo persistência de papéis e
 * sobrevivência a "restart" (nova instância sobre o mesmo DataSource).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Persistência de usuários (PostgreSQL real)")
class JdbcUserRepositoryPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;
    private JdbcUserRepository users;

    @BeforeAll
    static void applyMigration() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;

        String ddl;
        try (var in = JdbcUserRepositoryPostgresTest.class.getResourceAsStream(
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
            conn.createStatement().execute("DELETE FROM user_roles");
            conn.createStatement().execute("DELETE FROM users");
        }
        users = new JdbcUserRepository(dataSource);
    }

    private static User newUser(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash("$2a$10$hashedvalue");
        user.setFirstName("Ada");
        user.setLastName("Lovelace");
        return user;
    }

    @Test
    @DisplayName("save atribui id e timestamps quando ausentes")
    void saveAssignsIdAndTimestamps() {
        User saved = users.save(newUser("ada", "ada@x.com"));

        assertThat(saved.getId()).startsWith("user-");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("round-trip preserva campos e reidrata papéis com permissões")
    void roundTripWithRoles() {
        User user = newUser("ada", "ada@x.com");
        user.addRole(Role.createAdminRole());
        user.addRole(Role.createDesignerRole());
        users.save(user);

        User loaded = users.findByUsername("ada").orElseThrow();
        assertThat(loaded.getEmail()).isEqualTo("ada@x.com");
        assertThat(loaded.getPasswordHash()).isEqualTo("$2a$10$hashedvalue");
        assertThat(loaded.getFullName()).isEqualTo("Ada Lovelace");
        assertThat(loaded.isEnabled()).isTrue();
        assertThat(loaded.hasRole(Role.ROLE_ADMIN)).isTrue();
        assertThat(loaded.hasRole(Role.ROLE_DESIGNER)).isTrue();
        // permissões reidratadas a partir da definição built-in
        assertThat(loaded.hasPermission("workflow", "create")).isTrue();
    }

    @Test
    @DisplayName("update altera campos e papéis sem duplicar linha")
    void updateChangesFieldsAndRoles() {
        User user = newUser("ada", "ada@x.com");
        user.addRole(Role.createAdminRole());
        users.save(user);

        user.setEmail("ada@new.com");
        user.getRoles().clear();
        user.addRole(Role.createViewerRole());
        users.save(user);

        User loaded = users.findById(user.getId()).orElseThrow();
        assertThat(loaded.getEmail()).isEqualTo("ada@new.com");
        assertThat(loaded.hasRole(Role.ROLE_ADMIN)).isFalse();
        assertThat(loaded.hasRole(Role.ROLE_VIEWER)).isTrue();
        assertThat(loaded.getRoles()).hasSize(1);
    }

    @Test
    @DisplayName("existsByUsername / existsByEmail")
    void existsChecks() {
        users.save(newUser("ada", "ada@x.com"));

        assertThat(users.existsByUsername("ada")).isTrue();
        assertThat(users.existsByUsername("ghost")).isFalse();
        assertThat(users.existsByEmail("ada@x.com")).isTrue();
        assertThat(users.existsByEmail("ghost@x.com")).isFalse();
    }

    @Test
    @DisplayName("delete remove usuário e seus papéis")
    void deleteRemovesUserAndRoles() {
        User user = newUser("ada", "ada@x.com");
        user.addRole(Role.createAdminRole());
        users.save(user);

        users.delete(user);

        assertThat(users.findByUsername("ada")).isEmpty();
        assertThat(users.existsByUsername("ada")).isFalse();
    }

    @Test
    @DisplayName("usuário sobrevive a uma nova instância (restart)")
    void survivesRestart() {
        User user = newUser("ada", "ada@x.com");
        user.addRole(Role.createExecutorRole());
        users.save(user);

        JdbcUserRepository fresh = new JdbcUserRepository(dataSource);
        User loaded = fresh.findByUsername("ada").orElseThrow();
        assertThat(loaded.hasRole(Role.ROLE_EXECUTOR)).isTrue();
    }
}
