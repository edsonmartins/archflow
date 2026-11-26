package br.com.archflow.api.config;

import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.security.apikey.ApiKeyService;
import br.com.archflow.security.auth.AuthService;
import br.com.archflow.security.auth.InMemoryTokenBlacklist;
import br.com.archflow.security.auth.InMemoryUserRepository;
import br.com.archflow.security.jwt.JwtService;
import br.com.archflow.security.password.PasswordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA2 T2 na requisição: um ADMIN rebaixado ou removido seguia ADMIN em toda a API até o access token
 * expirar (15 min), porque o filtro autorizava pela claim {@code roles} do token, enquanto o
 * {@code /me} do mesmo token já dizia outra coisa. Agora o filtro relê o usuário.
 *
 * <p>Roda o {@link JwtAuthenticationFilter} real com o {@link AuthService} real, o
 * {@link ImpersonationFilter} e o {@link AdminRoleInterceptor} na frente de uma sonda. Também fixa o
 * que não pode mudar: as credenciais de máquina (a chave de serviço do VendaX Core no
 * {@code /api/agents/invoke} e as chaves de API) e o comportamento com a autenticação desligada.
 */
@DisplayName("Sessão na requisição: papel atual, usuário removido e clientes de máquina")
class SessaoNaRequisicaoTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";

    private final JwtService jwtService = new JwtService(SECRET);
    private final PasswordService passwords = new PasswordService(4);
    private final ObjectMapper json = new ObjectMapper();
    private InMemoryUserRepository users;
    private AuthService auth;
    private ApiKeyService apiKeys;

    /** Responde com a identidade que os filtros deram à requisição. */
    @RestController
    static class Sonda {

        private static Map<String, Object> identidade(HttpServletRequest http) {
            Map<String, Object> body = new HashMap<>();
            body.put("userId", String.valueOf(http.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID)));
            body.put("roles", String.valueOf(http.getAttribute(JwtAuthenticationFilter.ATTR_ROLES)));
            body.put("tenantId", TenantContext.currentTenantId());
            return body;
        }

        @GetMapping("/api/admin/tenants")
        Map<String, Object> tenants(HttpServletRequest http) {
            return identidade(http);
        }

        @GetMapping("/api/workflows")
        Map<String, Object> workflows(HttpServletRequest http) {
            return identidade(http);
        }

        @PostMapping("/api/workflows/{id}/execute")
        Map<String, Object> executa(@PathVariable String id, HttpServletRequest http) {
            return identidade(http);
        }

        @PostMapping("/api/agents/invoke")
        Map<String, Object> invoke(HttpServletRequest http) {
            return identidade(http);
        }
    }

    @BeforeEach
    void monta() {
        users = new InMemoryUserRepository();
        usuario("u-ana", "ana", "t-a", Role.createAdminRole());
        auth = new AuthService(jwtService, passwords, users, new InMemoryTokenBlacklist());
        apiKeys = new ApiKeyService(new InMemoryApiKeyRepository(), users);
    }

    private User usuario(String id, String username, String tenant, Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwords.hash("segredo123"));
        user.setTenantId(tenant);
        user.setEnabled(true);
        user.setAccountNonLocked(true);
        user.setCreatedAt(LocalDateTime.now());
        user.addRole(role);
        return users.save(user);
    }

    private MockMvc mvc(boolean authEnabled, boolean impersonation) {
        return MockMvcBuilders.standaloneSetup(new Sonda())
                .addMappedInterceptors(new String[] {"/api/admin/**"}, new AdminRoleInterceptor(authEnabled))
                .addFilters(new JwtAuthenticationFilter(jwtService, auth, apiKeys, authEnabled, ""),
                        new ImpersonationFilter(impersonation))
                .build();
    }

    private ResultMatcher campo(String pointer, Object esperado) {
        return r -> assertThat(json.readTree(r.getResponse().getContentAsString()).at(pointer).asText())
                .as(pointer).isEqualTo(String.valueOf(esperado));
    }

    private void rebaixaAna() {
        User ana = users.findById("u-ana").orElseThrow();
        ana.getRoles().clear();
        ana.addRole(Role.createViewerRole());
        users.save(ana);
    }

    @Test
    @DisplayName("rebaixado a VIEWER: o token que já tinha deixa de abrir a administração (403)")
    void rebaixadoPerdeAdmin() throws Exception {
        String token = auth.authenticate("ana", "segredo123").accessToken();
        MockMvc mvc = mvc(true, false);
        mvc.perform(get("/api/admin/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        rebaixaAna();

        mvc.perform(get("/api/admin/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        // O token segue valendo para o que o papel novo concede, com o papel novo.
        mvc.perform(get("/api/workflows").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(campo("/roles", "[VIEWER]"));
    }

    @Test
    @DisplayName("rebaixado: também não personifica outro tenant pelo X-Impersonate-Tenant")
    void rebaixadoNaoPersonifica() throws Exception {
        String token = auth.authenticate("ana", "segredo123").accessToken();
        rebaixaAna();

        mvc(true, true).perform(get("/api/workflows").header("Authorization", "Bearer " + token)
                        .header(ImpersonationFilter.HEADER_IMPERSONATE, "t-b"))
                // RCHFLW-193: a personificação recusada é 403, não mais 200 com o próprio tenant.
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("removido ou desativado: o token que já tinha é recusado (401) — o filtro concorda com o /me")
    void removidoPerdeAcesso() throws Exception {
        String token = auth.authenticate("ana", "segredo123").accessToken();
        MockMvc mvc = mvc(true, false);

        User ana = users.findById("u-ana").orElseThrow();
        ana.setEnabled(false);
        users.save(ana);
        mvc.perform(get("/api/workflows").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        users.delete(ana);
        mvc.perform(get("/api/admin/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("depois do logout, nem o access nem o refresh token abrem a API")
    void logoutEncerraNaApi() throws Exception {
        AuthService.AuthenticationResult sessao = auth.authenticate("ana", "segredo123");
        auth.logout(sessao.accessToken());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> auth.refreshToken(sessao.refreshToken())))
                .isInstanceOf(AuthService.AuthenticationException.class);
        mvc(true, false).perform(get("/api/workflows").header("Authorization", "Bearer " + sessao.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("store de revogação fora do ar: 503 AUTH_UNAVAILABLE, não 401 — o SPA não apaga a sessão")
    void storeForaDoArNaoDerrubaASessao() throws Exception {
        // O SPA trata 401 como sessão perdida (apaga os tokens e vai ao login): com 401 aqui, uma
        // oscilação do banco deslogaria todo usuário. A requisição continua não passando.
        String token = auth.authenticate("ana", "segredo123").accessToken();
        br.com.archflow.security.auth.TokenBlacklist foraDoAr = new br.com.archflow.security.auth.TokenBlacklist() {
            @Override
            public void revoke(String t, java.time.Instant expiresAt) {
                throw new IllegalStateException("Failed to revoke token");
            }

            @Override
            public boolean isRevoked(String t) {
                throw new IllegalStateException("Failed to check token revocation");
            }

            @Override
            public boolean revokeIfAbsent(String t, java.time.Instant expiresAt) {
                throw new IllegalStateException("Failed to consume token");
            }
        };
        AuthService semStore = new AuthService(jwtService, passwords, users, foraDoAr);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Sonda())
                .addFilters(new JwtAuthenticationFilter(jwtService, semStore, apiKeys, true, ""),
                        new ImpersonationFilter(false))
                .build();

        mvc.perform(get("/api/workflows").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(campo("/error", "AUTH_UNAVAILABLE"));
        // Um token inválido segue 401, com o store fora do ar ou não.
        mvc.perform(get("/api/workflows").header("Authorization", "Bearer nao.e.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("VendaX Core: a chave de serviço no /api/agents/invoke segue passando pelo filtro")
    void chaveDeServicoDoCoreSegue() throws Exception {
        mvc(true, false).perform(post("/api/agents/invoke").header("Authorization", "Bearer core-service-key"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("chave de API segue autenticando com o AuthService ligado ao filtro")
    void chaveDeApiSegue() throws Exception {
        String chave = apiKeys.createApiKey("t-a", "u-ana", "ci", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null)
                .credential();

        mvc(true, false).perform(post("/api/workflows/wf-1/execute").header("Authorization", "Bearer " + chave))
                .andExpect(status().isOk())
                .andExpect(campo("/userId", "u-ana"))
                .andExpect(campo("/tenantId", "t-a"));
    }

    @Test
    @DisplayName("autenticação desligada: como antes, a identidade vem do token, sem consultar o usuário")
    void autenticacaoDesligadaComoAntes() throws Exception {
        String token = auth.authenticate("ana", "segredo123").accessToken();
        users.delete(users.findById("u-ana").orElseThrow());

        mvc(false, false).perform(get("/api/workflows").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(campo("/userId", "u-ana"))
                .andExpect(campo("/roles", "[ADMIN]"));
    }
}
