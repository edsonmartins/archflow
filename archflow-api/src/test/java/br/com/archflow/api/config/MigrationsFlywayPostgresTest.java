package br.com.archflow.api.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aplica <b>todas</b> as migrations {@code classpath:db/migration} de todos os
 * módulos no classpath do {@code archflow-api} via Flyway contra um PostgreSQL
 * real. Prova que:
 * <ul>
 *   <li>o esquema de versão prefixado por módulo ({@code V1_x}, {@code V2_x}, …)
 *       não colide — um {@code migrate()} bem-sucedido significa versões únicas;</li>
 *   <li>todo o DDL é PostgreSQL-válido e aplica em ordem;</li>
 *   <li>a segunda execução é idempotente (nada a aplicar).</li>
 * </ul>
 * É a validação de que o boot em produção (Flyway auto-config sobre o DataSource)
 * cria o schema inteiro sem intervenção manual.
 *
 * <p><b>Um método só, de propósito.</b> O container é {@code static} — vale para a
 * classe inteira —, então cada método que chamasse {@code migrate()} encontraria o
 * schema no estado deixado pelo anterior, e a ordem dos métodos no JUnit não é a de
 * declaração. Uma tentativa anterior de separar isto em três testes ("aplica do
 * zero", "coluna existe", "é idempotente") quebrou no CI exatamente assim: o teste
 * do zero rodou por último e não achou nada para aplicar. Migração é sequência com
 * estado; testá-la em passos independentes é testar outra coisa. Novas asserções
 * entram aqui, na ordem em que o boot as encontraria.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Flyway aplica todas as migrations em PostgreSQL real")
class MigrationsFlywayPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    @Test
    @DisplayName("migrate() aplica sem colisão de versão e cria as tabelas de todos os módulos")
    void allMigrationsApplyWithoutCollision() throws Exception {
        DataSource ds = dataSource();

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        // core(2) + conversation(2) + memory-jdbc(1) + observability(1) + security(1) + api/quartz(1)
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(8);

        // `success` só diz que o migrate não estourou: uma migration marcada como
        // falha ou uma que ficou pendente não aparecem ali.
        assertThat(Arrays.stream(flyway.info().all())
                .filter(info -> info.getState() != null && info.getState().isFailed())
                .map(MigrationInfo::getVersion)
                .toList())
                .as("migrations que falharam")
                .isEmpty();
        assertThat(flyway.info().pending()).as("migrations pendentes apos migrar").isEmpty();

        // tabelas de módulos distintos comprovam que cada migration rodou
        assertTableExists(ds, "flow_states");             // archflow-core
        assertTableExists(ds, "flows");                   // archflow-core
        assertTableExists(ds, "conversations");           // archflow-conversation
        assertTableExists(ds, "suspended_conversations"); // archflow-conversation
        assertTableExists(ds, "chat_messages");           // langchain4j-memory-jdbc
        assertTableExists(ds, "af_audit_log");            // archflow-observability
        assertTableExists(ds, "users");                   // archflow-security
        assertTableExists(ds, "api_keys");                // archflow-security
        assertTableExists(ds, "qrtz_job_details");        // archflow-api (Quartz)
        assertTableExists(ds, "agent_invocations");       // archflow-api (fila durável)
        assertTableExists(ds, "workflow_documents");      // archflow-api (runtime do designer)
        assertTableExists(ds, "workflow_executions");     // archflow-api (runtime do designer)
        assertTableExists(ds, "global_config");           // archflow-api (config admin)
        assertTableExists(ds, "mcp_agent_states");        // archflow-api (laços de agente suspensos, V6_5)

        // V1_3 acrescenta uma COLUNA a uma tabela criada em V1_1 — o caso que o
        // Flyway recusa quando chega depois de prefixos mais altos já aplicados.
        // Sem ela o resume perde a árvore de execução.
        assertColumnExists(ds, "flow_states", "execution_paths");

        // segunda execução: idempotente
        MigrateResult again = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(again.migrationsExecuted).isZero();
    }

    private static void assertColumnExists(DataSource ds, String table, String column) throws Exception {
        try (Connection conn = ds.getConnection();
             ResultSet rs = conn.getMetaData().getColumns(null, "public", table, column)) {
            assertThat(rs.next())
                    .as("coluna %s.%s deve existir após o Flyway migrate", table, column)
                    .isTrue();
        }
    }

    private static void assertTableExists(DataSource ds, String table) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.tables "
                             + "WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("tabela %s deve existir após o Flyway migrate", table)
                        .isEqualTo(1);
            }
        }
    }
}
