package br.com.archflow.api.config;

import br.com.archflow.engine.persistence.jdbc.JdbcStateRepository;
import br.com.archflow.model.flow.FlowState;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O dado gravado com o antigo tenant padrão "SYSTEM" continua legível depois da unificação em
 * "__default__" (RCHFLW-13): as migrations {@code V1_3_1} (flow_states/audit_logs) e
 * {@code V6_12_1} (mcp_agent_states) o trazem para o id canônico.
 *
 * <p>Em PostgreSQL real porque a migração do laço usa {@code jsonb_set}, e é ela que garante que a
 * pendência migrada diga pertencer ao mesmo tenant cuja fila a mostra. Um método só, pela mesma
 * razão do {@link MigrationsFlywayPostgresTest}: migração é sequência com estado — o segundo método,
 * do upgrade real, usa um banco próprio no mesmo container justamente para não depender da ordem.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Migração do tenant padrão SYSTEM → __default__")
class TenantPadraoMigracaoPostgresTest {

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

    private static DataSource dataSource(String database) {
        PGSimpleDataSource ds = (PGSimpleDataSource) dataSource();
        ds.setDatabaseName(database);
        return ds;
    }

    private static Flyway flyway(DataSource ds, String target) {
        var config = Flyway.configure().dataSource(ds).locations("classpath:db/migration");
        return (target == null ? config : config.target(target)).load();
    }

    @Test
    @DisplayName("estados, auditoria e laços suspensos gravados como SYSTEM passam a __default__")
    void dadoLegadoPassaAoTenantCanonico() throws Exception {
        DataSource ds = dataSource();

        // (1) schema como estava antes da V1_3_1, com dado gravado pelo motor antigo.
        flyway(ds, "1.3").migrate();
        exec(ds, """
                INSERT INTO flow_states (tenant_id, flow_id, status, variables)
                VALUES ('SYSTEM', 'exec-legado', 'AWAITING_APPROVAL', '{}'::json),
                       ('SYSTEM', 'exec-duplicado', 'COMPLETED', '{}'::json),
                       ('__default__', 'exec-duplicado', 'RUNNING', '{}'::json),
                       ('acme', 'exec-acme', 'RUNNING', '{}'::json)
                """);
        exec(ds, "INSERT INTO audit_logs (tenant_id, flow_id) VALUES ('SYSTEM', 'exec-legado')");

        // (2) V1_3_1 roda; o schema vai até antes da V6_12_1, quando o laço ainda gravava SYSTEM.
        flyway(ds, "6.12").migrate();
        assertThat(count(ds, "SELECT count(*) FROM flow_states WHERE tenant_id = '__default__' "
                + "AND flow_id = 'exec-legado'")).isEqualTo(1);
        assertThat(count(ds, "SELECT count(*) FROM audit_logs WHERE tenant_id = 'SYSTEM'")).isZero();
        assertThat(count(ds, "SELECT count(*) FROM flow_states WHERE tenant_id = 'acme'"))
                .as("tenant real não é tocado").isEqualTo(1);
        assertThat(count(ds, "SELECT count(*) FROM flow_states WHERE tenant_id = 'SYSTEM' "
                + "AND flow_id = 'exec-duplicado'"))
                .as("colisão de PK: a linha SYSTEM fica, em vez de derrubar a migração").isEqualTo(1);

        // O leitor por tenant enxerga o dado antigo sob o id canônico.
        assertThat(new JdbcStateRepository(ds).getStatesByTenant("__default__"))
                .extracting(FlowState::getFlowId)
                .contains("exec-legado");

        exec(ds, """
                INSERT INTO mcp_agent_states (run_id, tenant_id, request_id, state)
                VALUES ('run-legado', 'SYSTEM', 'req-1', '{"runId":"run-legado","tenantId":"SYSTEM"}'::json),
                       ('run-sem-tenant', 'SYSTEM', 'req-2', '{"runId":"run-sem-tenant"}'::json),
                       ('run-acme', 'acme', 'req-3', '{"runId":"run-acme","tenantId":"acme"}'::json)
                """);

        // (3) V6_12_1: coluna e JSON mudam juntos.
        flyway(ds, null).migrate();
        assertThat(count(ds, "SELECT count(*) FROM mcp_agent_states WHERE tenant_id = '__default__' "
                + "AND state::jsonb ->> 'tenantId' = '__default__'")).isEqualTo(2);
        assertThat(count(ds, "SELECT count(*) FROM mcp_agent_states WHERE tenant_id = 'acme' "
                + "AND state::jsonb ->> 'tenantId' = 'acme'")).isEqualTo(1);
        assertThat(count(ds, "SELECT count(*) FROM mcp_agent_states WHERE tenant_id = 'SYSTEM'")).isZero();
    }

