package br.com.archflow.security.auth;

import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.security.jwt.JwtService;
import br.com.archflow.security.password.PasswordService;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA2 T1 entre réplicas: a revogação (logout, refresh token consumido) vive na tabela
 * {@code revoked_tokens} — aplicada aqui pela migration {@code V5_1_3} como está no repositório — e
 * duas instâncias sobre o mesmo banco enxergam a mesma revogação. Em memória, o logout feito numa
 * réplica não valia na outra.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Revogação de tokens durável (PostgreSQL real)")
class JdbcTokenBlacklistPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;

    @BeforeAll
    static void applyMigration() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;
        String ddl;
        try (var in = JdbcTokenBlacklistPostgresTest.class
                .getResourceAsStream("/db/migration/V5_1_3__create_revoked_tokens.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(ddl);
        }
    }

    @BeforeEach
    void limpa() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("DELETE FROM revoked_tokens");
        }
    }

    private static Instant daquiA(Duration duracao) {
        return Instant.now().plus(duracao);
    }

    @Test
    @DisplayName("revogado numa instância, revogado na outra; nunca grava o token em claro")
    void revogacaoCompartilhada() throws SQLException {
        JdbcTokenBlacklist replicaA = new JdbcTokenBlacklist(dataSource);
        JdbcTokenBlacklist replicaB = new JdbcTokenBlacklist(dataSource);

        replicaA.revoke("tok-1", daquiA(Duration.ofMinutes(5)));

        assertThat(replicaB.isRevoked("tok-1")).isTrue();
        assertThat(replicaB.isRevoked("tok-2")).isFalse();
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.createStatement().executeQuery("SELECT token_hash FROM revoked_tokens")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isNotEqualTo("tok-1");
        }
    }

    @Test
    @DisplayName("revogar de novo é idempotente e nunca encurta a revogação")
    void revogarDeNovo() {
        JdbcTokenBlacklist blacklist = new JdbcTokenBlacklist(dataSource);

        blacklist.revoke("tok", daquiA(Duration.ofHours(1)));
        blacklist.revoke("tok", daquiA(Duration.ofMillis(1)));

        assertThat(blacklist.isRevoked("tok")).isTrue();
    }

    @Test
    @DisplayName("entrada vencida não conta e sai na próxima revogação")
    void vencidaNaoConta() throws Exception {
        JdbcTokenBlacklist blacklist = new JdbcTokenBlacklist(dataSource);
        blacklist.revoke("curto", daquiA(Duration.ofMillis(200)));
        Thread.sleep(300);

        assertThat(blacklist.isRevoked("curto")).isFalse();
        blacklist.revoke("outro", daquiA(Duration.ofMinutes(1)));
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM revoked_tokens")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("consumo concorrente entre réplicas: exatamente uma vence")
    void consumoTemUmVencedor() throws Exception {
        int replicas = 8;
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(replicas);
        try {
            List<Future<Boolean>> tentativas = new ArrayList<>();
            for (int i = 0; i < replicas; i++) {
                JdbcTokenBlacklist replica = new JdbcTokenBlacklist(dataSource);
                tentativas.add(pool.submit(() -> {
                    largada.await();
                    return replica.revokeIfAbsent("refresh-1", daquiA(Duration.ofDays(7)));
                }));
            }
            largada.countDown();
            int vencedoras = 0;
            for (Future<Boolean> tentativa : tentativas) {
                if (tentativa.get()) {
                    vencedoras++;
                }
            }
            assertThat(vencedoras).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("logout numa réplica: o refresh token da sessão não renova na outra")
    void logoutValeEmTodaReplica() {
        PasswordService passwords = new PasswordService(4);
        InMemoryUserRepository users = new InMemoryUserRepository();
        User ana = new User();
        ana.setId("u-ana");
        ana.setUsername("ana");
        ana.setEmail("ana@example.com");
        ana.setPasswordHash(passwords.hash("segredo123"));
        ana.setEnabled(true);
        ana.setAccountNonLocked(true);
        ana.addRole(Role.createAdminRole());
        users.save(ana);
        JwtService jwt = new JwtService("test-secret-key-for-jdbc-blacklist-minimum-32-bytes-long");
        AuthService replicaA = new AuthService(jwt, passwords, users, new JdbcTokenBlacklist(dataSource));
        AuthService replicaB = new AuthService(jwt, passwords, users, new JdbcTokenBlacklist(dataSource));

        AuthService.AuthenticationResult sessao = replicaA.authenticate("ana", "segredo123");
        AuthService.AuthenticationResult renovado = replicaB.refreshToken(sessao.refreshToken());
        // Rotação vista pela outra réplica: o refresh já consumido em B não renova em A.
        assertThatThrownBy(() -> replicaA.refreshToken(sessao.refreshToken()))
                .isInstanceOf(AuthService.AuthenticationException.class);

        AuthService.AuthenticationResult outra = replicaA.authenticate("ana", "segredo123");
        replicaA.logout(outra.accessToken());
        assertThatThrownBy(() -> replicaB.refreshToken(outra.refreshToken()))
                .isInstanceOf(AuthService.AuthenticationException.class);
        assertThatThrownBy(() -> replicaB.validateAccessToken(outra.accessToken()))
                .hasMessageContaining("revoked");
        // O reuso acima encerrou a primeira sessão também, em qualquer réplica.
        assertThatThrownBy(() -> replicaA.validateAccessToken(renovado.accessToken()))
                .hasMessageContaining("revoked");
    }
}
