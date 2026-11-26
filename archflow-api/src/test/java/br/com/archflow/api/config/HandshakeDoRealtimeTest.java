package br.com.archflow.api.config;

import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.security.auth.AuthService;
import br.com.archflow.security.auth.InMemoryTokenBlacklist;
import br.com.archflow.security.auth.InMemoryUserRepository;
import br.com.archflow.security.jwt.JwtService;
import br.com.archflow.security.password.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * QA2 T4: o handshake do WebSocket de voz ({@code /api/realtime/{tenant}/{persona}?token=}) conferia
 * só a assinatura e a validade do JWT. Um refresh token, um access token já revogado pelo logout e o
 * token de um usuário do tenant B abriam a sessão no tenant que o cliente escrevesse no caminho — e
 * é esse tenant que segue para o adapter. Agora vale a mesma regra do REST.
 *
 * <p>QA2 (RCHFLW-158): a sessão de voz põe o agente da persona para responder, mas o handshake não passa
 * pelos interceptors do MVC — o VIEWER, que leva 403 no {@code POST /ag-ui/agent}, conversava com o agente
 * por aqui, e o usuário de um tenant suspenso também. Agora exige {@code workflow:execute} e tenant ativo.
 */
@DisplayName("Handshake do realtime: tipo do token, revogação e tenant do caminho")
class HandshakeDoRealtimeTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";

    private final JwtService jwtService = new JwtService(SECRET);
    private final PasswordService passwords = new PasswordService(4);
    private InMemoryUserRepository users;
    private AuthService auth;

    @BeforeEach
    void monta() {
        users = new InMemoryUserRepository();
        usuario("u-bia", "bia", "tenant_b", Role.createExecutorRole());
        usuario("u-root", "root", "tenant_a", Role.createAdminRole());
        usuario("u-vera", "vera", "tenant_b", Role.createViewerRole());
        usuario("u-dani", "dani", "tenant_b", Role.createDesignerRole());
        auth = new AuthService(jwtService, passwords, users, new InMemoryTokenBlacklist());
    }

    private void usuario(String id, String username, String tenant, Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwords.hash("segredo123"));
        user.setTenantId(tenant);
        user.setEnabled(true);
        user.setAccountNonLocked(true);
        user.addRole(role);
        users.save(user);
    }

    /** Resultado de um handshake: o status da recusa (ou 200 quando aceito) e os atributos da sessão. */
    private record Handshake(boolean aceito, int status, Map<String, Object> atributos) {
    }

    /** Tenants suspensos no cadastro, lidos a cada handshake (como o cadastro real). */
    private final Set<String> suspensos = new HashSet<>();

    private Handshake handshake(boolean authEnabled, boolean impersonation, String tenant, String token)
            throws Exception {
        WebSocketConfiguration config = new WebSocketConfiguration(null, jwtService, auth,
                new String[] {"http://localhost:5173"}, authEnabled, impersonation, suspensos::contains);
        HandshakeInterceptor interceptor = config.pathVariablesInterceptor();
        MockHttpServletRequest http = new MockHttpServletRequest("GET", "/api/realtime/" + tenant + "/p1");
        if (token != null) {
            http.setQueryString("token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        Map<String, Object> atributos = new HashMap<>();
        boolean aceito = interceptor.beforeHandshake(new ServletServerHttpRequest(http),
                new ServletServerHttpResponse(resp), mock(org.springframework.web.socket.WebSocketHandler.class),
                atributos);
        return new Handshake(aceito, resp.getStatus(), atributos);
    }

    private AuthService.AuthenticationResult login(String username) {
        return auth.authenticate(username, "segredo123");
    }

    @Test
    @DisplayName("access token do próprio tenant: aceito, e o tenant da sessão é o do token")
    void proprioTenantAceito() throws Exception {
        Handshake h = handshake(true, false, "tenant_b", login("bia").accessToken());

        assertThat(h.aceito()).isTrue();
        assertThat(h.atributos()).containsEntry("tenantId", "tenant_b").containsEntry("personaId", "p1");
    }

    @Test
    @DisplayName("refresh token: recusado (401)")
    void refreshTokenRecusado() throws Exception {
        Handshake h = handshake(true, false, "tenant_b", login("bia").refreshToken());

        assertThat(h.aceito()).isFalse();
        assertThat(h.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("access token revogado pelo logout: recusado (401)")
    void revogadoRecusado() throws Exception {
        String token = login("bia").accessToken();
        auth.logout(token);

        Handshake h = handshake(true, false, "tenant_b", token);

        assertThat(h.aceito()).isFalse();
        assertThat(h.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("usuário removido: recusado (401)")
    void removidoRecusado() throws Exception {
        String token = login("bia").accessToken();
        users.delete(users.findById("u-bia").orElseThrow());

        assertThat(handshake(true, false, "tenant_b", token).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("tenant do caminho diferente do tenant do token: recusado (403)")
    void outroTenantRecusado() throws Exception {
        String token = login("bia").accessToken();

        Handshake padrao = handshake(true, false, "__default__", token);
        Handshake outro = handshake(true, false, "tenant_a", token);

        assertThat(padrao.aceito()).isFalse();
        assertThat(padrao.status()).isEqualTo(403);
        assertThat(outro.aceito()).isFalse();
        assertThat(outro.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("token sem a claim de tenant: é o tenant padrão, não um tenant livre")
    void semClaimEhOPadrao() throws Exception {
        String semTenant = jwtService.generateTenantAccessToken("u-bia", "bia", null, new String[] {"EXECUTOR"});

        assertThat(handshake(true, false, "__default__", semTenant).aceito()).isTrue();
        assertThat(handshake(true, false, "tenant_b", semTenant).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("outro tenant só para ADMIN com a personificação ligada — a mesma regra do REST")
    void personificacao() throws Exception {
        String admin = login("root").accessToken();
        String viewer = login("bia").accessToken();

        assertThat(handshake(true, false, "tenant_b", admin).status()).isEqualTo(403);
        Handshake personificado = handshake(true, true, "tenant_b", admin);
        assertThat(personificado.aceito()).isTrue();
        assertThat(personificado.atributos()).containsEntry("tenantId", "tenant_b");
        assertThat(handshake(true, true, "tenant_a", viewer).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("VIEWER no próprio tenant: recusado (403) — falta workflow:execute, como no /ag-ui/agent")
    void viewerSemWorkflowExecuteRecusado() throws Exception {
        Handshake h = handshake(true, false, "tenant_b", login("vera").accessToken());

        assertThat(h.aceito()).isFalse();
        assertThat(h.status()).isEqualTo(403);
        assertThat(h.atributos()).doesNotContainKey("tenantId");
    }

    @Test
    @DisplayName("os papéis conferidos são os atuais: rebaixado para VIEWER depois do login, recusado")
    void papelAtualConferido() throws Exception {
        String token = login("bia").accessToken();
        var bia = users.findById("u-bia").orElseThrow();
        bia.getRoles().clear();
        bia.addRole(Role.createViewerRole());
        users.save(bia);

        assertThat(handshake(true, false, "tenant_b", token).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("DESIGNER, EXECUTOR e ADMIN têm workflow:execute: aceitos no próprio tenant")
    void papeisComWorkflowExecuteAceitos() throws Exception {
        assertThat(handshake(true, false, "tenant_b", login("dani").accessToken()).aceito()).isTrue();
        assertThat(handshake(true, false, "tenant_b", login("bia").accessToken()).aceito()).isTrue();
        assertThat(handshake(true, false, "tenant_a", login("root").accessToken()).aceito()).isTrue();
    }

    @Test
    @DisplayName("tenant da identidade suspenso: recusado (403); reativado, aceito de novo")
    void tenantSuspensoRecusado() throws Exception {
        String token = login("bia").accessToken();
        suspensos.add("tenant_b");

        Handshake h = handshake(true, false, "tenant_b", token);
        assertThat(h.aceito()).isFalse();
        assertThat(h.status()).isEqualTo(403);

        suspensos.remove("tenant_b");
        assertThat(handshake(true, false, "tenant_b", token).aceito()).isTrue();
    }

    @Test
    @DisplayName("superadmin de outro tenant personificando o suspenso: aceito — conta o tenant da identidade")
    void personificandoOSuspenso() throws Exception {
        suspensos.add("tenant_b");

        assertThat(handshake(true, true, "tenant_b", login("root").accessToken()).aceito()).isTrue();
    }

    @Test
    @DisplayName("store de revogação fora do ar: recusado com 503, não 401 — a falha não é do token")
    void storeForaDoAr() throws Exception {
        String token = login("bia").accessToken();
        br.com.archflow.security.auth.TokenBlacklist foraDoAr =
                mock(br.com.archflow.security.auth.TokenBlacklist.class);
        org.mockito.Mockito.when(foraDoAr.isRevoked(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("Failed to check token revocation"));
        auth = new AuthService(jwtService, passwords, users, foraDoAr);

        Handshake h = handshake(true, false, "tenant_b", token);

        assertThat(h.aceito()).isFalse();
        assertThat(h.status()).isEqualTo(503);
    }

    @Test
    @DisplayName("sem token: recusado (401)")
    void semTokenRecusado() throws Exception {
        assertThat(handshake(true, false, "tenant_b", null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("autenticação desligada: como antes, aceito sem token")
    void autenticacaoDesligadaComoAntes() throws Exception {
        Handshake h = handshake(false, false, "qualquer", null);

        assertThat(h.aceito()).isTrue();
        assertThat(h.atributos()).containsEntry("tenantId", "qualquer");
    }
}
