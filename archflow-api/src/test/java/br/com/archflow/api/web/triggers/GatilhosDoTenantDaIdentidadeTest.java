package br.com.archflow.api.web.triggers;

import br.com.archflow.api.agent.AgentController;
import br.com.archflow.api.agent.dto.InvokeRequest;
import br.com.archflow.api.agent.dto.InvokeResponse;
import br.com.archflow.api.agent.invocation.AgentInvocationLookup;
import br.com.archflow.api.agent.invocation.AgentInvocationResult;
import br.com.archflow.api.agent.invocation.InMemoryAgentInvocationResultStore;
import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.config.TenantAccessDeniedHandler;
import br.com.archflow.api.config.TenantScope;
import br.com.archflow.api.events.EventController;
import br.com.archflow.api.events.dto.MessageRequest;
import br.com.archflow.api.events.dto.MessageResponse;
import br.com.archflow.api.triggers.ScheduledTriggerController;
import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import br.com.archflow.api.web.agent.SpringAgentController;
import br.com.archflow.api.web.agent.SpringAgentInvocationController;
import br.com.archflow.api.web.events.SpringEventController;
import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RCHFLW-103: gatilhos agendados e o Agent lab rodam no tenant da identidade, nunca no que o
 * formulário manda.
 *
 * <p>Antes, um VIEWER do tenant A criava um gatilho com {@code tenantId} do B e o agente passava a ser
 * invocado no B a cada disparo; o Invocar/Enviar do Agent lab fazia o mesmo sob demanda. Os filtros
 * reais (JWT → personificação) rodam na frente dos controllers, com tokens assinados de verdade: é
 * deles que sai o tenant efetivo. A prova de que nada rodou no outro tenant é o que chega ao
 * delegate.</p>
 */
