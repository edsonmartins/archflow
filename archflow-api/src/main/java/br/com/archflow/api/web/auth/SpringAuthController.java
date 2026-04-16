package br.com.archflow.api.web.auth;

import br.com.archflow.api.auth.AuthController;
import br.com.archflow.api.auth.dto.LoginRequest;
import br.com.archflow.api.auth.dto.LoginResponse;
import br.com.archflow.api.auth.dto.MeResponse;
import br.com.archflow.api.auth.dto.RefreshTokenRequest;
import br.com.archflow.api.auth.dto.RefreshTokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Spring MVC adapter that delegates to the framework-agnostic {@link AuthController}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/auth/login} — username/password authentication</li>
 *   <li>{@code POST /api/auth/refresh} — token refresh</li>
 *   <li>{@code POST /api/auth/logout} — invalidate tokens</li>
 *   <li>{@code GET /api/auth/me} — current user info</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class SpringAuthController {

    private final AuthController delegate;

    public SpringAuthController(AuthController delegate) {
        this.delegate = delegate;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        return delegate.login(request);
    }

    @PostMapping("/refresh")
    public RefreshTokenResponse refresh(@RequestBody RefreshTokenRequest request) {
        return delegate.refreshToken(request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = stripBearer(authorization);
        if (token != null) {
            delegate.logout(token);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me(@RequestHeader("Authorization") String authorization) {
        return delegate.me(stripBearer(authorization));
    }

    private static String stripBearer(String header) {
        if (header == null) return null;
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return header.trim();
    }

    @ExceptionHandler(AuthController.AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleAuthException(AuthController.AuthenticationException ex,
                                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "error", "Unauthorized",
                        "message", ex.getMessage(),
                        "path", request.getRequestURI()
                ));
    }
}
