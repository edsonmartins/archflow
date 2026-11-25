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

        // Na ordem do Flyway: a V5_1_2 traz tenant e convite, que o repositório grava e lê.
        for (String migration : new String[] {"/db/migration/V5_1__create_security.sql",
                "/db/migration/V5_1_2__add_tenant_and_invitation_to_users.sql"}) {
            String ddl;
            try (var in = JdbcUserRepositoryPostgresTest.class.getResourceAsStream(migration)) {
                ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            try (Connection conn = dataSource.getConnection()) {
                conn.createStatement().execute(ddl);
            }
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

    @Test
    @DisplayName("convite pendente: tenant, hash e validade persistem, sem senha; findAll lista todos")
    void pendingInvitationRoundTrip() {
        User invited = new User();
        invited.setUsername("convidada@x.com");
        invited.setEmail("convidada@x.com");
        invited.setTenantId("tenant_acme");
        invited.setEnabled(false);
        invited.setInvitationTokenHash("hash-do-segredo");
        invited.setInvitationExpiresAt(java.time.LocalDateTime.of(2026, 12, 1, 10, 0));
        invited.addRole(Role.createViewerRole());
        users.save(invited);
        users.save(newUser("ada", "ada@x.com"));

        User loaded = new JdbcUserRepository(dataSource).findByUsername("convidada@x.com").orElseThrow();
        assertThat(loaded.getTenantId()).isEqualTo("tenant_acme");
        assertThat(loaded.getPasswordHash()).isNull();
        assertThat(loaded.isEnabled()).isFalse();
        assertThat(loaded.isInvitationPending()).isTrue();
        assertThat(loaded.getInvitationExpiresAt()).isEqualTo(java.time.LocalDateTime.of(2026, 12, 1, 10, 0));
        assertThat(loaded.hasRole(Role.ROLE_VIEWER)).isTrue();

        // Sem tenant no objeto, a linha grava o tenant padrão explícito.
        assertThat(users.findAll()).extracting(User::getUsername, User::getTenantId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("convidada@x.com", "tenant_acme"),
                        org.assertj.core.groups.Tuple.tuple("ada", "__default__"));
    }

    @Test
    @DisplayName("consumeInvitation compara e apaga o hash na mesma escrita: só o primeiro aceite vence")
    void consumeInvitationIsSingleUse() {
        User invited = new User();
        invited.setUsername("convidada@x.com");
        invited.setEmail("convidada@x.com");
        invited.setTenantId("tenant_acme");
        invited.setEnabled(false);
        invited.setInvitationTokenHash("hash-do-segredo");
        invited.setInvitationExpiresAt(java.time.LocalDateTime.of(2026, 12, 1, 10, 0));
        invited.addRole(Role.createViewerRole());
        String id = users.save(invited).getId();

        assertThat(users.consumeInvitation(id, "hash-errado", "senha-x")).isFalse();
        assertThat(users.consumeInvitation(id, "hash-do-segredo", "senha-do-primeiro")).isTrue();
        // O segundo aceite leu o mesmo hash antes do primeiro gravar: a escrita dele não acha a linha.
        assertThat(users.consumeInvitation(id, "hash-do-segredo", "senha-do-segundo")).isFalse();

        User loaded = users.findById(id).orElseThrow();
        assertThat(loaded.getPasswordHash()).isEqualTo("senha-do-primeiro");
        assertThat(loaded.isEnabled()).isTrue();
        assertThat(loaded.isInvitationPending()).isFalse();
        assertThat(loaded.getInvitationExpiresAt()).isNull();
        assertThat(loaded.hasRole(Role.ROLE_VIEWER)).isTrue();
    }

    @Test
    @DisplayName("gravar um nome de login já usado lança DuplicateUserException, não um erro genérico")
    void duplicateUsernameIsTyped() {
        users.save(newUser("ada@x.com", "ada@x.com"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> users.save(newUser("ada@x.com", "ada@x.com")))
                .isInstanceOf(DuplicateUserException.class);
        assertThat(users.findAll()).hasSize(1);
    }
}
