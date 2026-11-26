package br.com.archflow.api.web.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Gravações do mesmo fluxo que se cruzam no store em memória (RCHFLW-172, RCHFLW-173).
 *
 * <p>Sem depender da sorte do escalonador: um gancho dispara a outra requisição exatamente entre a leitura e a
 * gravação da primeira — no {@code get("updatedAt")} da conferência do If-Match, ou no {@code get("status")} do
 * corpo do PATCH — e espera um pouco por ela. Se a primeira segura a trava do store (a correção), a outra fica
 * esperando e só roda depois; se não segura (o defeito), a outra termina no meio e a primeira grava por cima.
 * O que se confere é o invariante que o QA viu quebrar: uma resposta 2xx nunca é desfeita em silêncio.</p>
 */
@DisplayName("Gravações concorrentes do fluxo (store em memória)")
class GravacaoConcorrenteDoFluxoTest {

    /** Quanto o gancho espera pela outra requisição antes de deixar a primeira seguir. */
    private static final long ESPERA_MS = 300;

    private final ExecutorService outra = Executors.newCachedThreadPool();
    private InMemoryWorkflowRuntimeStore store;
    private SpringWorkflowCrudController controller;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowRuntimeStore();
        controller = new SpringWorkflowCrudController(store, mock(WorkflowDeserializer.class),
                mock(FlowEngine.class), mock(FlowRepository.class), null, null);
    }

    @AfterEach
    void tearDown() {
        outra.shutdownNow();
    }

    @Test
    @DisplayName("RCHFLW-172: DELETE entre a leitura e a gravação do PUT — depois do 204 o fluxo continua excluído")
    void putDuranteExclusaoNaoRessuscita() throws Exception {
        DocumentoComGancho doc = semeia("wf-put-del");
        Future<ResponseEntity<Map<String, Object>>> exclusao = noMeio(doc,
                () -> controller.delete("wf-put-del"));

        ResponseEntity<Map<String, Object>> salvar = controller.update("wf-put-del",
                corpo("salvo"), "\"t0\"");

        assertThat(exclusao.get(5, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(204);
        assertThat(salvar.getStatusCode().value()).isIn(200, 404);
        assertThat(store.hasWorkflow(null, "wf-put-del"))
                .as("o DELETE respondeu 204: o PUT não pode recriar o fluxo")
                .isFalse();
    }

    @Test
    @DisplayName("RCHFLW-172: DELETE entre a leitura e a gravação do PATCH de status — o PATCH recebe 404")
    void patchDuranteExclusaoNaoRessuscita() throws Exception {
        semeia("wf-patch-del");
        CorpoComGancho corpo = new CorpoComGancho("active");
        corpo.gancho = () -> espera(outra.submit(() -> controller.delete("wf-patch-del")));

        ResponseEntity<Map<String, Object>> patch = controller.setStatus("wf-patch-del", corpo);

        assertThat(patch.getStatusCode().value()).isEqualTo(404);
        assertThat(store.hasWorkflow(null, "wf-patch-del")).isFalse();
    }

    @Test
    @DisplayName("RCHFLW-173: PATCH no meio de um PUT com If-Match — os dois sobrevivem, ou o PUT leva 409")
    void patchNoMeioDoPutNaoSePerde() throws Exception {
        DocumentoComGancho doc = semeia("wf-patch-put");
        Future<ResponseEntity<Map<String, Object>>> patch = noMeio(doc,
                () -> controller.setStatus("wf-patch-put", Map.of("status", "active")));

        ResponseEntity<Map<String, Object>> salvar = controller.update("wf-patch-put", corpo("salvo"), "\"t0\"");

        assertThat(patch.get(5, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        Map<String, Object> fim = store.getWorkflow(null, "wf-patch-put");
        if (salvar.getStatusCode().value() == 200) {
            assertThat(fim.get("status")).as("o PATCH respondeu 200: o status dele não pode sumir").isEqualTo("active");
            assertThat(descricao(fim)).as("o PUT respondeu 200: a gravação dele não pode sumir").isEqualTo("salvo");
        } else {
            assertThat(salvar.getStatusCode().value()).isEqualTo(409);
        }
    }

    @Test
    @DisplayName("RCHFLW-173: PUT confirmado no meio de um PATCH — o PATCH não regrava o documento de antes")
    void putNoMeioDoPatchNaoSePerde() throws Exception {
        semeia("wf-put-patch");
        CorpoComGancho corpo = new CorpoComGancho("active");
        Future<?>[] salvar = new Future<?>[1];
        corpo.gancho = () -> salvar[0] = espera(outra.submit(
                () -> controller.update("wf-put-patch", corpo("salvo 7"), "\"t0\"")));

        ResponseEntity<Map<String, Object>> patch = controller.setStatus("wf-put-patch", corpo);

        @SuppressWarnings("unchecked")
        var put = (ResponseEntity<Map<String, Object>>) salvar[0].get(5, TimeUnit.SECONDS);
        assertThat(put.getStatusCode().value()).isEqualTo(200);
        assertThat(patch.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> fim = store.getWorkflow(null, "wf-put-patch");
        assertThat(descricao(fim)).as("o PUT respondeu 200 com If-Match: não pode ser desfeito").isEqualTo("salvo 7");
        assertThat(fim.get("status")).isEqualTo("active");
    }

    @Test
    @DisplayName("Publicar que se cruza com outro Publicar: PRODUCTION fica na versão mais nova, a que o último 201 "
            + "anunciou")
    void publicacoesCruzadasDeixamAMaisNovaEmProducao() throws Exception {
        // O gancho fica na implantação da primeira publicação, depois de a versão dela existir: é ali que, com a
        // versão e a implantação em duas operações do store, a outra publicação inteira cabia no meio.
        AtomicReference<Runnable> gancho = new AtomicReference<>();
        var cruzado = new InMemoryWorkflowRuntimeStore() {
            @Override
            public WorkflowDeploymentRecord deployWorkflow(String tenantId, String workflowId, String environment,
                                                           String versionId) {
                Runnable g = gancho.getAndSet(null);
                if (g != null) {
                    g.run();
                }
                return super.deployWorkflow(tenantId, workflowId, environment, versionId);
            }
        };
        var publicador = new SpringWorkflowCrudController(cruzado, mock(WorkflowDeserializer.class),
                mock(FlowEngine.class), mock(FlowRepository.class), null, null);
        Map<String, Object> doc = new LinkedHashMap<>(corpo("rascunho A"));
        doc.put("id", "wf-pub-pub");
        doc.put("updatedAt", "t0");
        cruzado.putWorkflow(null, "wf-pub-pub", doc);
        @SuppressWarnings("unchecked")
        Future<ResponseEntity<Map<String, Object>>>[] segunda = new Future[1];
        gancho.set(() -> segunda[0] = espera(outra.submit(() -> {
            publicador.update("wf-pub-pub", corpo("rascunho B"), null);
            return publicador.publish("wf-pub-pub", Map.of("comment", "B"));
        })));

        ResponseEntity<Map<String, Object>> primeira = publicador.publish("wf-pub-pub", Map.of("comment", "A"));

        assertThat(segunda[0]).as("o gancho não disparou").isNotNull();
        ResponseEntity<Map<String, Object>> depois = segunda[0].get(5, TimeUnit.SECONDS);
        assertThat(primeira.getStatusCode().value()).isEqualTo(201);
        assertThat(depois.getStatusCode().value()).isEqualTo(201);
        int maisNova = Math.max(numero(primeira), numero(depois));
        WorkflowDeploymentRecord producao = cruzado.getDeployment(null, "wf-pub-pub", "PRODUCTION");
        assertThat(cruzado.getWorkflowVersion(null, "wf-pub-pub", producao.versionId()).number())
                .as("as duas responderam 201 anunciando a própria versão em PRODUCTION; vale a mais nova")
                .isEqualTo(maisNova);
    }

    // ── helpers ──────────────────────────────────────────────────

    /** Fluxo gravado com {@code updatedAt = t0}, num documento que dispara o gancho quando a versão é lida. */
    private DocumentoComGancho semeia(String id) {
        DocumentoComGancho doc = new DocumentoComGancho();
        doc.put("id", id);
        doc.put("status", "draft");
        doc.put("updatedAt", "t0");
        doc.put("metadata", new LinkedHashMap<>(Map.of("name", id, "description", "original")));
        doc.put("steps", List.of());
        doc.put("configuration", Map.of());
        store.putWorkflow(null, id, doc);
        return doc;
    }

    /** Arma o gancho do documento: a {@code acao} roda noutra thread na primeira leitura da versão. */
    private <T> Future<T> noMeio(DocumentoComGancho doc, Callable<T> acao) {
        @SuppressWarnings("unchecked")
        Future<T>[] futuro = new Future[1];
        doc.gancho = () -> futuro[0] = espera(outra.submit(acao));
        return new Future<>() {
            @Override public boolean cancel(boolean b) { return futuro[0].cancel(b); }
            @Override public boolean isCancelled() { return futuro[0].isCancelled(); }
            @Override public boolean isDone() { return futuro[0].isDone(); }
            @Override public T get() throws java.util.concurrent.ExecutionException, InterruptedException {
                return futuro[0].get();
            }
            @Override public T get(long t, TimeUnit u)
                    throws java.util.concurrent.ExecutionException, InterruptedException, TimeoutException {
                assertThat(futuro[0]).as("o gancho não disparou: a requisição não leu a versão").isNotNull();
                return futuro[0].get(t, u);
            }
        };
    }

    /** Espera a outra requisição até {@link #ESPERA_MS}; se ela está presa numa trava desta thread, segue. */
    private static <F extends Future<?>> F espera(F futuro) {
        try {
            futuro.get(ESPERA_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException presaNaTrava) {
            // Esperado com a correção: a outra só roda depois que esta gravar.
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return futuro;
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

    /** Documento que roda o gancho (uma vez) quando alguém lê o {@code updatedAt} — a conferência do If-Match. */
    private static final class DocumentoComGancho extends LinkedHashMap<String, Object> {
        private volatile Runnable gancho;

        @Override
        public Object get(Object key) {
            Runnable g = gancho;
            if (g != null && "updatedAt".equals(key)) {
                gancho = null;
                g.run();
            }
            return super.get(key);
        }
    }

    /** Corpo do PATCH que roda o gancho (uma vez) quando o controller lê o status pedido. */
    private static final class CorpoComGancho extends LinkedHashMap<String, Object> {
        private volatile Runnable gancho;

        CorpoComGancho(String status) {
            put("status", status);
        }

        @Override
        public Object get(Object key) {
            Runnable g = gancho;
            if (g != null && "status".equals(key)) {
                gancho = null;
                g.run();
            }
            return super.get(key);
        }
    }
}