    /**
     * O caminho que a produção percorre de fato: o banco já está em 6.12 quando chega a release com
     * a V1_3_1, que fica <em>abaixo</em> da versão aplicada. O teste acima aplica em ordem de versão
     * e por isso não pega isto. Prova as duas metades do contrato: sem {@code outOfOrder} o Flyway
     * recusa e o boot para (é por isso que {@code application-prod.yml} liga
     * {@code spring.flyway.out-of-order}); com ele, a V1_3_1 roda fora de ordem e converte o dado.
     */
    @Test
    @DisplayName("upgrade de um banco já em 6.12: V1_3_1 só roda fora de ordem, e converte o SYSTEM")
    void upgradeDeBancoJaAdiantadoPrecisaDeForaDeOrdem() throws Exception {
        exec(dataSource(), "CREATE DATABASE upgrade_real");
        DataSource ds = dataSource("upgrade_real");

        // Banco de produção da release anterior: tudo aplicado até 6.12, sem as duas migrações
        // desta. Aplicar tudo e apagar as duas linhas do histórico dá exatamente esse estado — as
        // duas só fazem UPDATE de dado, então nenhum DDL fica "aplicado sem registro".
        flyway(ds, null).migrate();
        exec(ds, "DELETE FROM flyway_schema_history WHERE version IN ('1.3.1', '6.12.1')");
        exec(ds, """
                INSERT INTO flow_states (tenant_id, flow_id, status, variables)
                VALUES ('SYSTEM', 'exec-prod', 'AWAITING_APPROVAL', '{}'::json),
                       ('acme', 'exec-acme', 'RUNNING', '{}'::json)
                """);
        exec(ds, "INSERT INTO audit_logs (tenant_id, flow_id) VALUES ('SYSTEM', 'exec-prod')");
        exec(ds, """
                INSERT INTO mcp_agent_states (run_id, tenant_id, request_id, state)
                VALUES ('run-prod', 'SYSTEM', 'req-1', '{"runId":"run-prod","tenantId":"SYSTEM"}'::json)
                """);

        // Sem fora de ordem (o default do Flyway): a migração abaixo da versão atual é recusada.
        assertThatThrownBy(() -> flyway(ds, null).migrate())
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("1.3.1");
        assertThat(count(ds, "SELECT count(*) FROM flow_states WHERE tenant_id = 'SYSTEM'"))
                .as("recusada, não aplicou nada").isEqualTo(1);

        // Como o perfil prod sobe: spring.flyway.out-of-order=true.
        Flyway foraDeOrdem = Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load();
        foraDeOrdem.migrate();

        assertThat(Arrays.stream(foraDeOrdem.info().all())
                .filter(info -> info.getVersion() != null && "1.3.1".equals(info.getVersion().getVersion()))
                .map(MigrationInfo::getState))
                .containsExactly(MigrationState.OUT_OF_ORDER);
        assertThat(count(ds, "SELECT count(*) FROM flow_states WHERE tenant_id = 'SYSTEM'")).isZero();
        assertThat(count(ds, "SELECT count(*) FROM flow_states WHERE tenant_id = '__default__' "
                + "AND flow_id = 'exec-prod'")).isEqualTo(1);
        assertThat(count(ds, "SELECT count(*) FROM flow_states WHERE tenant_id = 'acme'")).isEqualTo(1);
        assertThat(count(ds, "SELECT count(*) FROM audit_logs WHERE tenant_id = 'SYSTEM'")).isZero();
        assertThat(count(ds, "SELECT count(*) FROM mcp_agent_states WHERE tenant_id = '__default__' "
                + "AND state::jsonb ->> 'tenantId' = '__default__'")).isEqualTo(1);
    }

    private static void exec(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static long count(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
