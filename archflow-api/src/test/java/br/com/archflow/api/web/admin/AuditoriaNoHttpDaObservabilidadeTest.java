package br.com.archflow.api.web.admin;

import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.admin.observability.impl.InMemoryTraceStore;
import br.com.archflow.api.admin.observability.impl.ObservabilityControllerImpl;
import br.com.archflow.api.admin.observability.impl.ObservabilityService;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.config.TenantScope;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.AuditEvent;
import br.com.archflow.observability.audit.AuditRepository;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O HTTP da auditoria da observabilidade com os parâmetros que a UI manda
 * ({@code observability-api.ts#buildQuery}).
 *
 * <ul>
 *   <li>RCHFLW-180: o export só lia tenant e período — ação e autor ficavam de fora —, saía como
 *       {@code text/plain}; a listagem lia {@code size} e a UI manda {@code pageSize}.</li>
 *   <li>RCHFLW-195 item 10: a listagem não lia {@code actor}.</li>
 *   <li>RCHFLW-195 item 1: o "Parar" do Running não entrava na auditoria.</li>
 * </ul>
 */
@DisplayName("Observabilidade HTTP — filtros da auditoria e cancelamento auditado")
class AuditoriaNoHttpDaObservabilidadeTest {

    private final ObjectMapper json = new ObjectMapper();
    private InMemoryAuditRepository audit;
    private RunningFlowsRegistry running;
    private MockMvc mvc;

    @BeforeEach
    void monta() {
        audit = new InMemoryAuditRepository();
        for (int i = 0; i < 60; i++) {
            audit.save(evento(AuditAction.LOGIN_SUCCESS, "admin", i + 10));
        }
        for (int i = 0; i < 5; i++) {
            audit.save(evento(AuditAction.CREATE, "maria", i + 100));
        }
        running = new RunningFlowsRegistry();
        ObservabilityService service = new ObservabilityService(null, new InMemoryTraceStore(), audit, null,
                running, mock(FlowEngine.class));
        var controller = new SpringObservabilityController(new ObservabilityControllerImpl(service),
                new TenantScope(false));
        controller.setAuditTrail(new AuditTrail(() -> audit));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static AuditEvent evento(AuditAction action, String username, int segundosAtras) {
        return AuditEvent.builder()
                .timestamp(Instant.now().minusSeconds(segundosAtras))
                .action(action)
                .userId("id-" + username)
                .username(username)
                .success(true)
                .addContext(AuditTrail.CONTEXT_TENANT, "acme")
                .build();
    }

    private static List<String> linhas(String csv) {
        return Arrays.stream(csv.split("\n")).skip(1).toList();
    }

    @Test
    @DisplayName("export: a ação e o autor da tela chegam ao arquivo, que sai como text/csv com nome")
    void exportComOsFiltrosDaTela() throws Exception {
        MockHttpServletResponse r = mvc.perform(get("/api/admin/observability/audit/export")
                        .param("action", "CREATE").param("page", "0").param("pageSize", "50"))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(r.getContentType()).startsWith("text/csv");
        assertThat(r.getHeader("Content-Disposition")).contains("audit-log.csv");
        String csv = r.getContentAsString(StandardCharsets.UTF_8);
        assertThat(linhas(csv)).hasSize(5).allSatisfy(l -> assertThat(l).contains(",CREATE,"));

        String doAutor = mvc.perform(get("/api/admin/observability/audit/export").param("actor", "maria"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(linhas(doAutor)).hasSize(5);
    }

    @Test
    @DisplayName("listagem: lê o actor e o pageSize que a UI manda")
    void listagemComAutorEPageSize() throws Exception {
        JsonNode pagina = json.readTree(mvc.perform(get("/api/admin/observability/audit")
                        .param("pageSize", "50").param("page", "0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(pagina.get("items").size()).isEqualTo(50);
        assertThat(pagina.get("total").asLong()).isEqualTo(65);

        JsonNode daMaria = json.readTree(mvc.perform(get("/api/admin/observability/audit").param("actor", "maria"))
                .andReturn().getResponse().getContentAsString());
        assertThat(daMaria.get("total").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("parar uma execução no Running grava WORKFLOW_CANCELLED no tenant dono dela")
    void cancelamentoAuditado() throws Exception {
        running.flowStarted("exec-9", "tenant-b", 3);

        mvc.perform(post("/api/admin/observability/running/exec-9/cancel")).andExpect(status().isNoContent());

        assertThat(audit.query(AuditRepository.AuditQuery.builder().action(AuditAction.WORKFLOW_CANCELLED)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getResourceId()).isEqualTo("exec-9");
                    assertThat(e.getContext()).containsEntry(AuditTrail.CONTEXT_TENANT, "tenant-b");
                });
    }
}
