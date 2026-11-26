package br.com.archflow.api.web.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * As corridas que o QA mediu contra o servidor de validação, no {@link JdbcWorkflowRuntimeStore} sobre o schema
 * real do Flyway (RCHFLW-172, RCHFLW-173, RCHFLW-188 itens 19 e 20).
 *
 * <p>Determinísticas, sem depender do escalonador: o {@link DataSource} do store é embrulhado, e quando a
 * requisição prepara a instrução que grava ({@code UPDATE workflow_documents}, {@code INSERT INTO
 * workflow_versions}...) um gancho dispara a outra requisição e espera um pouco por ela. Com a trava da linha
 * (a correção) a outra fica presa no {@code FOR UPDATE} até o commit; sem ela, termina no meio — e a primeira
 * recria o fluxo excluído, apaga a gravação confirmada ou esbarra numa chave única.</p>
 *
 * <p>Cada método usa um banco próprio no mesmo container, como o
 * {@code JdbcWorkflowRuntimeStoreTenantPostgresTest}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Gravações concorrentes do fluxo (JDBC, PostgreSQL real)")
class GravacaoConcorrenteDoFluxoPostgresTest {

    /** Quanto o gancho espera pela outra requisição antes de deixar a primeira seguir. */
    private static final long ESPERA_MS = 500;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private final ExecutorService outra = Executors.newCachedThreadPool();
    /** O prefixo da instrução que dispara o gancho, e o que ele faz. Disparado uma vez só. */
    private final AtomicReference<String> prefixo = new AtomicReference<>();
    private volatile Runnable gancho;
    private DataSource banco;
    private JdbcWorkflowRuntimeStore store;
    private SpringWorkflowCrudController controller;

    @AfterEach
    void tearDown() {
        outra.shutdownNow();
    }

