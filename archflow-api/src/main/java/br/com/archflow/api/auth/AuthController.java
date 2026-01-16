package br.com.archflow.api.auth;

import br.com.archflow.api.auth.dto.LoginRequest;
import br.com.archflow.api.auth.dto.LoginResponse;
import br.com.archflow.api.auth.dto.MeResponse;
import br.com.archflow.api.auth.dto.RefreshTokenRequest;
import br.com.archflow.api.auth.dto.RefreshTokenResponse;

/**
 * REST controller for authentication operations.
 *
 * <p>This interface defines the contract for authentication endpoints.
 * Implementations can be provided for different web frameworks (Spring Boot, JAX-RS, etc.).</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>POST /api/auth/login - Authenticate with username/password</li>
 *   <li>POST /api/auth/refresh - Refresh access token using refresh token</li>
 *   <li>POST /api/auth/logout - Logout and invalidate tokens</li>
 *   <li>GET /api/auth/me - Get current user information</li>
 * </ul>
 */
public interface AuthController {

    /**
     * Authenticates a user with username and password.
     *
     * @param request The login request containing username and password
     * @return Login response with access and refresh tokens
     * @throws AuthenticationException if authentication fails
     */
    LoginResponse login(LoginRequest request);

    /**
     * Refreshes an access token using a refresh token.
     *
     * @param request The refresh token request
     * @return Response with new access and refresh tokens
     * @throws AuthenticationException if refresh token is invalid or expired
     */
    RefreshTokenResponse refreshToken(RefreshTokenRequest request);

    /**
     * Logs out the current user and invalidates tokens.
     *
     * @param accessToken The access token to invalidate
     */
    void logout(String accessToken);

    /**
     * Gets information about the currently authenticated user.
     *
     * @param accessToken The access token of the current user
     * @return The current user's information
     * @throws AuthenticationException if token is invalid
     */
    MeResponse me(String accessToken);

    /**
     * Exception thrown when authentication fails.
     */
    class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) {
            super(message);
        }

        public AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
