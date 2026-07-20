package br.com.archflow.api.auth.impl;

import br.com.archflow.api.auth.AuthController;
import br.com.archflow.api.auth.dto.LoginRequest;
import br.com.archflow.api.auth.dto.RefreshTokenRequest;
import br.com.archflow.security.auth.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthControllerImpl")
class AuthControllerImplTest {

    @Mock
    private AuthService authService;

    private AuthControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new AuthControllerImpl(authService, 900);
    }

    private AuthService.AuthenticationResult createAuthResult() {
        return new AuthService.AuthenticationResult(
                "access-token-123",
                "refresh-token-456",
                "user-1",
                "john",
                "John Doe",
                "john@test.com",
                new String[]{"ADMIN"},
                900000L
        );
    }

    @Nested
    @DisplayName("login")
    class LoginTest {

        @Test
        @DisplayName("should login successfully")
        void shouldLoginSuccessfully() {
            var request = new LoginRequest("john", "password123");
            when(authService.authenticate("john", "password123")).thenReturn(createAuthResult());

            var response = controller.login(request);

            assertThat(response.accessToken()).isEqualTo("access-token-123");
            assertThat(response.refreshToken()).isEqualTo("refresh-token-456");
            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.expiresIn()).isEqualTo(900);
            assertThat(response.userId()).isEqualTo("user-1");
            assertThat(response.username()).isEqualTo("john");
            assertThat(response.email()).isEqualTo("john@test.com");
            assertThat(response.roles()).contains("ADMIN");
        }

        @Test
        @DisplayName("should throw on invalid credentials")
        void shouldThrowOnInvalidCredentials() {
            var request = new LoginRequest("john", "wrong");
            when(authService.authenticate("john", "wrong"))
                    .thenThrow(new AuthService.AuthenticationException("Invalid credentials"));

            assertThatThrownBy(() -> controller.login(request))
                    .isInstanceOf(AuthController.AuthenticationException.class)
                    .hasMessageContaining("Invalid username or password");
        }
    }

    @Nested
    @DisplayName("refreshToken")
    class RefreshTokenTest {

        @Test
        @DisplayName("should refresh token successfully")
        void shouldRefreshSuccessfully() {
            var request = new RefreshTokenRequest("refresh-token-456");
            when(authService.refreshToken("refresh-token-456")).thenReturn(createAuthResult());

            var response = controller.refreshToken(request);

            assertThat(response.accessToken()).isEqualTo("access-token-123");
            assertThat(response.refreshToken()).isEqualTo("refresh-token-456");
            assertThat(response.expiresIn()).isEqualTo(900);
        }

        @Test
        @DisplayName("should throw on invalid refresh token")
        void shouldThrowOnInvalidToken() {
            var request = new RefreshTokenRequest("invalid");
            when(authService.refreshToken("invalid"))
                    .thenThrow(new AuthService.AuthenticationException("Token expired"));

            assertThatThrownBy(() -> controller.refreshToken(request))
                    .isInstanceOf(AuthController.AuthenticationException.class);
        }
    }

    @Nested
    @DisplayName("logout")
    class LogoutTest {

        @Test
        @DisplayName("should logout successfully")
        void shouldLogoutSuccessfully() {
            var userInfo = new AuthService.UserInfo(
                    "user-1", "john", "john@test.com",
                    new String[]{"ADMIN"}, true, Instant.now(), Instant.now()
            );
            when(authService.getUserInfoFromToken("token")).thenReturn(userInfo);

            assertThatNoException().isThrownBy(() -> controller.logout("token"));
            verify(authService).logout("token");
        }

        @Test
        @DisplayName("should throw on invalid token")
        void shouldThrowOnInvalidToken() {
            when(authService.getUserInfoFromToken("invalid"))
                    .thenThrow(new AuthService.AuthenticationException("Invalid"));

            assertThatThrownBy(() -> controller.logout("invalid"))
                    .isInstanceOf(AuthController.AuthenticationException.class);
        }
    }

    @Nested
    @DisplayName("me")
    class MeTest {

        @Test
        @DisplayName("should return user info")
        void shouldReturnUserInfo() {
            var userInfo = new AuthService.UserInfo(
                    "user-1", "john", "john@test.com",
                    new String[]{"ADMIN", "DESIGNER"}, true, Instant.now(), Instant.now()
            );
            when(authService.getUserInfoFromToken("token")).thenReturn(userInfo);

            var response = controller.me("token");

            assertThat(response.id()).isEqualTo("user-1");
            assertThat(response.username()).isEqualTo("john");
            assertThat(response.email()).isEqualTo("john@test.com");
            assertThat(response.roles()).containsExactlyInAnyOrder("ADMIN", "DESIGNER");
            assertThat(response.enabled()).isTrue();
        }

        @Test
        @DisplayName("should throw on invalid token")
        void shouldThrowOnInvalidToken() {
            when(authService.getUserInfoFromToken("invalid"))
                    .thenThrow(new AuthService.AuthenticationException("Invalid"));

            assertThatThrownBy(() -> controller.me("invalid"))
                    .isInstanceOf(AuthController.AuthenticationException.class);
        }
    }

    @Test
    @DisplayName("should use default token expiration of 15 minutes")
    void shouldUseDefaultExpiration() {
        var ctrl = new AuthControllerImpl(authService);
        when(authService.authenticate("john", "pass")).thenReturn(createAuthResult());

        var response = ctrl.login(new LoginRequest("john", "pass"));

        assertThat(response.expiresIn()).isEqualTo(900); // 15 * 60
    }

    @Nested
    @DisplayName("audit trail")
    class AuditTest {

        private final br.com.archflow.observability.audit.InMemoryAuditRepository auditRepo =
                new br.com.archflow.observability.audit.InMemoryAuditRepository();

        private java.util.List<br.com.archflow.observability.audit.AuditEvent> events() {
            return auditRepo.query(
                    br.com.archflow.observability.audit.AuditRepository.AuditQuery.builder().limit(10));
        }

        @Test
        @DisplayName("login com sucesso grava LOGIN_SUCCESS")
        void loginSuccessIsAudited() {
            controller.setAuditTrail(new br.com.archflow.api.audit.AuditTrail(() -> auditRepo));
            when(authService.authenticate("john", "password123")).thenReturn(createAuthResult());

            controller.login(new LoginRequest("john", "password123"));

            assertThat(events()).singleElement().satisfies(e -> {
                assertThat(e.getAction())
                        .isEqualTo(br.com.archflow.observability.audit.AuditAction.LOGIN_SUCCESS);
                assertThat(e.getUserId()).isEqualTo("user-1");
                assertThat(e.getUsername()).isEqualTo("john");
                assertThat(e.isSuccess()).isTrue();
            });
        }

        @Test
        @DisplayName("login inválido grava LOGIN_FAILED sem vazar detalhes")
        void loginFailureIsAudited() {
            controller.setAuditTrail(new br.com.archflow.api.audit.AuditTrail(() -> auditRepo));
            when(authService.authenticate("john", "wrong"))
                    .thenThrow(new AuthService.AuthenticationException("Invalid credentials"));

            assertThatThrownBy(() -> controller.login(new LoginRequest("john", "wrong")))
                    .isInstanceOf(AuthController.AuthenticationException.class);

            assertThat(events()).singleElement().satisfies(e -> {
                assertThat(e.getAction())
                        .isEqualTo(br.com.archflow.observability.audit.AuditAction.LOGIN_FAILED);
                assertThat(e.getUsername()).isEqualTo("john");
                assertThat(e.isSuccess()).isFalse();
            });
        }
    }
}
