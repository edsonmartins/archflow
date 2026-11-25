package br.com.archflow.api.web.workflow;

import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.mcp.server.WorkflowMcpServer;
import br.com.archflow.api.workflow.WorkflowYamlBridge;
import br.com.archflow.api.workflow.WorkflowYamlDto;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.langchain4j.mcp.JsonRpc;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.tenant.DefaultTenant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-73: fluxos e execuções são do tenant da requisição. Ao entrar no tenant B, os fluxos e as
 * execuções do tenant A não aparecem e não podem ser lidos, editados, publicados, executados nem
 * apagados — por REST, YAML, AG-UI ou MCP.
 *
 * <p>O tenant chega como chegaria em produção: pelo {@link ImpersonationFilter} real, que grava o
 * atributo lido pelo {@code TenantContext}. Com auth desligada, o {@code X-Tenant-Id}; com JWT de
 * superadmin, a personificação ({@code X-Impersonate-Tenant}) — o único caminho para agir sobre
 * outro tenant.
 */
@DisplayName("Fluxos e execuções separados por tenant (RCHFLW-73)")
class FluxosPorTenantTest {

    private InMemoryWorkflowRuntimeStore store;
    private FlowEngine engine;
    private SpringWorkflowCrudController crud;
    private SpringExecutionController execucoes;
    private SpringWorkflowYamlController yaml;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowRuntimeStore();
        engine = mock(FlowEngine.class);
        // Nunca termina: a execução fica RUNNING, o que deixa o cancel exercitável.
        when(engine.execute(any(), any())).thenReturn(new CompletableFuture<>());
        WorkflowDeserializer deserializer = json -> mock(Flow.class);
        crud = new SpringWorkflowCrudController(store, deserializer, engine, mock(FlowRepository.class),
                null, null);
        execucoes = new SpringExecutionController(store, engine, mock(StateManager.class), null);
        yaml = new SpringWorkflowYamlController(store, new WorkflowYamlBridge());
    }

    @AfterEach
    void limpa() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ── requisições ─────────────────────────────────────────────────────────

    /** Auth desligada (dev): o tenant é o do {@code X-Tenant-Id}; sem header, o padrão. */
    private <T> T comoTenant(String tenant, Supplier<T> acao) throws Exception {
        return naRequisicao(false, req -> {
            if (tenant != null) {
                req.addHeader(ImpersonationFilter.HEADER_TENANT, tenant);
            }
        }, acao);
    }

    /** Superadmin autenticado por JWT (sem claim de tenant) personificando {@code tenant}. */
    private <T> T superadminPersonificando(String tenant, Supplier<T> acao) throws Exception {
        return naRequisicao(true, req -> {
            req.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, "root");
            req.setAttribute(JwtAuthenticationFilter.ATTR_ROLES, List.of("superadmin"));
            req.addHeader(ImpersonationFilter.HEADER_IMPERSONATE, tenant);
        }, acao);
    }

    private <T> T naRequisicao(boolean impersonationEnabled, Consumer<MockHttpServletRequest> prepara,
                               Supplier<T> acao) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/workflows");
        prepara.accept(req);
        AtomicReference<T> resultado = new AtomicReference<>();
        new ImpersonationFilter(impersonationEnabled).doFilter(req, new MockHttpServletResponse(),
                (rq, rs) -> {
                    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
                    try {
                        resultado.set(acao.get());
                    } finally {
                        RequestContextHolder.resetRequestAttributes();
                    }
                });
        return resultado.get();
    }

    private String criaFluxo(String tenant, String nome) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metadata", Map.of("name", nome, "version", "1.0.0"));
        body.put("steps", List.of());
        return comoTenant(tenant, () -> String.valueOf(crud.create(body).getBody().get("id")));
    }

    private static List<Object> ids(List<Map<String, Object>> itens) {
        return itens.stream().map(item -> item.get("id")).toList();
    }

    // ── workflows ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a lista do tenant B não traz o fluxo do tenant A, e cada um vê o seu")
    void listaSoDoTenant() throws Exception {
        String doA = criaFluxo("tenant-a", "Fluxo do A");
        String doB = criaFluxo("tenant-b", "Fluxo do B");

        assertThat(ids(comoTenant("tenant-a", crud::list))).contains(doA).doesNotContain(doB);
        assertThat(ids(comoTenant("tenant-b", crud::list))).containsExactly(doB);
    }

    @Test
    @DisplayName("no tenant B, o fluxo do A é 404 em get, update, status, publish, versões, execute e delete")
    void fluxoDeOutroTenantEhInexistente() throws Exception {
        String doA = criaFluxo("tenant-a", "Fluxo do A");
        Map<String, Object> edicao = new LinkedHashMap<>(Map.of("metadata", Map.of("name", "Sequestrado")));

        assertThat(comoTenant("tenant-b", () -> crud.get(doA)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(comoTenant("tenant-b", () -> crud.update(doA, edicao)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(comoTenant("tenant-b", () -> crud.setStatus(doA, Map.of("status", "archived")))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(comoTenant("tenant-b", () -> crud.publish(doA, null)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(comoTenant("tenant-b", () -> crud.versions(doA)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(comoTenant("tenant-b", () -> crud.execute(doA, null)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(comoTenant("tenant-b", () -> crud.delete(doA)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(engine, never()).execute(any(), any());

        // Nada do que o B tentou chegou ao fluxo do A.
        Map<String, Object> intacto = comoTenant("tenant-a", () -> crud.get(doA)).getBody();
        assertThat(intacto).containsEntry("status", "draft");
        assertThat(intacto.get("metadata")).isEqualTo(Map.of("name", "Fluxo do A", "version", "1.0.0"));
    }

    @Test
    @DisplayName("versões e implantações de um fluxo do A não são alcançáveis pelo B nem pelo id da versão")
    void versoesEImplantacoesHerdamODono() throws Exception {
        String doA = criaFluxo("tenant-a", "Fluxo do A");
        @SuppressWarnings("unchecked")
        var publicado = (WorkflowVersionRecord) comoTenant("tenant-a", () -> crud.publish(doA, null))
                .getBody().get("version");

        assertThat(comoTenant("tenant-b", () -> crud.version(doA, publicado.id())).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(comoTenant("tenant-b", () -> crud.deployment(doA, "PRODUCTION")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(comoTenant("tenant-b", () -> crud.deploy(doA, "PRODUCTION",
                Map.of("versionId", publicado.id()))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(comoTenant("tenant-a", () -> crud.version(doA, publicado.id())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("o YAML de um fluxo do A é 404 no tenant B, para ler e para gravar")
    void yamlDeOutroTenant() throws Exception {
        String doA = criaFluxo("tenant-a", "Fluxo do A");
        String yamlDoA = comoTenant("tenant-a", () -> yaml.getYaml(doA)).yaml();

        assertThatThrownBy(() -> comoTenant("tenant-b", () -> yaml.getYaml(doA)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> comoTenant("tenant-b",
                () -> yaml.updateYaml(doA, new WorkflowYamlDto(doA, yamlDoA, null))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── execuções ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a execução do A grava o tenant e não aparece, não abre e não é cancelada no tenant B")
    void execucaoDeOutroTenant() throws Exception {
        String doA = criaFluxo("tenant-a", "Fluxo do A");
        String execucao = comoTenant("tenant-a",
                () -> String.valueOf(crud.execute(doA, null).getBody().get("executionId")));

        assertThat(comoTenant("tenant-a", () -> execucoes.get(execucao)).getBody())
                .containsEntry("tenantId", "tenant-a");
        assertThat(ids(comoTenant("tenant-a", () -> execucoes.list(null, null)))).contains(execucao);

        assertThat(ids(comoTenant("tenant-b", () -> execucoes.list(null, null)))).doesNotContain(execucao);
        assertThat(comoTenant("tenant-b", () -> execucoes.list(doA, null))).isEmpty();
        assertThat(comoTenant("tenant-b", () -> execucoes.get(execucao)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(comoTenant("tenant-b", () -> execucoes.cancel(execucao)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(comoTenant("tenant-b", () -> execucoes.resume(execucao)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(engine, never()).cancel(execucao);
        assertThat(comoTenant("tenant-a", () -> execucoes.get(execucao)).getBody())
                .containsEntry("status", "RUNNING");
    }

    @Test
    @DisplayName("apagar o fluxo no tenant B não apaga o do A nem as execuções dele")
    void deleteDeOutroTenantNaoApaga() throws Exception {
        String doA = criaFluxo("tenant-a", "Fluxo do A");
        String execucao = comoTenant("tenant-a",
                () -> String.valueOf(crud.execute(doA, null).getBody().get("executionId")));

        comoTenant("tenant-b", () -> crud.delete(doA));

        assertThat(comoTenant("tenant-a", () -> crud.get(doA)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(comoTenant("tenant-a", () -> execucoes.get(execucao)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ── tenant padrão e personificação ──────────────────────────────────────

    @Test
    @DisplayName("sem tenant (dev) continua vendo o fluxo e a execução de demonstração; outro tenant não")
    void tenantPadraoMantemOsDadosDeDev() throws Exception {
        assertThat(ids(comoTenant(null, crud::list))).contains("wf-demo-001");
        assertThat(ids(comoTenant(DefaultTenant.ID, crud::list))).contains("wf-demo-001");
        assertThat(ids(comoTenant(null, () -> execucoes.list(null, null)))).contains("exec-demo-001");

        assertThat(ids(comoTenant("tenant-b", crud::list))).doesNotContain("wf-demo-001");
        assertThat(ids(comoTenant("tenant-b", () -> execucoes.list(null, null))))
                .doesNotContain("exec-demo-001");
    }

    @Test
    @DisplayName("o superadmin que personifica o A age sobre o fluxo do A — e só enquanto personifica")
    void superadminPersonificandoAgeNoTenant() throws Exception {
        String doA = criaFluxo("tenant-a", "Fluxo do A");

        assertThat(ids(superadminPersonificando("tenant-a", crud::list))).containsExactly(doA);
        assertThat(superadminPersonificando("tenant-a", () -> crud.get(doA)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // Criado personificando o A = do A, não do tenant do próprio superadmin.
        String criado = superadminPersonificando("tenant-a", () -> String.valueOf(crud.create(
                new LinkedHashMap<>(Map.of("metadata", Map.of("name", "Do suporte")))).getBody().get("id")));
        assertThat(ids(comoTenant("tenant-a", crud::list))).contains(criado);

        // Personificando o B, o fluxo do A volta a não existir.
        assertThat(superadminPersonificando("tenant-b", () -> crud.get(doA)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── MCP ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MCP: tools/list só anuncia os fluxos do tenant, e tools/call de um fluxo do A falha no B")
    void mcpSoEnxergaOTenantDaChamada() throws Exception {
        String doA = criaFluxo("tenant-a", "Fluxo do A");
        FlowResult ok = mock(FlowResult.class);
        when(ok.getStatus()).thenReturn(ExecutionStatus.COMPLETED);
        when(ok.getOutput()).thenReturn(Optional.empty());
        when(engine.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(ok));
        WorkflowMcpServer mcp = new WorkflowMcpServer(store, json -> mock(Flow.class), engine,
                mock(FlowRepository.class), new ObjectMapper());

        JsonRpc.Response listaDoB = mcp.handleRequest(
                new JsonRpc.Request("2.0", 1, "tools/list", Map.of()), "tenant-b");
        JsonRpc.Response listaDoA = mcp.handleRequest(
                new JsonRpc.Request("2.0", 2, "tools/list", Map.of()), "tenant-a");
        assertThat(String.valueOf(listaDoB.result())).doesNotContain("workflow_" + doA);
        assertThat(String.valueOf(listaDoA.result())).contains("workflow_" + doA);

        JsonRpc.Response chamadaDoB = mcp.handleRequest(new JsonRpc.Request("2.0", 3, "tools/call",
                Map.of("name", "workflow_" + doA, "arguments", Map.of())), "tenant-b");
        assertThat(String.valueOf(chamadaDoB.result())).contains("Unknown workflow");
        verify(engine, never()).execute(any(), any());
        assertThat(store.executions("tenant-b", null, null)).isEmpty();
        assertThat(store.executions("tenant-a", doA, null)).isEmpty();

        mcp.handleRequest(new JsonRpc.Request("2.0", 4, "tools/call",
                Map.of("name", "workflow_" + doA, "arguments", Map.of())), "tenant-a");
        assertThat(store.executions("tenant-a", doA, null)).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("tenantId", "tenant-a"));
    }
}
