package br.com.archflow.api.web.admin;

import br.com.archflow.agent.metrics.AggregatedMetrics;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.admin.observability.impl.InMemoryTraceStore;
import br.com.archflow.api.admin.observability.impl.ObservabilityControllerImpl;
import br.com.archflow.api.admin.observability.impl.ObservabilityService;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.config.TenantAccessDeniedHandler;
import br.com.archflow.api.config.TenantScope;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.AuditEvent;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import br.com.archflow.security.jwt.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RCHFLW-39: traces, auditoria (lista e export), execuções em andamento e o cancel usam o tenant da
 * identidade.
 *
 * <p>Antes o {@code tenantId} era opcional em todos os endpoints e a ausência valia "todos os
 * tenants": qualquer usuário autenticado lia os traces e a auditoria de todos e cancelava a execução
 * de outro tenant — o serviço trata o tenant nulo do cancel como "sem checagem de dono". A auditoria
 * nem filtrava pelo tenant que recebia. Filtros reais (JWT → personificação) na frente do controller,
 * tokens assinados.</p>
 */
@DisplayName("Observabilidade: tenant da identidade (RCHFLW-39)")
class ObservabilidadeDoTenantDaIdentidadeTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";

    private final JwtService jwtService = new JwtService(SECRET);
    private final ObjectMapper json = new ObjectMapper();
    private RunningFlowsRegistry running;
    private InMemoryTraceStore traces;
    private InMemoryAuditRepository audit;
    private MetricsCollector metricas;
    private MockMvc mvc;

    @BeforeEach
    void monta() {
        traces = new InMemoryTraceStore(100);
        audit = new InMemoryAuditRepository();
        running = new RunningFlowsRegistry();
        Instant agora = Instant.now();
        traces.record(InMemoryTraceStore.trace("trace-a", "tenant-a", null, "flow", "exec-a",
                agora.minusSeconds(30), 100, "OK", null, Map.of(), List.of()));
        traces.record(InMemoryTraceStore.trace("trace-b", "tenant-b", null, "flow", "exec-b",
                agora.minusSeconds(20), 200, "OK", null, Map.of(), List.of()));
        audit.save(evento("evt-a", "tenant-a"));
        audit.save(evento("evt-b", "tenant-b"));
        running.flowStarted("run-a", "tenant-a", 3);
        running.flowStarted("run-b", "tenant-b", 3);
        // O coletor agrega a plataforma: a tool do tenant B aparece pelo nome no snapshot.
        metricas = mock(MetricsCollector.class);
        when(metricas.getAggregatedMetrics()).thenReturn(new AggregatedMetrics(agora,
                Map.of("tool_calls_total", 2L, "tool_calls_reiniciar_servico", 1L), Map.of(), Map.of()));
        mvc = montar(true);
    }

    private MockMvc montar(boolean authLigada) {
        ObservabilityService service = new ObservabilityService(metricas, traces, audit, null, running);
        var controller = new SpringObservabilityController(new ObservabilityControllerImpl(service),
                new TenantScope(authLigada));
        var builder = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TenantAccessDeniedHandler());
        if (authLigada) {
            builder.addFilters(new JwtAuthenticationFilter(jwtService, null, true, ""),
                    new ImpersonationFilter(false));
        }
        return builder.build();
    }

    /** O tenant vai no contexto do evento, sob a chave que o {@link AuditTrail} grava. */
    private static AuditEvent evento(String userId, String tenant) {
        return AuditEvent.builder()
                .timestamp(Instant.now().minusSeconds(5))
                .action(AuditAction.WORKFLOW_EXECUTE)
                .userId(userId)
                .username(userId)
                .success(true)
                .addContext(AuditTrail.CONTEXT_TENANT, tenant)
                .build();
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

    private JsonNode corpo(ResultActions resposta) throws Exception {
        return json.readTree(resposta.andReturn().getResponse().getContentAsString());
    }

    /** Os valores de {@code campo} de uma lista (ou de {@code items}, numa página). */
    private List<String> valores(ResultActions resposta, String campo) throws Exception {
        JsonNode raiz = corpo(resposta);
        JsonNode itens = raiz.has("items") ? raiz.get("items") : raiz;
        List<String> out = new ArrayList<>();
        itens.forEach(item -> out.add(item.get(campo).asText()));
        return out;
    }

    private String csv(ResultActions resposta) throws Exception {
        return resposta.andReturn().getResponse().getContentAsString();
    }

    @Nested
    @DisplayName("traces")
    class Traces {

        @Test
        @DisplayName("lista: sem tenantId só os do token; 'all' 403 sem admin; com ADMIN, todos")
        void lista() throws Exception {
            assertThat(valores(como(token("tenant-a", "VIEWER"), get("/api/admin/observability/traces"))
                    .andExpect(status().isOk()), "traceId")).containsExactly("trace-a");

            como(token("tenant-a", "VIEWER"), get("/api/admin/observability/traces").param("tenantId", "all"))
                    .andExpect(status().isForbidden());
            como(token("tenant-a", "EDITOR"),
                    get("/api/admin/observability/traces").param("tenantId", "tenant-b"))
                    .andExpect(status().isForbidden());

            assertThat(valores(como(token("tenant-a", "ADMIN"),
                    get("/api/admin/observability/traces").param("tenantId", "all"))
                    .andExpect(status().isOk()), "traceId")).containsExactlyInAnyOrder("trace-a", "trace-b");
        }

        @Test
        @DisplayName("detalhe de outro tenant → 404; o admin com 'all' abre")
        void detalhe() throws Exception {
            como(token("tenant-a", "VIEWER"), get("/api/admin/observability/traces/trace-a"))
                    .andExpect(status().isOk());
            como(token("tenant-a", "VIEWER"), get("/api/admin/observability/traces/trace-b"))
                    .andExpect(status().isNotFound());
            como(token("tenant-a", "VIEWER"),
                    get("/api/admin/observability/traces/trace-b").param("tenantId", "all"))
                    .andExpect(status().isForbidden());

            como(token("tenant-a", "SUPERADMIN"),
                    get("/api/admin/observability/traces/trace-b").param("tenantId", "all"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("overview conta só os do próprio tenant (traces e auditoria)")
        void overview() throws Exception {
            JsonNode overview = corpo(como(token("tenant-a", "VIEWER"), get("/api/admin/observability/overview"))
                    .andExpect(status().isOk()));
            assertThat(overview.get("totalExecutionsToday").asLong()).isEqualTo(1);
            assertThat(overview.get("totalAuditEventsToday").asLong()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("auditoria")
    class Auditoria {

        @Test
        @DisplayName("lista: só os eventos do tenant do token; 'all' 403 sem admin; com ADMIN, todos")
        void lista() throws Exception {
            assertThat(valores(como(token("tenant-a", "VIEWER"), get("/api/admin/observability/audit"))
                    .andExpect(status().isOk()), "userId")).containsExactly("evt-a");

            como(token("tenant-a", "VIEWER"), get("/api/admin/observability/audit").param("tenantId", "all"))
                    .andExpect(status().isForbidden());

            assertThat(valores(como(token("tenant-a", "ADMIN"),
                    get("/api/admin/observability/audit").param("tenantId", "all"))
                    .andExpect(status().isOk()), "userId")).containsExactlyInAnyOrder("evt-a", "evt-b");
        }

        @Test
        @DisplayName("export: só as linhas do tenant do token; 'all' 403 sem admin; com ADMIN, todas")
        void export() throws Exception {
            String doA = csv(como(token("tenant-a", "VIEWER"), get("/api/admin/observability/audit/export"))
                    .andExpect(status().isOk()));
            assertThat(doA).contains("evt-a").doesNotContain("evt-b");

            como(token("tenant-a", "VIEWER"),
                    get("/api/admin/observability/audit/export").param("tenantId", "all"))
                    .andExpect(status().isForbidden());

            String todos = csv(como(token("tenant-a", "ADMIN"),
                    get("/api/admin/observability/audit/export").param("tenantId", "all"))
                    .andExpect(status().isOk()));
            assertThat(todos).contains("evt-a").contains("evt-b");
        }
    }

    @Nested
    @DisplayName("execuções em andamento")
    class EmAndamento {

        @Test
        @DisplayName("lista: só as do tenant do token; 'all' 403 sem admin; com ADMIN, todas")
        void lista() throws Exception {
            assertThat(valores(como(token("tenant-a", "VIEWER"), get("/api/admin/observability/running"))
                    .andExpect(status().isOk()), "flowId")).containsExactly("run-a");

            como(token("tenant-a", "VIEWER"), get("/api/admin/observability/running").param("tenantId", "all"))
                    .andExpect(status().isForbidden());

            assertThat(valores(como(token("tenant-a", "ADMIN"),
                    get("/api/admin/observability/running").param("tenantId", "all"))
                    .andExpect(status().isOk()), "flowId")).containsExactlyInAnyOrder("run-a", "run-b");
        }

        @Test
        @DisplayName("cancel sem tenantId da execução de outro tenant → 404 e ela segue no registro")
        void cancelDeOutroTenant() throws Exception {
            String editorA = token("tenant-a", "EDITOR");

            String foraDoTenant = como(editorA, post("/api/admin/observability/running/run-b/cancel"))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            String naoRodando = como(editorA, post("/api/admin/observability/running/nao-existe/cancel"))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            como(editorA, post("/api/admin/observability/running/run-b/cancel").param("tenantId", "all"))
                    .andExpect(status().isForbidden());

            assertThat(running.find("run-b")).isPresent();
            // Mesma resposta para "de outro tenant" e "não está rodando": não revela ids alheios.
            assertThat(foraDoTenant.replace("run-b", "X")).isEqualTo(naoRodando.replace("nao-existe", "X"));
        }

        @Test
        @DisplayName("cancel da própria execução → 200; o admin com 'all' cancela a de outro tenant")
        void cancelPermitido() throws Exception {
            como(token("tenant-a", "EDITOR"), post("/api/admin/observability/running/run-a/cancel"))
                    .andExpect(status().isOk());
            como(token("tenant-a", "ADMIN"),
                    post("/api/admin/observability/running/run-b/cancel").param("tenantId", "all"))
                    .andExpect(status().isOk());

            assertThat(running.find("run-a")).isEmpty();
            assertThat(running.find("run-b")).isEmpty();
        }
    }

    @Nested
    @DisplayName("snapshot de métricas (agregado da plataforma)")
    class Metricas {

        @Test
        @DisplayName("quem não é admin → 403, mesmo sem tenantId; nada das tools dos outros sai")
        void naoAdminRecusado() throws Exception {
            String resposta = como(token("tenant-a", "VIEWER"), get("/api/admin/observability/metrics"))
                    .andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();
            assertThat(resposta).doesNotContain("reiniciar_servico").contains("ADMIN");

            como(token("tenant-a", "EDITOR"), get("/api/admin/observability/metrics").param("tenantId", "tenant-a"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ADMIN/SUPERADMIN → 200 com o agregado, com ou sem 'all'")
        void adminVe() throws Exception {
            JsonNode snap = corpo(como(token("tenant-a", "ADMIN"),
                    get("/api/admin/observability/metrics").param("tenantId", "all"))
                    .andExpect(status().isOk()));
            assertThat(snap.get("counters").get("tool_calls_reiniciar_servico").asLong()).isEqualTo(1);

            como(token("tenant-a", "SUPERADMIN"), get("/api/admin/observability/metrics"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("auth desligada (dev): sem tenantId continua 'todos', como antes")
    void authDesligada() throws Exception {
        MockMvc semAuth = montar(false);

        // Detalhe e snapshot: o tenantId não entrava na busca e continua não entrando.
        semAuth.perform(get("/api/admin/observability/traces/trace-b").param("tenantId", "tenant-a"))
                .andExpect(status().isOk());
        semAuth.perform(get("/api/admin/observability/metrics")).andExpect(status().isOk());

        assertThat(valores(semAuth.perform(get("/api/admin/observability/traces")).andExpect(status().isOk()),
                "traceId")).containsExactlyInAnyOrder("trace-a", "trace-b");
        assertThat(valores(semAuth.perform(get("/api/admin/observability/running")).andExpect(status().isOk()),
                "flowId")).containsExactlyInAnyOrder("run-a", "run-b");
    }
}