    @Test
    @DisplayName("RCHFLW-172: DELETE no meio do PUT — depois do 204 o fluxo continua excluído, sem versão órfã")
    void putDuranteExclusaoNaoRessuscita() throws Exception {
        prepara("put_del");
        semeiaPublicado("wf-a");
        Future<ResponseEntity<Map<String, Object>>> exclusao =
                noMeio("UPDATE workflow_documents SET document", () -> controller.delete("wf-a"));

        ResponseEntity<Map<String, Object>> salvar = controller.update("wf-a", corpo("salvo"), "\"t0\"");

        assertThat(exclusao.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(204);
        assertThat(salvar.getStatusCode().value()).isIn(200, 404);
        assertThat(store.getWorkflow(null, "wf-a")).as("o DELETE respondeu 204").isNull();
        assertThat(contagem("SELECT count(*) FROM workflow_versions WHERE workflow_id = 'wf-a'")).isZero();
    }

    @Test
    @DisplayName("RCHFLW-172: DELETE no meio do PATCH de status — o PATCH recebe 404 e o fluxo não volta")
    void patchDuranteExclusaoNaoRessuscita() throws Exception {
        prepara("patch_del");
        semeiaPublicado("wf-b");
        Map<String, Object> corpo = new LinkedHashMap<>(Map.of("status", "active")) {
            private boolean disparou;

            @Override
            public Object get(Object key) {
                if (!disparou && "status".equals(key)) {
                    disparou = true;
                    espera(outra.submit(() -> controller.delete("wf-b")));
                }
                return super.get(key);
            }
        };

        ResponseEntity<Map<String, Object>> patch = controller.setStatus("wf-b", corpo);

        assertThat(patch.getStatusCode().value()).isEqualTo(404);
        assertThat(store.getWorkflow(null, "wf-b")).isNull();
    }

    @Test
    @DisplayName("RCHFLW-173: PATCH no meio de um PUT com If-Match — os dois sobrevivem, ou o PUT leva 409")
    void patchNoMeioDoPutNaoSePerde() throws Exception {
        prepara("patch_put");
        semeia("wf-c");
        Future<ResponseEntity<Map<String, Object>>> patch = noMeio("UPDATE workflow_documents SET document",
                () -> controller.setStatus("wf-c", Map.of("status", "active")));

        ResponseEntity<Map<String, Object>> salvar = controller.update("wf-c", corpo("salvo 7"), "\"t0\"");

        assertThat(patch.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        Map<String, Object> fim = store.getWorkflow(null, "wf-c");
        if (salvar.getStatusCode().value() == 200) {
            assertThat(fim.get("status")).as("o PATCH respondeu 200: o status dele não pode sumir").isEqualTo("active");
            assertThat(descricao(fim)).as("o PUT respondeu 200").isEqualTo("salvo 7");
        } else {
            assertThat(salvar.getStatusCode().value()).isEqualTo(409);
        }
    }

    @Test
    @DisplayName("RCHFLW-188 item 20: DELETE no meio do Publicar — nem 500, nem versão ou implantação órfã")
    void publicarDuranteExclusaoNaoDeixaOrfa() throws Exception {
        prepara("pub_del");
        semeia("wf-d");
        Future<ResponseEntity<Map<String, Object>>> exclusao =
                noMeio("INSERT INTO workflow_versions", () -> controller.delete("wf-d"));

        ResponseEntity<Map<String, Object>> publicar = controller.publish("wf-d", Map.of("comment", "v1"));

        assertThat(exclusao.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(204);
        assertThat(publicar.getStatusCode().value()).isIn(201, 404);
        assertThat(store.getWorkflow(null, "wf-d")).isNull();
        assertThat(contagem("SELECT count(*) FROM workflow_versions WHERE workflow_id = 'wf-d'")).isZero();
        assertThat(contagem("SELECT count(*) FROM workflow_deployments WHERE workflow_id = 'wf-d'")).isZero();
    }

    @Test
    @DisplayName("RCHFLW-188 item 19: duas implantações simultâneas num ambiente novo — as duas 200, sem 500")
    void implantacoesSimultaneas() throws Exception {
        prepara("deploy");
        semeia("wf-e");
        String versao = store.publishWorkflow(null, "wf-e", "v1").id();
        Future<ResponseEntity<WorkflowDeploymentRecord>> segunda = noMeio("INSERT INTO workflow_deployments",
                () -> controller.deploy("wf-e", "STAGING", Map.of("versionId", versao)));

        ResponseEntity<WorkflowDeploymentRecord> primeira =
                controller.deploy("wf-e", "STAGING", Map.of("versionId", versao));

        assertThat(primeira.getStatusCode().value()).isEqualTo(200);
        assertThat(segunda.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(store.getDeployment(null, "wf-e", "STAGING").versionId()).isEqualTo(versao);
    }

    @Test
    @DisplayName("RCHFLW-188 item 19: publicações simultâneas recebem números distintos, nenhuma 500")
    void publicacoesSimultaneasTemNumerosDistintos() throws Exception {
        prepara("pub_pub");
        semeia("wf-f");
        int n = 8;
        CyclicBarrier largada = new CyclicBarrier(n);
        List<Future<ResponseEntity<Map<String, Object>>>> respostas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String comentario = "pub " + i;
            respostas.add(outra.submit(() -> {
                largada.await(5, TimeUnit.SECONDS);
                return controller.publish("wf-f", Map.of("comment", comentario));
            }));
        }
        for (var resposta : respostas) {
            assertThat(resposta.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
        }
        assertThat(store.workflowVersions(null, "wf-f")).extracting(WorkflowVersionRecord::number)
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
    }

    @Test
    @DisplayName("Publicar que se cruza com outro Publicar: PRODUCTION fica na versão mais nova, a que o último 201 "
            + "anunciou")
    void publicacoesCruzadasDeixamAMaisNovaEmProducao() throws Exception {
        prepara("pub_cruzado");
        semeia("wf-g");
        // Depois do INSERT da versão da primeira, o gancho passa para a próxima leitura travada do documento — a
        // da implantação. Com a versão e a implantação em duas transações, ali não há trava nenhuma, e a outra
        // publicação (Salvar B + Publicar B) inteira cabia no meio; com uma transação só, ela espera o commit.
        AtomicReference<Future<ResponseEntity<Map<String, Object>>>> segunda = new AtomicReference<>();
        gancho = () -> {
            gancho = () -> segunda.set(espera(outra.submit(() -> {
                controller.update("wf-g", corpo("rascunho B"), null);
                return controller.publish("wf-g", Map.of("comment", "B"));
            })));
            prefixo.set("SELECT document FROM workflow_documents");
        };
        prefixo.set("INSERT INTO workflow_versions");

        ResponseEntity<Map<String, Object>> primeira = controller.publish("wf-g", Map.of("comment", "A"));

        assertThat(segunda.get()).as("o gancho não disparou na implantação").isNotNull();
        ResponseEntity<Map<String, Object>> depois = segunda.get().get(10, TimeUnit.SECONDS);
        assertThat(primeira.getStatusCode().value()).isEqualTo(201);
        assertThat(depois.getStatusCode().value()).isEqualTo(201);
        int maisNova = Math.max(numero(primeira), numero(depois));
        WorkflowDeploymentRecord producao = store.getDeployment(null, "wf-g", "PRODUCTION");
        assertThat(store.getWorkflowVersion(null, "wf-g", producao.versionId()).number())
                .as("as duas responderam 201 anunciando a própria versão em PRODUCTION; vale a mais nova")
                .isEqualTo(maisNova);
    }

    // ── helpers ──────────────────────────────────────────────────

    private void prepara(String nome) throws SQLException {
        PGSimpleDataSource admin = dataSource(null);
        try (Connection conn = admin.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + nome);
        }
        PGSimpleDataSource ds = dataSource(nome);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        banco = ds;
        store = new JdbcWorkflowRuntimeStore(comGancho(ds));
        controller = new SpringWorkflowCrudController(store, mock(WorkflowDeserializer.class),
                mock(FlowEngine.class), mock(FlowRepository.class), null, null);
    }

    private static PGSimpleDataSource dataSource(String nome) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        if (nome != null) {
            ds.setDatabaseName(nome);
        }
        return ds;
    }

    /** O DataSource do store, com o gancho no {@code prepareStatement} da instrução armada em {@link #prefixo}. */
    private DataSource comGancho(DataSource real) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, metodo, args) -> {
                    Object resultado = invoca(metodo, real, args);
                    if (!"getConnection".equals(metodo.getName())) {
                        return resultado;
                    }
                    Connection conn = (Connection) resultado;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                            (p, m, a) -> {
                                if ("prepareStatement".equals(m.getName()) && a != null
                                        && a[0] instanceof String sql) {
                                    String armado = prefixo.get();
                                    if (armado != null && sql.strip().startsWith(armado)
                                            && prefixo.compareAndSet(armado, null)) {
                                        gancho.run();
                                    }
                                }
                                return invoca(m, conn, a);
                            });
                });
    }

    private static Object invoca(java.lang.reflect.Method metodo, Object alvo, Object[] args) throws Throwable {
        try {
            return metodo.invoke(alvo, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Arma o gancho: na próxima instrução que começa com {@code sql}, a {@code acao} roda noutra thread. */
    private <T> Future<T> noMeio(String sql, Callable<T> acao) {
        AtomicReference<Future<T>> futuro = new AtomicReference<>();
        gancho = () -> futuro.set(espera(outra.submit(acao)));
        prefixo.set(sql);
        return new Future<>() {
            private Future<T> f() {
                assertThat(futuro.get()).as("o gancho não disparou em '%s'", sql).isNotNull();
                return futuro.get();
            }

            @Override public boolean cancel(boolean b) { return f().cancel(b); }
            @Override public boolean isCancelled() { return f().isCancelled(); }
            @Override public boolean isDone() { return f().isDone(); }
            @Override public T get() throws java.util.concurrent.ExecutionException, InterruptedException {
                return f().get();
            }
            @Override public T get(long t, TimeUnit u)
                    throws java.util.concurrent.ExecutionException, InterruptedException, TimeoutException {
                return f().get(t, u);
            }
        };
    }

    /** Espera a outra requisição até {@link #ESPERA_MS}; se ela está presa na trava da linha, segue. */
    private static <F extends Future<?>> F espera(F futuro) {
        try {
            futuro.get(ESPERA_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException presaNaTrava) {
            // Esperado com a correção: a outra só roda depois do commit desta.
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return futuro;
    }

    private void semeia(String id) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        doc.put("status", "draft");
        doc.put("updatedAt", "t0");
        doc.put("metadata", Map.of("name", id, "description", "original"));
        doc.put("steps", List.of());
        doc.put("configuration", Map.of());
        store.putWorkflow(null, id, doc);
    }

    /** Fluxo com a v1 em PRODUCTION — o caso do ticket, em que o fluxo voltava sem as versões. */
    private void semeiaPublicado(String id) {
        semeia(id);
        var versao = store.publishWorkflow(null, id, "v1");
        store.deployWorkflow(null, id, "PRODUCTION", versao.id());
        // O updatedAt do documento continua t0: publicar não grava o rascunho.
    }

    private long contagem(String sql) throws SQLException {
        try (Connection conn = banco.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Map<String, Object> corpo(String descricao) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("status", "draft");
        corpo.put("metadata", Map.of("name", "fluxo", "description", descricao));
        corpo.put("steps", List.of());
        corpo.put("configuration", Map.of());
        return corpo;
    }

    /** O número da versão que a resposta 201 do Publicar anunciou. */
    private static int numero(ResponseEntity<Map<String, Object>> publicar) {
        return ((WorkflowVersionRecord) publicar.getBody().get("version")).number();
    }

    @SuppressWarnings("unchecked")
    private static Object descricao(Map<String, Object> doc) {
        return ((Map<String, Object>) doc.get("metadata")).get("description");
    }
}
