package br.com.archflow.api.web.approval;

import br.com.archflow.api.agent.mcp.RetomadaDoLaco;
import br.com.archflow.api.approval.impl.ApprovalControllerImpl;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.config.TenantAccessDeniedHandler;
import br.com.archflow.api.config.TenantScope;
import br.com.archflow.api.flow.InMemoryStateManager;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.security.jwt.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RCHFLW-39: a fila de aprovações usa o tenant da identidade, nunca o que o cliente manda.
 *
 * <p>Antes, sem {@code tenantId} ou com {@code "all"}, um viewer do tenant A listava as pendências
 * do B (com a proposta), abria o detalhe e aprovava — ou editava os argumentos da tool — de um pedido
 * do B só por saber o id. Os filtros reais (JWT → personificação) rodam na frente do controller, com
 * tokens assinados de verdade: é deles que sai o tenant efetivo. A prova de que nada foi decidido é
 * o motor (e o laço) nunca serem chamados.</p>
 */
@DisplayName("Aprovações: tenant da identidade (RCHFLW-39)")
class AprovacoesDoTenantDaIdentidadeTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";
    /** O corpo exige um tenantId; quem ataca manda o próprio, para passar pela conferência. */
    private static final String DECISAO = "{\"decision\":\"APPROVED\",\"tenantId\":\"tenant-a\"}";

    private final JwtService jwtService = new JwtService(SECRET);
    private final FlowEngine flowEngine = mock(FlowEngine.class);
    private final RetomadaDoLaco retomada = mock(RetomadaDoLaco.class);
    private InMemoryStateManager stateManager;
    private MockMvc mvc;

    @BeforeEach
    void monta() {
        stateManager = new InMemoryStateManager();
        aguardando("flow-a", "tenant-a", "req-a");
        aguardando("flow-b", "tenant-b", "req-b");
        when(flowEngine.submitApproval(anyString(), anyString(), anyBoolean(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        // O gate do laço de agente: uma tool do tenant B esperando decisão.
        when(retomada.detalhe(anyString())).thenReturn(Optional.empty());
        when(retomada.detalhe("req-laco-b")).thenReturn(Optional.of(new RetomadaDoLaco.Pendencia(
                "req-laco-b", "tenant-b", "run-b", "reiniciar_servico", Map.of("servico", "traefik"),
                Instant.parse("2026-11-24T10:00:00Z"))));
        when(retomada.pendentes(anyString())).thenReturn(List.of());
        mvc = montar(true);
    }

    private MockMvc montar(boolean authLigada) {
        ApprovalQueueService fila = new ApprovalQueueService(stateManager, flowEngine, Duration.ZERO,
                Clock.systemUTC(), retomada);
        var controller = new SpringApprovalController(new ApprovalControllerImpl(fila),
                new TenantScope(authLigada));
        var builder = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TenantAccessDeniedHandler());
        if (authLigada) {
            builder.addFilters(new JwtAuthenticationFilter(jwtService, null, true, ""),
                    new ImpersonationFilter(false));
        } else {
            builder.addFilters(new ImpersonationFilter(false));
        }
        return builder.build();
    }

    private void aguardando(String flowId, String tenant, String requestId) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(ExecutionKeys.APPROVAL_REQUEST_ID, requestId);
        vars.put(ExecutionKeys.APPROVAL_PROPOSAL, Map.of("valor", 10));
        vars.put(ExecutionKeys.APPROVAL_REQUESTED_AT, "2026-11-24T11:00:00Z");
        stateManager.saveState(flowId, FlowState.builder()
                .tenantId(tenant).flowId(flowId).status(FlowStatus.AWAITING_APPROVAL)
                .currentStepId("gate").variables(vars).build());
    }

    private String token(String tenantId, String... roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "access");
        claims.put("roles", List.of(roles));
        if (tenantId != null) {
            claims.put("tenantId", tenantId);
        }
        return jwtService.generateToken("user-1", "alice", 60_000, claims);
    }

    private ResultActions como(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private static JsonNode json(ResultActions resposta) throws Exception {
        return new ObjectMapper().readTree(resposta.andReturn().getResponse().getContentAsString());
    }

    /** Os requestIds de uma listagem. */
    private static List<String> ids(ResultActions resposta) throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        json(resposta).forEach(item -> ids.add(item.get("requestId").asText()));
        return ids;
    }

    private static MockHttpServletRequestBuilder decidir(String requestId, String corpo) {
        return post("/api/approvals/" + requestId).contentType(MediaType.APPLICATION_JSON).content(corpo);
    }

    @Nested
    @DisplayName("listar e contar")
    class Listar {

        @Test
        @DisplayName("sem tenantId: só as do tenant do token")
        void proprioTenant() throws Exception {
            String viewerA = token("tenant-a", "VIEWER");

            assertThat(ids(como(viewerA, get("/api/approvals/pending")).andExpect(status().isOk())))
                    .containsExactly("req-a");
            assertThat(json(como(viewerA, get("/api/approvals/pending/count")).andExpect(status().isOk()))
                    .get("count").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("'all' sem papel admin → 403; com ADMIN → as de todos")
        void todosSoParaAdmin() throws Exception {
            assertThat(json(como(token("tenant-a", "VIEWER"),
                    get("/api/approvals/pending").param("tenantId", "all"))
                    .andExpect(status().isForbidden())).get("message").asText()).contains("ADMIN");
            como(token("tenant-a", "EDITOR"), get("/api/approvals/pending/count").param("tenantId", "all"))
                    .andExpect(status().isForbidden());

            assertThat(ids(como(token("tenant-a", "ADMIN"),
                    get("/api/approvals/pending").param("tenantId", "all")).andExpect(status().isOk())))
                    .containsExactlyInAnyOrder("req-a", "req-b");
            assertThat(json(como(token("tenant-a", "ROLE_SUPERADMIN"),
                    get("/api/approvals/pending/count").param("tenantId", "all"))
                    .andExpect(status().isOk())).get("count").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("tenantId de outro tenant sem papel admin → 403 (não vira o próprio em silêncio)")
        void outroTenantRecusado() throws Exception {
            como(token("tenant-a", "VIEWER"), get("/api/approvals/pending").param("tenantId", "tenant-b"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("token sem claim é do tenant padrão: não vê as de tenant-a nem de tenant-b")
        void tokenSemClaim() throws Exception {
            assertThat(json(como(token(null, "VIEWER"), get("/api/approvals/pending/count"))
                    .andExpect(status().isOk())).get("count").asInt()).isZero();
        }
    }

    @Nested
    @DisplayName("detalhe")
    class Detalhe {

        @Test
        @DisplayName("do próprio tenant → 200; de outro → 404, como um id desconhecido")
        void outroTenantEhNaoEncontrado() throws Exception {
            String viewerA = token("tenant-a", "VIEWER");

            assertThat(json(como(viewerA, get("/api/approvals/req-a")).andExpect(status().isOk()))
                    .get("tenantId").asText()).isEqualTo("tenant-a");
            como(viewerA, get("/api/approvals/req-b")).andExpect(status().isNotFound());
            como(viewerA, get("/api/approvals/req-laco-b")).andExpect(status().isNotFound());
            como(viewerA, get("/api/approvals/nao-existe")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("'all' sem admin → 403; o admin com 'all' abre o de outro tenant")
        void todosSoParaAdmin() throws Exception {
            como(token("tenant-a", "VIEWER"), get("/api/approvals/req-b").param("tenantId", "all"))
                    .andExpect(status().isForbidden());

            assertThat(json(como(token("tenant-a", "ADMIN"),
                    get("/api/approvals/req-b").param("tenantId", "all")).andExpect(status().isOk()))
                    .get("tenantId").asText()).isEqualTo("tenant-b");
            // Sem pedir "all", nem o admin vê fora do próprio tenant: a ausência é o tenant dele.
            como(token("tenant-a", "ADMIN"), get("/api/approvals/req-b")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("decidir")
    class Decidir {

        @Test
        @DisplayName("pedido de outro tenant → 404 e nada é decidido (nem no motor, nem no laço)")
        void outroTenantNaoDecide() throws Exception {
            String editorA = token("tenant-a", "EDITOR");

            como(editorA, decidir("req-b", DECISAO)).andExpect(status().isNotFound());
            como(editorA, decidir("req-laco-b",
                    "{\"decision\":\"EDITED\",\"tenantId\":\"tenant-a\","
                    + "\"editedPayload\":{\"servico\":\"banco\"}}"))
                    .andExpect(status().isNotFound());

            verify(flowEngine, never()).submitApproval(anyString(), anyString(), anyBoolean(), any(), any(),
                    any());
            verify(retomada, never()).decidir(anyString(), anyBoolean(), any());
        }

        @Test
        @DisplayName("corpo nomeando outro tenant (ou 'all') sem admin → 403 e nada é decidido")
        void corpoComOutroTenant() throws Exception {
            String editorA = token("tenant-a", "EDITOR");

            como(editorA, decidir("req-b", "{\"decision\":\"APPROVED\",\"tenantId\":\"tenant-b\"}"))
                    .andExpect(status().isForbidden());
            como(editorA, decidir("req-b", "{\"decision\":\"APPROVED\",\"tenantId\":\"all\"}"))
                    .andExpect(status().isForbidden());

            verify(flowEngine, never()).submitApproval(anyString(), anyString(), anyBoolean(), any(), any(),
                    any());
        }

        @Test
        @DisplayName("pedido do próprio tenant → 200 e o motor decide")
        void proprioTenantDecide() throws Exception {
            como(token("tenant-a", "EDITOR"), decidir("req-a",
                    "{\"decision\":\"APPROVED\",\"tenantId\":\"tenant-a\"}"))
                    .andExpect(status().isOk());

            verify(flowEngine).submitApproval(eq("flow-a"), eq("req-a"), eq(true), any(), eq("alice"), any());
        }

        @Test
        @DisplayName("admin decide o pedido de outro tenant que abriu na visão de todos")
        void adminDecideOutroTenant() throws Exception {
            como(token("tenant-a", "ADMIN"), decidir("req-b",
                    "{\"decision\":\"REJECTED\",\"tenantId\":\"tenant-b\"}"))
                    .andExpect(status().isOk());

            verify(flowEngine).submitApproval(eq("flow-b"), eq("req-b"), eq(false), any(), eq("alice"), any());
        }
    }

    @Nested
    @DisplayName("auth desligada (dev): comportamento de antes")
    class AuthDesligada {

        @Test
        @DisplayName("sem parâmetro, a fila do X-Tenant-Id; 'all' continua listando todos")
        void dev() throws Exception {
            MockMvc semAuth = montar(false);

            assertThat(ids(semAuth.perform(get("/api/approvals/pending")
                    .header(ImpersonationFilter.HEADER_TENANT, "tenant-b")).andExpect(status().isOk())))
                    .containsExactly("req-b");
            assertThat(ids(semAuth.perform(get("/api/approvals/pending").param("tenantId", "all"))
                    .andExpect(status().isOk())))
                    .containsExactlyInAnyOrder("req-a", "req-b");
            semAuth.perform(get("/api/approvals/req-b")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("detalhe e decisão: o tenantId da query/corpo não entra na busca, como antes")
        void tenantDoClienteIgnoradoNaBusca() throws Exception {
            MockMvc semAuth = montar(false);

            semAuth.perform(get("/api/approvals/req-b").param("tenantId", "tenant-a"))
                    .andExpect(status().isOk());
            // DECISAO nomeia tenant-a; o pedido é de tenant-b. Antes do RCHFLW-39 o corpo era ignorado.
            semAuth.perform(decidir("req-b", DECISAO)).andExpect(status().isOk());

            verify(flowEngine).submitApproval(eq("flow-b"), eq("req-b"), eq(true), any(), any(), any());
        }
    }
}
