package br.com.archflow.api.config;

import br.com.archflow.api.admin.dto.TenantDto.CreateTenantRequest;
import br.com.archflow.api.admin.impl.TenantControllerImpl;
import br.com.archflow.api.agent.vendax.VendaxAgentDispatcher;
import br.com.archflow.api.agent.vendax.VendaxInvokeController;
import br.com.archflow.api.web.admin.SpringTenantController;
import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.security.apikey.ApiKeyService;
import br.com.archflow.security.auth.InMemoryUserRepository;
import br.com.archflow.security.jwt.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * QA2 P6: suspender um tenant ({@code POST /api/admin/tenants/{id}/suspend}, o botão "Suspender" da
 * tela do tenant) só trocava o status do cadastro. Agora o uso autenticado da API pelos usuários e
 * chaves de API desse tenant é recusado com {@code 403}, até ele ser reativado.
 *
 * <p>A requisição passa pelo filtro JWT real, pelo {@link ImpersonationFilter} e pelo interceptor
 * exatamente como {@link TenantSuspensionConfiguration} o registra; o cadastro é o
 * {@link TenantControllerImpl} real, alterado pelos endpoints reais de suspender e reativar.
 */
@DisplayName("Suspensão de tenant (QA2 P6)")
class SuspensaoDeTenantTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";
    private static final String CHAVE_DO_CORE = "chave-de-servico-do-core";
    private static final String SUSPENSO = "tenant_qa2_b";
    private static final String OUTRO = "tenant_qa2_c";

    private final JwtService jwtService = new JwtService(SECRET);
    private final ObjectMapper json = new ObjectMapper();
    private InMemoryUserRepository users;
    private ApiKeyService apiKeys;
    private TenantControllerImpl cadastro;
    private VendaxAgentDispatcher vendax;
    private Sonda sonda;

    /** Conta as requisições que chegaram ao handler — o que a suspensão tem de impedir. */
    @RestController
    static class Sonda {
        final AtomicInteger chegadas = new AtomicInteger();

        @PostMapping("/api/workflows")
        Map<String, String> cria() {
            chegadas.incrementAndGet();
            return Map.of("id", "wf-1");
        }

        @GetMapping("/api/workflows")
        List<String> lista() {
            chegadas.incrementAndGet();
            return List.of();
        }

        @PostMapping("/api/workflows/{id}/execute")
        Map<String, String> executa(@PathVariable String id) {
            chegadas.incrementAndGet();
            return Map.of("workflowId", id);
        }

        @PostMapping("/api/auth/logout")
        Map<String, String> sai() {
            chegadas.incrementAndGet();
            return Map.of("status", "ok");
        }
    }

    @BeforeEach
    void monta() {
        users = new InMemoryUserRepository();
        apiKeys = new ApiKeyService(new InMemoryApiKeyRepository(), users);
        cadastro = new TenantControllerImpl(false);
        cadastro.createTenant(new CreateTenantRequest("QA2 B", SUSPENSO, "admin@b.local", null, "starter", null,
                null, null));
        cadastro.createTenant(new CreateTenantRequest("QA2 C", OUTRO, "admin@c.local", null, "starter", null,
                null, null));
        vendax = mock(VendaxAgentDispatcher.class);
        sonda = new Sonda();
    }

    private MockMvc mvc(boolean authEnabled) {
        var invoke = new VendaxInvokeController(vendax);
        ReflectionTestUtils.setField(invoke, "invokeKey", CHAVE_DO_CORE);
        var beans = new StaticListableBeanFactory(Map.of("tenantControllerImpl", cadastro));
        RegistroExposto registro = new RegistroExposto();
        new TenantSuspensionConfiguration(authEnabled, beans.getBeanProvider(TenantControllerImpl.class))
                .addInterceptors(registro);
        return MockMvcBuilders.standaloneSetup(new SpringTenantController(cadastro), invoke, sonda)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new JwtAuthenticationFilter(jwtService, null, apiKeys, authEnabled, ""),
                        new ImpersonationFilter(false))
                .addInterceptors(registro.interceptors().stream()
                        .map(HandlerInterceptor.class::cast)
                        .toArray(HandlerInterceptor[]::new))
                .build();
    }

    private String jwt(String tenant, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "access");
        claims.put("roles", List.of(role));
        claims.put("tenantId", tenant);
        return jwtService.generateToken("u-" + tenant + "-" + role.toLowerCase(), role.toLowerCase() + "@qa2.local",
                60_000, claims);
    }

    private static MvcResult como(MockMvc mvc, String token, AbstractMockHttpServletRequestBuilder<?> req)
            throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token)).andReturn();
    }

    private void assertSuspenso(MvcResult result) throws Exception {
        String rota = result.getRequest().getMethod() + " " + result.getRequest().getRequestURI();
        assertThat(result.getResponse().getStatus()).as(rota).isEqualTo(403);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("error").asText()).as(rota).isEqualTo("FORBIDDEN");
        assertThat(body.path("status").asInt()).as(rota).isEqualTo(403);
        assertThat(body.path("message").asText()).as(rota).contains(SUSPENSO).contains("suspended");
    }

    @Test
    @DisplayName("usuário do tenant suspenso: criar, listar e executar dão 403 e nada chega ao handler")
    void usuarioDoTenantSuspenso() throws Exception {
        MockMvc mvc = mvc(true);
        String designer = jwt(SUSPENSO, "DESIGNER");
        assertThat(como(mvc, designer, post("/api/workflows")).getResponse().getStatus()).isEqualTo(200);
        assertThat(sonda.chegadas.get()).isEqualTo(1);

        como(mvc, jwt(DefaultTenant.ID, "SUPERADMIN"), post("/api/admin/tenants/" + SUSPENSO + "/suspend"));

        assertSuspenso(como(mvc, designer, post("/api/workflows")));
        assertSuspenso(como(mvc, designer, get("/api/workflows")));
        assertSuspenso(como(mvc, designer, post("/api/workflows/wf-1/execute")));
        assertThat(sonda.chegadas.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("o ADMIN do próprio tenant suspenso não se reativa; o superadmin de outro tenant reativa")
    void reativacao() throws Exception {
        MockMvc mvc = mvc(true);
        String superadmin = jwt(DefaultTenant.ID, "SUPERADMIN");
        como(mvc, superadmin, post("/api/admin/tenants/" + SUSPENSO + "/suspend"));

        assertSuspenso(como(mvc, jwt(SUSPENSO, "ADMIN"), post("/api/admin/tenants/" + SUSPENSO + "/activate")));
        assertThat(cadastro.isSuspended(SUSPENSO)).isTrue();

        assertThat(como(mvc, superadmin, post("/api/admin/tenants/" + SUSPENSO + "/activate"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(cadastro.isSuspended(SUSPENSO)).isFalse();
        // Reativado, vale na hora: o cadastro é lido a cada requisição.
        assertThat(como(mvc, jwt(SUSPENSO, "DESIGNER"), post("/api/workflows")).getResponse().getStatus())
                .isEqualTo(200);
    }

    /**
     * O cadastro aceita criar um {@code __default__}; suspendê-lo trancaria todo token sem claim de
     * tenant, inclusive o do admin da plataforma. Recusado pelos dois caminhos que gravam o status.
     */
    @Test
    @DisplayName("o tenant padrão não se suspende (nem por /suspend nem por PUT status=suspended)")
    void tenantPadraoNaoSeSuspende() throws Exception {
        cadastro.createTenant(new CreateTenantRequest("Plataforma", DefaultTenant.ID, "admin@p.local", null,
                "enterprise", null, null, null));
        MockMvc mvc = mvc(true);
        String admin = jwt(OUTRO, "ADMIN");

        MvcResult suspende = como(mvc, admin, post("/api/admin/tenants/" + DefaultTenant.ID + "/suspend"));
        assertThat(suspende.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(suspende.getResponse().getContentAsString()).path("error").asText())
                .isEqualTo("The default tenant (" + DefaultTenant.ID + ") cannot be suspended");
        assertThat(como(mvc, admin, put("/api/admin/tenants/" + DefaultTenant.ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"suspended\"}"))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(cadastro.isSuspended(DefaultTenant.ID)).isFalse();

        // Token sem claim (o admin da plataforma) segue entrando.
        String plataforma = jwtService.generateToken("u-plataforma", "plataforma@p.local", 60_000,
                Map.of("type", "access", "roles", List.of("ADMIN")));
        assertThat(como(mvc, plataforma, post("/api/workflows")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("outros tenants não são afetados pela suspensão de um")
    void outrosTenants() throws Exception {
        MockMvc mvc = mvc(true);
        cadastro.suspendTenant(SUSPENSO);

        assertThat(como(mvc, jwt(OUTRO, "DESIGNER"), post("/api/workflows")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(como(mvc, jwt(DefaultTenant.ID, "DESIGNER"), post("/api/workflows")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(sonda.chegadas.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("chave de API do tenant suspenso também é recusada")
    void chaveDeApi() throws Exception {
        User dono = new User();
        dono.setId("dono");
        dono.setUsername("dono");
        dono.setEmail("dono@b.local");
        dono.setEnabled(true);
        dono.addRole(Role.createExecutorRole());
        users.save(dono);
        String credencial = apiKeys.createApiKey(SUSPENSO, "dono", "ci", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null)
                .credential();
        MockMvc mvc = mvc(true);
        assertThat(como(mvc, credencial, post("/api/workflows/wf-1/execute")).getResponse().getStatus())
                .isEqualTo(200);

        cadastro.suspendTenant(SUSPENSO);

        assertSuspenso(como(mvc, credencial, post("/api/workflows/wf-1/execute")));
        assertThat(sonda.chegadas.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("o ciclo da sessão (/api/auth/**) continua: o usuário suspenso ainda consegue sair")
    void sessao() throws Exception {
        MockMvc mvc = mvc(true);
        cadastro.suspendTenant(SUSPENSO);

        assertThat(como(mvc, jwt(SUSPENSO, "DESIGNER"), post("/api/auth/logout")).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("VendaX Core: o invoke com a chave de serviço não passa pela suspensão (chamada de máquina)")
    void vendaxCore() throws Exception {
        MockMvc mvc = mvc(true);
        cadastro.suspendTenant(SUSPENSO);

        MvcResult result = mvc.perform(post("/api/agents/invoke")
                .header("Authorization", "Bearer " + CHAVE_DO_CORE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + SUSPENSO + "\",\"agent\":\"qp\",\"conversationId\":\"c-1\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        verify(vendax).dispatch(any());
    }

    @Test
    @DisplayName("auth desligada (dev): nada muda, mesmo com o X-Tenant-Id do tenant suspenso")
    void authDesligada() throws Exception {
        MockMvc mvc = mvc(false);
        cadastro.suspendTenant(SUSPENSO);

        MvcResult result = mvc.perform(post("/api/workflows").header(ImpersonationFilter.HEADER_TENANT, SUSPENSO))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(sonda.chegadas.get()).isEqualTo(1);
    }

    /** Expõe os interceptors registrados ({@code getInterceptors} é protegido). */
    private static final class RegistroExposto extends InterceptorRegistry {
        List<Object> interceptors() {
            return getInterceptors();
        }
    }
}