@DisplayName("Gatilhos e Agent lab: tenant da identidade (RCHFLW-103)")
class GatilhosDoTenantDaIdentidadeTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";

    private final JwtService jwtService = new JwtService(SECRET);
    private GatilhosEmMemoria gatilhos;
    private final AtomicReference<InvokeRequest> invocado = new AtomicReference<>();
    private final AtomicReference<MessageRequest> mensagem = new AtomicReference<>();
    private InMemoryAgentInvocationResultStore resultados;
    private MockMvc mvc;

    @BeforeEach
    void monta() {
        gatilhos = new GatilhosEmMemoria();
        gatilhos.create(gatilho("trg-a", "tenant-a"));
        gatilhos.create(gatilho("trg-b", "tenant-b"));
        resultados = new InMemoryAgentInvocationResultStore();
        mvc = montar(true);
    }

    private MockMvc montar(boolean authLigada) {
        TenantScope escopo = new TenantScope(authLigada);
        AgentController agentes = (agentId, request) -> {
            invocado.set(request);
            return new InvokeResponse("req-1", request.tenantId(), agentId, "ACCEPTED", Instant.now());
        };
        EventController eventos = request -> {
            mensagem.set(request);
            return new MessageResponse("req-2", request.tenantId(), request.sessionId(), request.agentId(),
                    "ACCEPTED", null, Instant.now());
        };
        var builder = MockMvcBuilders.standaloneSetup(
                        new SpringScheduledTriggerController(gatilhos, escopo),
                        new SpringAgentController(agentes, escopo),
                        new SpringEventController(eventos, escopo),
                        new SpringAgentInvocationController(new AgentInvocationLookup(resultados,
                                new InMemoryAgentInvocationQueue(), null), escopo))
                .setControllerAdvice(new TenantAccessDeniedHandler());
        if (authLigada) {
            builder.addFilters(new JwtAuthenticationFilter(jwtService, null, true, ""),
                    new ImpersonationFilter(false));
        } else {
            builder.addFilters(new ImpersonationFilter(false));
        }
        return builder.build();
    }

    private static ScheduledTriggerDto gatilho(String id, String tenant) {
        return new ScheduledTriggerDto(id, "Resumo " + id, "0 0 2 * * ?", tenant, "agente-x", Map.of(), false,
                null, null, null, null);
    }

    private static String corpoDoGatilho(String tenant) {
        return "{\"name\":\"Diário\",\"cronExpression\":\"0 0 2 * * ?\",\"tenantId\":\"" + tenant
                + "\",\"agentId\":\"agente-x\",\"payload\":{},\"enabled\":false}";
    }

    private String token(String tenantId, String... roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "access");
        claims.put("roles", List.of(roles));
        claims.put("tenantId", tenantId);
        return jwtService.generateToken("user-1", "alice", 60_000, claims);
    }

    private ResultActions como(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(token == null ? req : req.header("Authorization", "Bearer " + token));
    }

    private static JsonNode json(ResultActions resposta) throws Exception {
        return new ObjectMapper().readTree(resposta.andReturn().getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder criar(String tenant) {
        return post("/api/admin/triggers").contentType(MediaType.APPLICATION_JSON).content(corpoDoGatilho(tenant));
    }

    private List<String> tenantsGravados() {
        return gatilhos.list().stream().map(ScheduledTriggerDto::tenantId).toList();
    }

    @Nested
    @DisplayName("criar e editar um gatilho")
    class Criar {

        @Test
        @DisplayName("viewer com o próprio tenant: criado no tenant do token")
        void proprioTenant() throws Exception {
            como(token("tenant-a", "VIEWER"), criar("tenant-a")).andExpect(status().isCreated());

            assertThat(tenantsGravados()).containsExactly("tenant-a", "tenant-b", "tenant-a");
        }

        @Test
        @DisplayName("tenant vazio no formulário: vale o do token")
        void tenantVazio() throws Exception {
            var resposta = como(token("tenant-a", "VIEWER"), criar("")).andExpect(status().isCreated());

            assertThat(json(resposta).get("tenantId").asText()).isEqualTo("tenant-a");
        }

        @Test
        @DisplayName("viewer com outro tenant: 403 e nada é gravado")
        void outroTenantSemAdmin() throws Exception {
            como(token("tenant-a", "VIEWER"), criar("tenant-b")).andExpect(status().isForbidden());

            assertThat(gatilhos.list()).hasSize(2);
        }

        @Test
        @DisplayName("admin com outro tenant: criado nele")
        void outroTenantComAdmin() throws Exception {
            var resposta = como(token("tenant-a", "ADMIN"), criar("tenant-b")).andExpect(status().isCreated());

            assertThat(json(resposta).get("tenantId").asText()).isEqualTo("tenant-b");
        }

        @Test
        @DisplayName("'all' não é um tenant: 400, mesmo para o admin")
        void todosNaoETenant() throws Exception {
            como(token("tenant-a", "ADMIN"), criar("all")).andExpect(status().isBadRequest());

            assertThat(gatilhos.list()).hasSize(2);
        }

        @Test
        @DisplayName("viewer movendo o próprio gatilho para outro tenant: 403, o gatilho fica onde estava")
        void editarParaOutroTenant() throws Exception {
            como(token("tenant-a", "VIEWER"), put("/api/admin/triggers/trg-a")
                    .contentType(MediaType.APPLICATION_JSON).content(corpoDoGatilho("tenant-b")))
                    .andExpect(status().isForbidden());

            assertThat(gatilhos.get("trg-a").tenantId()).isEqualTo("tenant-a");
        }

        @Test
        @DisplayName("id escolhido que já é de outro tenant: 409, e o gatilho dele fica intacto")
        void idDeOutroTenant() throws Exception {
            como(token("tenant-a", "VIEWER"), post("/api/admin/triggers").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"id\":\"trg-b\",\"name\":\"x\",\"cronExpression\":\"0 0 2 * * ?\","
                            + "\"tenantId\":\"tenant-a\",\"agentId\":\"a\",\"enabled\":false}"))
                    .andExpect(status().isConflict());

            assertThat(gatilhos.get("trg-b").tenantId()).isEqualTo("tenant-b");
            assertThat(gatilhos.get("trg-b").name()).isEqualTo("Resumo trg-b");
        }

        @Test
        @DisplayName("id escolhido que já é do próprio tenant: 409 também — sobrescrever é o PUT")
        void idDoProprioTenant() throws Exception {
            como(token("tenant-a", "VIEWER"), post("/api/admin/triggers").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"id\":\"trg-a\",\"name\":\"x\",\"cronExpression\":\"0 0 2 * * ?\","
                            + "\"tenantId\":\"tenant-a\",\"agentId\":\"a\",\"enabled\":false}"))
                    .andExpect(status().isConflict());

            assertThat(gatilhos.get("trg-a").name()).isEqualTo("Resumo trg-a");
        }

        @Test
        @DisplayName("admin edita o gatilho de outro tenant com tenantId em branco: ele fica no tenant dele")
        void edicaoSemTenantMantemOTenant() throws Exception {
            como(token("tenant-a", "ADMIN"), put("/api/admin/triggers/trg-b").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Resumo trg-b\",\"cronExpression\":\"0 0 3 * * ?\","
                            + "\"tenantId\":\"\",\"agentId\":\"agente-x\",\"enabled\":false}"))
                    .andExpect(status().isOk());

            assertThat(gatilhos.get("trg-b").tenantId()).isEqualTo("tenant-b");
            assertThat(gatilhos.get("trg-b").cronExpression()).isEqualTo("0 0 3 * * ?");
        }
    }

    @Nested
    @DisplayName("gatilhos que já existem")
    class Existentes {

        @Test
        @DisplayName("viewer lista só os do próprio tenant")
        void listaDoProprio() throws Exception {
            var resposta = como(token("tenant-a", "VIEWER"), get("/api/admin/triggers")).andExpect(status().isOk());

            List<String> ids = new ArrayList<>();
            json(resposta).forEach(t -> ids.add(t.get("id").asText()));
            assertThat(ids).containsExactly("trg-a");
        }

        @Test
        @DisplayName("viewer pedindo 'all': 403")
        void listaTodosSemAdmin() throws Exception {
            como(token("tenant-a", "VIEWER"), get("/api/admin/triggers").param("tenantId", "all"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("admin com 'all' vê os de todos os tenants")
        void listaTodosComAdmin() throws Exception {
            var resposta = como(token("tenant-a", "ADMIN"), get("/api/admin/triggers").param("tenantId", "all"))
                    .andExpect(status().isOk());

            assertThat(json(resposta)).hasSize(2);
        }

        @Test
        @DisplayName("o gatilho de outro tenant não existe para o viewer: ler, editar, disparar e apagar dão 404")
        void outroTenantE404() throws Exception {
            String viewerA = token("tenant-a", "VIEWER");
            como(viewerA, get("/api/admin/triggers/trg-b")).andExpect(status().isNotFound());
            como(viewerA, put("/api/admin/triggers/trg-b").contentType(MediaType.APPLICATION_JSON)
                    .content(corpoDoGatilho("tenant-a"))).andExpect(status().isNotFound());
            como(viewerA, post("/api/admin/triggers/trg-b/fire")).andExpect(status().isNotFound());
            como(viewerA, delete("/api/admin/triggers/trg-b")).andExpect(status().isNotFound());

            assertThat(gatilhos.disparados).isEmpty();
            assertThat(gatilhos.get("trg-b").tenantId()).isEqualTo("tenant-b");
        }

        @Test
        @DisplayName("admin dispara o gatilho de outro tenant")
        void adminDispara() throws Exception {
            como(token("tenant-a", "ADMIN"), post("/api/admin/triggers/trg-b/fire")).andExpect(status().isOk());

            assertThat(gatilhos.disparados).containsExactly("trg-b");
        }
    }

    @Nested
    @DisplayName("Agent lab")
    class AgentLab {

        private MockHttpServletRequestBuilder invocar(String tenant) {
            return post("/archflow/agents/agente-x/invoke").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tenantId\":\"" + tenant + "\",\"payload\":{}}");
        }

        private MockHttpServletRequestBuilder enviar(String tenant) {
            return post("/archflow/events/message").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tenantId\":\"" + tenant + "\",\"agentId\":\"agente-x\",\"message\":\"oi\"}");
        }

        @Test
        @DisplayName("invocar e enviar no próprio tenant: o agente roda no tenant do token")
        void proprioTenant() throws Exception {
            String viewerA = token("tenant-a", "VIEWER");
            como(viewerA, invocar("tenant-a")).andExpect(status().isOk());
            como(viewerA, enviar("tenant-a")).andExpect(status().isOk());

            assertThat(invocado.get().tenantId()).isEqualTo("tenant-a");
            assertThat(mensagem.get().tenantId()).isEqualTo("tenant-a");
        }

        @Test
        @DisplayName("invocar e enviar em outro tenant sem admin: 403 e o agente não é acionado")
        void outroTenantSemAdmin() throws Exception {
            String viewerA = token("tenant-a", "VIEWER");
            como(viewerA, invocar("tenant-b")).andExpect(status().isForbidden());
            como(viewerA, enviar("tenant-b")).andExpect(status().isForbidden());

            assertThat(invocado.get()).isNull();
            assertThat(mensagem.get()).isNull();
        }

        @Test
        @DisplayName("admin invoca em outro tenant")
        void outroTenantComAdmin() throws Exception {
            como(token("tenant-a", "ADMIN"), invocar("tenant-b")).andExpect(status().isOk());

            assertThat(invocado.get().tenantId()).isEqualTo("tenant-b");
        }

        @Test
        @DisplayName("o resultado de uma invocação de outro tenant: 403 para o viewer")
        void resultadoDeOutroTenant() throws Exception {
            InvocationRequest r = InvocationRequest.root("tenant-b", "agente-x", Map.of());
            resultados.save(AgentInvocationResult.running(r, 1, Instant.now()).completed("ok", Instant.now()));

            como(token("tenant-a", "VIEWER"), get("/archflow/agents/invocations/" + r.requestId())
                    .param("tenantId", "tenant-b")).andExpect(status().isForbidden());
            como(token("tenant-b", "VIEWER"), get("/archflow/agents/invocations/" + r.requestId())
                    .param("tenantId", "tenant-b")).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("auth desligada (dev): o tenant do formulário vale como antes")
    void authDesligada() throws Exception {
        mvc = montar(false);

        como(null, criar("tenant-b")).andExpect(status().isCreated());
        como(null, post("/archflow/agents/agente-x/invoke").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"tenant-b\",\"payload\":{}}")).andExpect(status().isOk());
        var lista = como(null, get("/api/admin/triggers")).andExpect(status().isOk());

        assertThat(json(lista)).hasSize(3);
        assertThat(invocado.get().tenantId()).isEqualTo("tenant-b");
    }

    /** O registro de gatilhos sem Quartz: o que interessa aqui é o tenant que chega a ele. */
    private static final class GatilhosEmMemoria implements ScheduledTriggerController {

        private final Map<String, ScheduledTriggerDto> store = new LinkedHashMap<>();
        private final List<String> disparados = new ArrayList<>();
        private int seq;

        @Override
        public List<ScheduledTriggerDto> list() {
            return new ArrayList<>(store.values());
        }

        @Override
        public ScheduledTriggerDto get(String id) {
            ScheduledTriggerDto dto = store.get(id);
            if (dto == null) {
                throw new IllegalArgumentException("Trigger not found: " + id);
            }
            return dto;
        }

        @Override
        public ScheduledTriggerDto create(ScheduledTriggerDto dto) {
            String id = dto.id() != null ? dto.id() : "trg-" + (++seq);
            ScheduledTriggerDto stored = new ScheduledTriggerDto(id, dto.name(), dto.cronExpression(),
                    dto.tenantId(), dto.agentId(), dto.payload(), dto.enabled(), null, null, null, null);
            store.put(id, stored);
            return stored;
        }

        @Override
        public ScheduledTriggerDto update(String id, ScheduledTriggerDto dto) {
            get(id);
            ScheduledTriggerDto stored = new ScheduledTriggerDto(id, dto.name(), dto.cronExpression(),
                    dto.tenantId(), dto.agentId(), dto.payload(), dto.enabled(), null, null, null, null);
            store.put(id, stored);
            return stored;
        }

        @Override
        public void delete(String id) {
            get(id);
            store.remove(id);
        }

        @Override
        public ScheduledTriggerDto fireNow(String id) {
            disparados.add(id);
            return get(id);
        }
    }
}
