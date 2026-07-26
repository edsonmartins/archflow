package br.com.archflow.api.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aplica <b>todas</b> as migrations, em ordem, num PostgreSQL limpo.
 *
 * <p>A auditoria registrou isto como incógnita aberta: o conjunto de migrations
 * nunca havia sido aplicado de ponta a ponta em ambiente limpo. Cada módulo traz
 * o seu {@code db/migration}, e o Flyway vê a união do classpath — então uma
 * colisão de versão, uma dependência de ordem quebrada ou sintaxe que só o
 * Postgres aceita só apareceriam no primeiro deploy de verdade.
 *
 * <p>O {@code archflow-api} é o único módulo onde o classpath completo se
 * monta, então é aqui que o teste faz sentido.
 *
 * <p>Sem Docker o teste é ignorado (roda no CI) — a alternativa seria H2, que
 * aceita um dialeto diferente e daria uma falsa aprovação.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Migrations aplicam num Postgres limpo")
class MigrationsApplyCleanlyTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /** Tabelas que o runtime precisa encontrar depois de migrar. */
    private static final List<String> EXPECTED_TABLES = List.of(
            "flow_states",       // V1_1 — estado de execução (resume/aprovações)
            "audit_logs",        // V1_1
            "flows",             // V1_2
            "conversations",     // V2_1
            "chat_messages",     // V3_1
            "users",             // V5_1
            "agent_invocations", // V6_2
            "global_config",     // V6_4
            "mcp_agent_states"); // V6_5 — laços de agente suspensos

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private static Flyway flyway(DataSource ds) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
    }

    @Test
    @DisplayName("o conjunto inteiro aplica do zero, sem colisão de versão")
    void appliesFromScratch() throws SQLException {
        DataSource ds = dataSource();
        Flyway flyway = flyway(ds);

        var result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted)
                .as("nenhuma migration encontrada indica classpath montado errado")
                .isPositive();

        // Nenhuma pendente e nenhuma falha registrada.
        assertThat(Arrays.stream(flyway.info().all())
                .filter(info -> info.getState() != null && info.getState().isFailed())
                .map(MigrationInfo::getVersion)
                .toList())
                .as("migrations que falharam")
                .isEmpty();
        assertThat(flyway.info().pending()).isEmpty();

        // As tabelas que o runtime resolve precisam existir de fato.
        List<String> missing = new ArrayList<>();
        try (Connection conn = ds.getConnection()) {
            for (String table : EXPECTED_TABLES) {
                try (ResultSet rs = conn.getMetaData()
                        .getTables(null, "public", table, new String[]{"TABLE"})) {
                    if (!rs.next()) {
                        missing.add(table);
                    }
                }
            }
        }
        assertThat(missing).as("tabelas ausentes apos migrar").isEmpty();
    }

    @Test
    @DisplayName("execution_paths existe — a coluna acrescentada em V1_3")
    void executionPathsColumnExists() throws SQLException {
        DataSource ds = dataSource();
        flyway(ds).migrate();

        try (Connection conn = ds.getConnection();
             ResultSet rs = conn.getMetaData()
                     .getColumns(null, "public", "flow_states", "execution_paths")) {
            assertThat(rs.next())
                    .as("V1_3 acrescenta a coluna; sem ela o resume perde a arvore de execucao")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("migrar duas vezes é idempotente — o segundo boot não reexecuta nada")
    void migratingTwiceIsIdempotent() {
        DataSource ds = dataSource();
        flyway(ds).migrate();

        var second = flyway(ds).migrate();

        assertThat(second.migrationsExecuted)
                .as("um restart nao pode tentar reaplicar o schema")
                .isZero();
    }
}
