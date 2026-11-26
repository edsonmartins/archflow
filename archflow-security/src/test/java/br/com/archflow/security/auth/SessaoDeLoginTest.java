package br.com.archflow.security.auth;

import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.security.jwt.JwtService;
import br.com.archflow.security.password.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA2 T1/T2: o logout encerra a sessão inteira (o refresh token dela também), o refresh token é de
 * uso único, e quem autoriza um access token vê o usuário como ele está agora — rebaixado, desativado
 * ou removido — e não como estava quando o token foi emitido.
 */
@DisplayName("Sessão de login: logout, rotação do refresh token e papel atual")
class SessaoDeLoginTest {

    private static final String SECRET = "test-secret-key-for-session-tests-minimum-32-bytes-long";

    private JwtService jwtService;
    private PasswordService passwordService;
    private InMemoryUserRepository users;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET);
        passwordService = new PasswordService(4);
        users = new InMemoryUserRepository();
        auth = new AuthService(jwtService, passwordService, users, new InMemoryTokenBlacklist());
        usuario("u-ana", "ana", Role.createAdminRole());
    }

    private User usuario(String id, String username, Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwordService.hash("segredo123"));
        user.setEnabled(true);
        user.setAccountNonLocked(true);
        user.setCreatedAt(LocalDateTime.now());
        user.addRole(role);
        return users.save(user);
    }

    private AuthService.AuthenticationResult login() {
        return auth.authenticate("ana", "segredo123");
    }

    @Nested
    @DisplayName("logout (T1)")
    class Logout {

        @Test
        @DisplayName("o refresh token da sessão para junto com o access, embora o cliente só mande o access")
        void logoutRevogaORefreshDaSessao() {
            AuthService.AuthenticationResult sessao = login();

            auth.logout(sessao.accessToken());

            assertThatThrownBy(() -> auth.refreshToken(sessao.refreshToken()))
                    .isInstanceOf(AuthService.AuthenticationException.class);
            assertThatThrownBy(() -> auth.validateAccessToken(sessao.accessToken()))
                    .hasMessageContaining("revoked");
        }

        @Test
        @DisplayName("depois de rotações, o logout alcança o par mais novo")
        void logoutAlcancaOParRotacionado() {
            AuthService.AuthenticationResult primeiro = login();
            AuthService.AuthenticationResult segundo = auth.refreshToken(primeiro.refreshToken());
            AuthService.AuthenticationResult terceiro = auth.refreshToken(segundo.refreshToken());

            // O SPA faz logout com o access corrente; os anteriores da sessão também param.
            auth.logout(terceiro.accessToken());

            assertThatThrownBy(() -> auth.refreshToken(terceiro.refreshToken()))
                    .isInstanceOf(AuthService.AuthenticationException.class);
            assertThatThrownBy(() -> auth.validateAccessToken(primeiro.accessToken()))
                    .hasMessageContaining("revoked");
        }

        @Test
        @DisplayName("outra sessão do mesmo usuário (outro navegador) continua")
        void outraSessaoContinua() {
            AuthService.AuthenticationResult navegadorA = login();
            AuthService.AuthenticationResult navegadorB = login();

            auth.logout(navegadorA.accessToken());

            assertThatCode(() -> auth.validateAccessToken(navegadorB.accessToken())).doesNotThrowAnyException();
            assertThat(auth.refreshToken(navegadorB.refreshToken()).accessToken()).isNotBlank();
        }

        @Test
        @DisplayName("token emitido antes da claim de sessão: o logout revoga o próprio token, como antes")
        void tokenSemSessaoSegueRevogavel() {
            String legado = jwtService.generateTenantAccessToken("u-ana", "ana", null, new String[] {"ADMIN"});

            auth.logout(legado);

            assertThatThrownBy(() -> auth.validateAccessToken(legado)).hasMessageContaining("revoked");
        }
    }

    @Nested
    @DisplayName("refresh token de uso único (T1)")
    class Rotacao {

        @Test
        @DisplayName("o refresh token usado não renova de novo")
        void refreshUsadoNaoVale() {
            AuthService.AuthenticationResult sessao = login();

            AuthService.AuthenticationResult renovado = auth.refreshToken(sessao.refreshToken());

            assertThat(renovado.refreshToken()).isNotEqualTo(sessao.refreshToken());
            assertThatThrownBy(() -> auth.refreshToken(sessao.refreshToken()))
                    .isInstanceOf(AuthService.AuthenticationException.class)
                    .hasMessageContaining("already used");
        }

        @Test
        @DisplayName("o reuso encerra a sessão: o par que a rotação emitiu também para")
        void reusoEncerraASessao() {
            AuthService.AuthenticationResult sessao = login();
            AuthService.AuthenticationResult renovado = auth.refreshToken(sessao.refreshToken());

            // Quem copiou o refresh token antigo tenta usá-lo: não há como saber quem é o legítimo.
            assertThatThrownBy(() -> auth.refreshToken(sessao.refreshToken()))
                    .isInstanceOf(AuthService.AuthenticationException.class);

            assertThatThrownBy(() -> auth.refreshToken(renovado.refreshToken()))
                    .isInstanceOf(AuthService.AuthenticationException.class);
            assertThatThrownBy(() -> auth.validateAccessToken(renovado.accessToken()))
                    .hasMessageContaining("revoked");
        }

        @Test
        @DisplayName("refreshes simultâneos do mesmo token: exatamente um passa")
        void refreshConcorrenteTemUmVencedor() throws Exception {
            AuthService.AuthenticationResult sessao = login();
            int threads = 8;
            CountDownLatch largada = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Boolean>> tentativas = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    Callable<Boolean> tentativa = () -> {
                        largada.await();
                        try {
                            auth.refreshToken(sessao.refreshToken());
                            return true;
                        } catch (AuthService.AuthenticationException e) {
                            return false;
                        }
                    };
                    tentativas.add(pool.submit(tentativa));
                }
                largada.countDown();
                int sucessos = 0;
                for (Future<Boolean> tentativa : tentativas) {
                    if (tentativa.get()) {
                        sucessos++;
                    }
                }
                assertThat(sucessos).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("refresh token de antes da claim de sessão: recusado, mesmo antes do logout")
        void refreshLegadoRecusado() {
            // Um refresh legado renovava num par novo — com sessão nova — mesmo depois do logout do
            // access legado, que só revoga aquele access: o logout não o alcançava.
            Map<String, Object> claims = new HashMap<>();
            claims.put("type", "refresh");
            String refreshLegado = jwtService.generateToken("u-ana", "ana", 60_000, claims);
            String accessLegado = jwtService.generateTenantAccessToken("u-ana", "ana", null, new String[] {"ADMIN"});

            auth.logout(accessLegado);

            assertThatThrownBy(() -> auth.refreshToken(refreshLegado))
                    .isInstanceOf(AuthService.AuthenticationException.class)
                    .hasMessageContaining("log in again");
        }

        @Test
        @DisplayName("access token não serve de refresh token")
        void accessNaoRenova() {
            AuthService.AuthenticationResult sessao = login();

            assertThatThrownBy(() -> auth.refreshToken(sessao.accessToken()))
                    .isInstanceOf(AuthService.AuthenticationException.class);
        }
    }

    @Nested
    @DisplayName("papel e estado atuais do usuário (T2)")
    class EstadoAtual {

        @Test
        @DisplayName("rebaixado: o token já emitido passa a valer com o papel novo")
        void rebaixadoValeNaHora() {
            AuthService.AuthenticationResult sessao = login();
            assertThat(auth.getUserInfoFromToken(sessao.accessToken()).roleSet()).containsExactly("ADMIN");

            User ana = users.findById("u-ana").orElseThrow();
            ana.getRoles().clear();
            ana.addRole(Role.createViewerRole());
            users.save(ana);

            assertThat(auth.getUserInfoFromToken(sessao.accessToken()).roleSet()).containsExactly("VIEWER");
        }

        @Test
        @DisplayName("desativado ou bloqueado: o token já emitido para de valer")
        void desativadoParaNaHora() {
            AuthService.AuthenticationResult sessao = login();

            User ana = users.findById("u-ana").orElseThrow();
            ana.setEnabled(false);
            users.save(ana);
            assertThatThrownBy(() -> auth.getUserInfoFromToken(sessao.accessToken()))
                    .isInstanceOf(AuthService.AuthenticationException.class);

            ana.setEnabled(true);
            ana.setAccountNonLocked(false);
            users.save(ana);
            assertThatThrownBy(() -> auth.getUserInfoFromToken(sessao.accessToken()))
                    .isInstanceOf(AuthService.AuthenticationException.class);
        }

        @Test
        @DisplayName("removido: o token já emitido para de valer")
        void removidoParaNaHora() {
            AuthService.AuthenticationResult sessao = login();

            users.delete(users.findById("u-ana").orElseThrow());

            assertThatThrownBy(() -> auth.getUserInfoFromToken(sessao.accessToken()))
                    .isInstanceOf(AuthService.AuthenticationException.class);
        }
    }
}
