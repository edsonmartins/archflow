package br.com.archflow.api.config;

import br.com.archflow.api.realtime.SpringRealtimeController;
import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.security.auth.AuthService;
import br.com.archflow.security.auth.InMemoryTokenBlacklist;
import br.com.archflow.security.auth.InMemoryUserRepository;
import br.com.archflow.security.auth.TokenBlacklist;
import br.com.archflow.security.jwt.JwtService;
import br.com.archflow.security.password.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-197: o WebSocket de voz conferia papel, revogação e tenant suspenso só no handshake. Uma sessão
 * já aberta seguia conversando com o agente depois do logout, do rebaixamento para VIEWER ou da suspensão
 * do tenant. Agora a mensagem que chega depois do intervalo de reconferência fecha a sessão em vez de
 * seguir para o agente.
 */
@DisplayName("Sessão aberta do realtime: reconferida depois do handshake")
class SessaoAbertaDoRealtimeTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";
    private static final Duration INTERVALO = Duration.ofSeconds(5);

    private final PasswordService passwords = new PasswordService(4);
    private final Set<String> suspensos = new HashSet<>();
    private final RelogioManual relogio = new RelogioManual(Instant.parse("2026-11-27T12:00:00Z"));
    private JwtService jwtService = new JwtService(SECRET);
    private InMemoryUserRepository users;
    private AuthService auth;

    @BeforeEach
    void monta() {
        users = new InMemoryUserRepository();
        usuario("u-bia", "bia", "tenant_b", Role.createExecutorRole());
        usuario("u-root", "root", "tenant_a", Role.createAdminRole());
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

    private String login(String username) {
        return auth.authenticate(username, "segredo123").accessToken();
    }

    private void rebaixarParaViewer(String userId) {
        User user = users.findById(userId).orElseThrow();
        user.getRoles().clear();
        user.addRole(Role.createViewerRole());
        users.save(user);
    }

    /** Uma sessão aberta pelo handshake real, com o agente da persona no lugar de um mock. */
    private final class Sessao {
        final WebSocketConfiguration config;
        final WebSocketSession socket = mock(WebSocketSession.class);
        final WebSocketHandler agente = mock(WebSocketHandler.class);
        final Map<String, Object> atributos = new HashMap<>();

        Sessao(boolean authEnabled, boolean impersonation, String tenant, String token) throws Exception {
            config = new WebSocketConfiguration(null, jwtService, auth, new String[] {"http://localhost:5173"},
                    authEnabled, impersonation, suspensos::contains, INTERVALO, relogio);
            MockHttpServletRequest http = new MockHttpServletRequest("GET", "/api/realtime/" + tenant + "/p1");
            if (token != null) {
                http.setQueryString("token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
            }
            boolean aceito = config.pathVariablesInterceptor().beforeHandshake(new ServletServerHttpRequest(http),
                    new ServletServerHttpResponse(new MockHttpServletResponse()), mock(WebSocketHandler.class),
                    atributos);
            assertThat(aceito).as("handshake").isTrue();
            atributos.put(SpringRealtimeController.class.getName(), agente);
            when(socket.getAttributes()).thenReturn(atributos);
            when(socket.getId()).thenReturn("s1");
        }

        /** Manda uma mensagem de áudio; {@code true} quando ela chegou ao agente. */
        boolean fala() throws Exception {
            org.mockito.Mockito.clearInvocations(agente);
            config.delegatingHandler().handleMessage(socket, new TextMessage("{\"type\":\"audio\"}"));
            try {
                verify(agente).handleMessage(any(), any());
                return true;
            } catch (AssertionError naoChegou) {
                return false;
            }
        }
    }

    @Test
    @DisplayName("logout: dentro do intervalo a mensagem passa; depois dele a sessão fecha como revogada")
    void logoutFechaASessaoAberta() throws Exception {
        String token = login("bia");
        Sessao sessao = new Sessao(true, false, "tenant_b", token);
        assertThat(sessao.fala()).isTrue();

        auth.logout(token);
        assertThat(sessao.fala()).as("ainda dentro do intervalo").isTrue();

        relogio.avanca(INTERVALO);
        assertThat(sessao.fala()).isFalse();
        verify(sessao.socket).close(CloseStatus.POLICY_VIOLATION.withReason("SESSION_REVOKED"));
    }

    @Test
    @DisplayName("rebaixado para VIEWER com a sessão aberta: fecha (FORBIDDEN)")
    void rebaixadoFecha() throws Exception {
        Sessao sessao = new Sessao(true, false, "tenant_b", login("bia"));

        rebaixarParaViewer("u-bia");
        relogio.avanca(INTERVALO);

        assertThat(sessao.fala()).isFalse();
        verify(sessao.socket).close(CloseStatus.POLICY_VIOLATION.withReason("FORBIDDEN"));
    }

    @Test
    @DisplayName("usuário desativado com a sessão aberta: fecha como revogada")
    void desativadoFecha() throws Exception {
        Sessao sessao = new Sessao(true, false, "tenant_b", login("bia"));

        User bia = users.findById("u-bia").orElseThrow();
        bia.setEnabled(false);
        users.save(bia);
        relogio.avanca(INTERVALO);

        assertThat(sessao.fala()).isFalse();
        verify(sessao.socket).close(CloseStatus.POLICY_VIOLATION.withReason("SESSION_REVOKED"));
    }

    @Test
    @DisplayName("tenant suspenso com a sessão aberta: fecha (FORBIDDEN)")
    void tenantSuspensoFecha() throws Exception {
        Sessao sessao = new Sessao(true, false, "tenant_b", login("bia"));

        suspensos.add("tenant_b");
        relogio.avanca(INTERVALO);

        assertThat(sessao.fala()).isFalse();
        verify(sessao.socket).close(CloseStatus.POLICY_VIOLATION.withReason("FORBIDDEN"));
    }

    @Test
    @DisplayName("superadmin personificando: rebaixado, perde a sessão no tenant personificado")
    void personificacaoReconferida() throws Exception {
        Sessao sessao = new Sessao(true, true, "tenant_b", login("root"));
        assertThat(sessao.fala()).isTrue();

        rebaixarParaViewer("u-root");
        relogio.avanca(INTERVALO);

        assertThat(sessao.fala()).isFalse();
    }

    @Test
    @DisplayName("nada mudou: segue aberta depois de várias reconferências, e a expiração do token não conta")
    void semMudancaSegue() throws Exception {
        // Access token de 2 s: já vencido na reconferência. O cliente não renova o token de um socket
        // aberto — conferir a expiração derrubaria toda sessão de voz 15 min depois do login.
        jwtService = new JwtService(SECRET, 2_000, 60_000);
        auth = new AuthService(jwtService, passwords, users, new InMemoryTokenBlacklist());
        Sessao sessao = new Sessao(true, false, "tenant_b", login("bia"));
        Thread.sleep(2_100);

        for (int i = 0; i < 3; i++) {
            relogio.avanca(INTERVALO);
            assertThat(sessao.fala()).isTrue();
        }
        verify(sessao.socket, never()).close(any());
    }

    @Test
    @DisplayName("dentro do intervalo não consulta o store: ~190 mensagens de áudio por segundo")
    void dentroDoIntervaloNaoConsulta() throws Exception {
        TokenBlacklist contado = mock(TokenBlacklist.class);
        auth = new AuthService(jwtService, passwords, users, contado);
        Sessao sessao = new Sessao(true, false, "tenant_b", login("bia"));
        org.mockito.Mockito.clearInvocations(contado);

        for (int i = 0; i < 50; i++) {
            assertThat(sessao.fala()).isTrue();
        }
        verify(contado, never()).isRevoked(anyString());

        relogio.avanca(INTERVALO);
        assertThat(sessao.fala()).isTrue();
        verify(contado).isRevoked(anyString());
    }

    @Test
    @DisplayName("store de revogação fora do ar na reconferência: fecha com 1013 (tente de novo), não como revogada")
    void storeForaDoArFecha() throws Exception {
        TokenBlacklist store = mock(TokenBlacklist.class);
        auth = new AuthService(jwtService, passwords, users, store);
        Sessao sessao = new Sessao(true, false, "tenant_b", login("bia"));

        when(store.isRevoked(anyString())).thenThrow(new IllegalStateException("Failed to check token revocation"));
        relogio.avanca(INTERVALO);

        assertThat(sessao.fala()).isFalse();
        verify(sessao.socket).close(CloseStatus.SERVICE_OVERLOAD.withReason("AUTH_UNAVAILABLE"));
    }

    @Test
    @DisplayName("autenticação desligada: como antes, nada a reconferir")
    void autenticacaoDesligada() throws Exception {
        Sessao sessao = new Sessao(false, false, "qualquer", null);
        relogio.avanca(INTERVALO);

        assertThat(sessao.fala()).isTrue();
    }

    @Test
    @DisplayName("a identidade guardada na sessão não leva o token no toString")
    void identidadeSemToken() throws Exception {
        String token = login("bia");
        Sessao sessao = new Sessao(true, false, "tenant_b", token);

        assertThat(String.valueOf(sessao.atributos.get(WebSocketConfiguration.IDENTITY_ATTRIBUTE)))
                .doesNotContain(token);
    }

    /** Relógio que só anda quando o teste manda. */
    private static final class RelogioManual extends Clock {
        private Instant agora;

        RelogioManual(Instant inicio) {
            this.agora = inicio;
        }

        void avanca(Duration d) {
            agora = agora.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }
}
