package br.com.archflow.api.auth.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Auth DTOs")
class AuthDtosTest {

    @Nested
    @DisplayName("LoginRequest")
    class LoginRequestTest {

        @Test
        @DisplayName("should create with username and password")
        void shouldCreate() {
            var request = new LoginRequest("john", "secret");

            assertThat(request.username()).isEqualTo("john");
            assertThat(request.password()).isEqualTo("secret");
        }

        @Test
        @DisplayName("should support equality")
        void shouldSupportEquality() {
            var r1 = new LoginRequest("john", "pass");
            var r2 = new LoginRequest("john", "pass");

            assertThat(r1).isEqualTo(r2);
        }
    }

    @Nested
    @DisplayName("LoginResponse")
    class LoginResponseTest {

        @Test
        @DisplayName("should create with all fields")
        void shouldCreate() {
            var now = Instant.now();
            var response = new LoginResponse(
                    "access", "refresh", "Bearer", 900,
                    now, "user-1", "john", "john@test.com", Set.of("ADMIN")
            );

            assertThat(response.accessToken()).isEqualTo("access");
            assertThat(response.refreshToken()).isEqualTo("refresh");
            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.expiresIn()).isEqualTo(900);
            assertThat(response.roles()).contains("ADMIN");
        }

        @Test
        @DisplayName("should default tokenType to Bearer when null")
        void shouldDefaultTokenType() {
            var response = new LoginResponse(
                    "access", "refresh", null, 900,
                    Instant.now(), "u1", "john", "j@t.com", Set.of()
            );

            assertThat(response.tokenType()).isEqualTo("Bearer");
        }

        @Test
        @DisplayName("should default tokenType to Bearer when empty")
        void shouldDefaultTokenTypeWhenEmpty() {
            var response = new LoginResponse(
                    "access", "refresh", "", 900,
                    Instant.now(), "u1", "john", "j@t.com", Set.of()
            );

            assertThat(response.tokenType()).isEqualTo("Bearer");
        }
    }

    @Nested
    @DisplayName("RefreshTokenRequest")
    class RefreshTokenRequestTest {

        @Test
        @DisplayName("should create with refresh token")
        void shouldCreate() {
            var request = new RefreshTokenRequest("token-123");
            assertThat(request.refreshToken()).isEqualTo("token-123");
        }
    }

    @Nested
    @DisplayName("RefreshTokenResponse")
    class RefreshTokenResponseTest {

        @Test
        @DisplayName("should create with all fields")
        void shouldCreate() {
            var now = Instant.now();
            var response = new RefreshTokenResponse("access", "refresh", 900, now);

            assertThat(response.accessToken()).isEqualTo("access");
            assertThat(response.refreshToken()).isEqualTo("refresh");
            assertThat(response.expiresIn()).isEqualTo(900);
            assertThat(response.expiresAt()).isEqualTo(now);
        }
    }

    @Nested
    @DisplayName("MeResponse")
    class MeResponseTest {

        @Test
        @DisplayName("should create with all fields")
        void shouldCreate() {
            var now = Instant.now();
            var response = new MeResponse(
                    "u1", "john", "john@test.com",
                    Set.of("ADMIN"), true, now, now
            );

            assertThat(response.id()).isEqualTo("u1");
            assertThat(response.username()).isEqualTo("john");
            assertThat(response.enabled()).isTrue();
        }
    }
}
