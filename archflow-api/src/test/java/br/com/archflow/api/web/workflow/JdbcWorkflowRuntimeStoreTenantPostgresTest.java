package br.com.archflow.api.web.workflow;

import br.com.archflow.model.tenant.DefaultTenant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O {@link JdbcWorkflowRuntimeStore} separa workflows e execuções por tenant (RCHFLW-73) sobre o
 * schema que o Flyway cria de verdade, inclusive a {@code V6_12_2}, e o dado gravado antes da coluna
 * continua legível — no tenant padrão, onde sempre apareceu.
 *
 * <p>Cada método usa um banco próprio no mesmo container: migração é sequência com estado (ver
 * {@code MigrationsFlywayPostgresTest}), e a ordem dos métodos no JUnit não é a de declaração.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("JdbcWorkflowRuntimeStore por tenant (PostgreSQL real)")
class JdbcWorkflowRuntimeStoreTenantPostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static DataSource novoBanco(String nome) throws SQLException {
        PGSimpleDataSource admin = new PGSimpleDataSource();
        admin.setURL(postgres.getJdbcUrl());
        admin.setUser(postgres.getUsername());
        admin.setPassword(postgres.getPassword());
        exec(admin, "CREATE DATABASE " + nome);
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        ds.setDatabaseName(nome);
        return ds;
    }

    private static void migra(DataSource ds, String target) {
        var config = Flyway.configure().dataSource(ds).locations("classpath:db/migration");
        (target == null ? config : config.target(target)).load().migrate();
    }

    private static void exec(DataSource ds, String sql) throws SQLException {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    @DisplayName("fluxo e execução gravados antes da coluna tenant_id passam ao tenant padrão, e só a ele")
    void dadoLegadoFicaNoTenantPadrao() throws Exception {
        DataSource ds = novoBanco("legado");
        // Schema como estava antes desta mudança, com dado gravado pelo store antigo (sem tenant, e
        // o JSON da execução sem a chave "tenantId").
        migra(ds, "6.12.1");
        exec(ds, """
                INSERT INTO workflow_documents (id, document, updated_at)
                VALUES ('wf-antigo', '{"id":"wf-antigo","metadata":{"name":"Antigo"}}', now())
                """);
        exec(ds, """
                INSERT INTO workflow_executions (id, workflow_id, record, updated_at)
                VALUES ('exec-antiga', 'wf-antigo',
                        '{"id":"exec-antiga","workflowId":"wf-antigo","status":"COMPLETED"}', now())
                """);

        migra(ds, null);
        var store = new JdbcWorkflowRuntimeStore(ds);

        assertThat(store.workflows(DefaultTenant.ID)).extracting(wf -> wf.get("id")).containsExactly("wf-antigo");
        assertThat(store.getWorkflow(null, "wf-antigo")).isNotNull();
        assertThat(store.getExecution(DefaultTenant.ID, "exec-antiga"))
                .as("o JSON antigo não tinha a chave; o tenant vem da coluna")
                .containsEntry("tenantId", DefaultTenant.ID);
        assertThat(store.executions(DefaultTenant.ID, "wf-antigo", null)).hasSize(1);

        assertThat(store.workflows("acme")).isEmpty();
        assertThat(store.getWorkflow("acme", "wf-antigo")).isNull();
        assertThat(store.getExecution("acme", "exec-antiga")).isNull();

        // O DEFAULT da migração só preencheu o legado: um INSERT sem tenant agora falha, em vez de
        // cair calado no tenant padrão.
        assertThatThrownBy(() -> exec(ds, """
                INSERT INTO workflow_documents (id, document, updated_at) VALUES ('wf-sem-dono', '{}', now())
                """)).isInstanceOf(SQLException.class).hasMessageContaining("tenant_id");
    }

    @Test
    @DisplayName("workflows, versões, implantações e execuções só são alcançáveis pelo tenant dono")
    void isolamentoPorTenant() throws Exception {
        DataSource ds = novoBanco("isolamento");
        migra(ds, null);
        var store = new JdbcWorkflowRuntimeStore(ds);

        store.putWorkflow("acme", "wf-acme", new LinkedHashMap<>(Map.of("id", "wf-acme", "v", 1)));
        store.putWorkflow("globex", "wf-globex", new LinkedHashMap<>(Map.of("id", "wf-globex")));
        var versao = store.publishWorkflow("acme", "wf-acme", "v1");
        store.deployWorkflow("acme", "wf-acme", "PRODUCTION", versao.id());
        String execucao = String.valueOf(store.createExecution("acme", "wf-acme", "A", versao).get("id"));
        store.recordStep(execucao, "s1", Map.of("status", "COMPLETED"));
        store.completeExecution(execucao, "COMPLETED", null);

        assertThat(store.workflows("acme")).extracting(wf -> wf.get("id")).containsExactly("wf-acme");
        assertThat(store.workflows("globex")).extracting(wf -> wf.get("id")).containsExactly("wf-globex");
        assertThat(store.getWorkflow("globex", "wf-acme")).isNull();
        assertThat(store.hasWorkflow("globex", "wf-acme")).isFalse();
        assertThat(store.workflowVersions("globex", "wf-acme")).isEmpty();
        assertThat(store.getWorkflowVersion("globex", "wf-acme", versao.id())).isNull();
        assertThat(store.publishWorkflow("globex", "wf-acme", "v2")).isNull();
        assertThat(store.getDeployment("globex", "wf-acme", "PRODUCTION")).isNull();
        assertThat(store.deployWorkflow("globex", "wf-acme", "PRODUCTION", versao.id())).isNull();
        assertThat(store.getExecution("globex", execucao)).isNull();
        assertThat(store.executions("globex", null, null)).isEmpty();

        // As mutações internas (callbacks do motor) mantêm o dono gravado no INSERT.
        assertThat(store.getExecution("acme", execucao))
                .containsEntry("tenantId", "acme")
                .containsEntry("status", "COMPLETED");
        assertThat(store.executions("acme", "wf-acme", null)).hasSize(1);

        // Gravar o id de outro tenant falha em vez de mover o fluxo — e nada muda no dono.
        assertThatThrownBy(() -> store.putWorkflow("globex", "wf-acme", Map.of("v", 2)))
                .isInstanceOf(WorkflowRuntimeStore.WorkflowOwnershipException.class);
        assertThat(store.getWorkflow("acme", "wf-acme")).containsEntry("v", 1);

        // Apagar no tenant errado não apaga nada; no certo, leva versões e implantação — e as execuções ficam,
        // com o nome do fluxo (RCHFLW-122): o histórico e quem decidiu cada portão continuam consultáveis.
        assertThat(store.deleteWorkflow("globex", "wf-acme")).isEqualTo(WorkflowRuntimeStore.Deletion.notFound());
        assertThat(store.hasWorkflow("acme", "wf-acme")).isTrue();
        assertThat(store.workflowVersions("acme", "wf-acme")).hasSize(1);
        assertThat(store.deleteWorkflow("acme", "wf-acme")).isEqualTo(WorkflowRuntimeStore.Deletion.deleted());
        assertThat(store.hasWorkflow("acme", "wf-acme")).isFalse();
        assertThat(store.getWorkflow("acme", "wf-acme")).isNull();
        assertThat(store.workflowVersions("acme", "wf-acme")).isEmpty();
        assertThat(store.executions("acme", "wf-acme", null)).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("id", execucao).containsEntry("workflowName", "A"));
        assertThat(store.getExecution("acme", execucao)).containsEntry("status", "COMPLETED");
        assertThat(store.getExecution("globex", execucao)).isNull();
        assertThat(store.workflows("globex")).hasSize(1);
    }

    @Test
    @DisplayName("deleteWorkflow recusa com execução RUNNING/PAUSED, inclusive a que nasce durante a exclusão")
    void exclusaoRecusadaComExecucaoAtiva() throws Exception {
        DataSource ds = novoBanco("exclusao");
        migra(ds, null);
        var store = new JdbcWorkflowRuntimeStore(ds);
        store.putWorkflow("acme", "wf-x", new LinkedHashMap<>(Map.of("id", "wf-x")));
        String rodando = String.valueOf(store.createExecution("acme", "wf-x", "X").get("id"));
        String pausada = String.valueOf(store.createExecution("acme", "wf-x", "X").get("id"));
        store.completeExecution(pausada, "PAUSED", null);

        var recusa = store.deleteWorkflow("acme", "wf-x");

        assertThat(recusa.outcome()).isEqualTo(WorkflowRuntimeStore.Deletion.Outcome.HAS_ACTIVE_EXECUTIONS);
        assertThat(recusa.activeExecutions()).containsExactlyInAnyOrder(rodando, pausada);
        assertThat(store.hasWorkflow("acme", "wf-x")).isTrue();
        store.completeExecution(rodando, "COMPLETED", null);
        store.completeExecution(pausada, "STOPPED", null);
        assertThat(store.deleteWorkflow("acme", "wf-x")).isEqualTo(WorkflowRuntimeStore.Deletion.deleted());
        assertThat(store.executions("acme", "wf-x", null)).hasSize(2);

        // A corrida: um createExecution em andamento — a linha do documento travada, como o store a trava antes
        // do INSERT, e o INSERT ainda sem commit. A exclusão espera a trava e então vê a execução: recusa. Com a
        // checagem antes da exclusão (sem a trava), ela apagava o fluxo de uma execução que acabava de nascer.
        store.putWorkflow("acme", "wf-y", new LinkedHashMap<>(Map.of("id", "wf-y")));
        try (Connection criando = ds.getConnection()) {
            criando.setAutoCommit(false);
            try (Statement st = criando.createStatement()) {
                st.execute("SELECT 1 FROM workflow_documents WHERE id = 'wf-y' FOR UPDATE");
            }
            var exclusao = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> store.deleteWorkflow("acme", "wf-y"));
            Thread.sleep(300);
            assertThat(exclusao).as("a exclusão espera a trava da execução que está nascendo").isNotDone();
            try (Statement st = criando.createStatement()) {
                st.execute("""
                        INSERT INTO workflow_executions (id, workflow_id, record, updated_at, tenant_id)
                        VALUES ('exec-corrida', 'wf-y',
                                '{"id":"exec-corrida","workflowId":"wf-y","status":"RUNNING"}', now(), 'acme')
                        """);
            }
            criando.commit();

            var resultado = exclusao.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(resultado.outcome()).isEqualTo(WorkflowRuntimeStore.Deletion.Outcome.HAS_ACTIVE_EXECUTIONS);
            assertThat(resultado.activeExecutions()).containsExactly("exec-corrida");
        }
        assertThat(store.hasWorkflow("acme", "wf-y")).isTrue();
    }
}
