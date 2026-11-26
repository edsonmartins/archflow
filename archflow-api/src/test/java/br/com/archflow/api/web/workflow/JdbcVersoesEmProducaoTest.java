package br.com.archflow.api.web.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RCHFLW-132: a lista de fluxos diz a versão que o Rodar executa. No JDBC isso é uma consulta para a lista
 * inteira — antes eram a implantação e a versão (com o documento) de cada fluxo, umas quatro idas ao banco por
 * fluxo em cada carga da lista e do Dashboard. A consulta tem de dar o mesmo que a regra do {@code /execute}.
 *
 * <p>H2 em modo PostgreSQL com o DDL das migrações V6_3/V6_6/V6_12_2 (só as três tabelas que a consulta lê): o
 * teste com o Postgres de verdade precisa de Docker.</p>
 */
@DisplayName("JdbcWorkflowRuntimeStore: versões em produção da lista numa consulta só")
class JdbcVersoesEmProducaoTest {

    private JdbcWorkflowRuntimeStore store;
    /** Conexões abertas pelo store: cada consulta do JDBC store abre uma. */
    private final AtomicInteger conexoes = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:versoes-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE workflow_documents (
                        id VARCHAR(64) PRIMARY KEY, document TEXT NOT NULL, updated_at TIMESTAMP NOT NULL,
                        tenant_id VARCHAR(64) NOT NULL)
                    """);
            st.execute("""
                    CREATE TABLE workflow_versions (
                        id VARCHAR(64) PRIMARY KEY, workflow_id VARCHAR(64) NOT NULL, version_no INTEGER NOT NULL,
                        document TEXT NOT NULL, comment VARCHAR(512), created_at TIMESTAMP NOT NULL,
                        CONSTRAINT uq_workflow_version_no UNIQUE (workflow_id, version_no))
                    """);
            st.execute("""
                    CREATE TABLE workflow_deployments (
                        workflow_id VARCHAR(64) NOT NULL, environment VARCHAR(32) NOT NULL,
                        version_id VARCHAR(64) NOT NULL, deployed_at TIMESTAMP NOT NULL,
                        PRIMARY KEY (workflow_id, environment),
                        CONSTRAINT fk_workflow_deployment_version
                            FOREIGN KEY (version_id) REFERENCES workflow_versions (id))
                    """);
        }
        DataSource contado = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, metodo, args) -> {
                    if (metodo.getName().equals("getConnection")) {
                        conexoes.incrementAndGet();
                    }
                    try {
                        return metodo.invoke(ds, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        store = new JdbcWorkflowRuntimeStore(contado);
    }

    private void fluxo(String tenant, String id) {
        store.putWorkflow(tenant, id, new LinkedHashMap<>(Map.of("id", id, "metadata", Map.of("name", id))));
    }

    @Test
    @DisplayName("o número da versão em PRODUCTION de cada fluxo do tenant; sem implantação fica fora")
    void versoesDoTenant() {
        fluxo("acme", "wf-publicado");
        fluxo("acme", "wf-rascunho");
        fluxo("acme", "wf-homolog");
        fluxo("globex", "wf-de-outro");

        var v1 = store.publishWorkflow("acme", "wf-publicado", "v1");
        store.publishWorkflow("acme", "wf-publicado", "v2");
        store.deployWorkflow("acme", "wf-publicado", "PRODUCTION", v1.id());
        // Implantada só em outro ambiente: o /execute roda o rascunho.
        var homolog = store.publishWorkflow("acme", "wf-homolog", "h1");
        store.deployWorkflow("acme", "wf-homolog", "STAGING", homolog.id());
        var outro = store.publishWorkflow("globex", "wf-de-outro", "g1");
        store.deployWorkflow("globex", "wf-de-outro", "PRODUCTION", outro.id());

        assertThat(store.productionVersionNumbers("acme")).containsExactlyInAnyOrderEntriesOf(
                Map.of("wf-publicado", 1));
        assertThat(store.productionVersionNumbers("globex")).containsExactlyInAnyOrderEntriesOf(
                Map.of("wf-de-outro", 1));
        assertThat(store.productionVersionNumbers("initech")).isEmpty();

        // A mesma resposta da regra do /execute (implantação → versão), fluxo a fluxo.
        for (String id : new String[] {"wf-publicado", "wf-rascunho", "wf-homolog"}) {
            var deployment = store.getDeployment("acme", id, "PRODUCTION");
            var version = deployment == null ? null : store.getWorkflowVersion("acme", id, deployment.versionId());
            assertThat(store.productionVersionNumbers("acme").get(id))
                    .as(id).isEqualTo(version == null ? null : version.number());
        }

        // Uma ida ao banco para a lista inteira, por mais fluxos que o tenant tenha.
        conexoes.set(0);
        store.productionVersionNumbers("acme");
        assertThat(conexoes).hasValue(1);
    }
}
