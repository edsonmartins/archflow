package br.com.archflow.api.auth.impl;

import br.com.archflow.api.auth.AuthController;
import br.com.archflow.api.auth.dto.LoginRequest;
import br.com.archflow.api.auth.dto.LoginResponse;
import br.com.archflow.api.auth.dto.MeResponse;
import br.com.archflow.api.auth.dto.RefreshTokenRequest;
import br.com.archflow.api.auth.dto.RefreshTokenResponse;
import br.com.archflow.security.auth.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link AuthController}.
 *
 * <p>This implementation uses the {@link AuthService} for authentication operations.
 * It can be used directly or wrapped by framework-specific adapters (Spring, etc.).</p>
 */
public class AuthControllerImpl implements AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthControllerImpl.class);

    private final AuthService authService;
    private final long accessTokenExpirationSeconds;

    /**
     * Creates a new AuthControllerImpl.
     *
     * @param authService The authentication service
     * @param accessTokenExpirationSeconds Access token expiration in seconds
     */
    public AuthControllerImpl(AuthService authService, long accessTokenExpirationSeconds) {
        this.authService = authService;
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
    }

    /**
     * Creates a new AuthControllerImpl with default 15-minute token expiration.
     *
     * @param authService The authentication service
     */
    public AuthControllerImpl(AuthService authService) {
        this(authService, 15 * 60); // 15 minutes default
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        try {
            log.debug("Login attempt for user: {}", request.username());

            AuthService.AuthenticationResult result = authService.authenticate(
                    request.username(),
                    request.password()
            );

            log.info("User {} authenticated successfully", request.username());

            return new LoginResponse(
                    result.accessToken(),
                    result.refreshToken(),
                    "Bearer",
                    accessTokenExpirationSeconds,
                    result.expiresAt(),
                    result.userId(),
                    result.username(),
                    result.email(),
                    Set.of(result.roles())
            );

        } catch (AuthService.AuthenticationException e) {
            log.warn("Authentication failed for user {}: {}", request.username(), e.getMessage());
            throw new AuthenticationException("Invalid username or password", e);
        }
    }

    @Override
    public RefreshTokenResponse refreshToken(RefreshTokenRequest request) {
        try {
            log.debug("Token refresh requested");

            AuthService.AuthenticationResult result = authService.refreshToken(request.refreshToken());

            log.info("Token refreshed successfully for user: {}", result.username());

            return new RefreshTokenResponse(
                    result.accessToken(),
                    result.refreshToken(),
                    accessTokenExpirationSeconds,
                    result.expiresAt()
            );

        } catch (AuthService.AuthenticationException e) {
            log.warn("Token refresh failed: {}", e.getMessage());
            throw new AuthenticationException("Invalid or expired refresh token", e);
        }
    }

    @Override
    public void logout(String accessToken) {
        try {
            AuthService.UserInfo userInfo = authService.getUserInfoFromToken(accessToken);
            log.info("Logout requested for user: {}", userInfo.username());

            authService.logout(accessToken);

            log.info("User {} logged out successfully", userInfo.username());

        } catch (AuthService.AuthenticationException e) {
            log.warn("Logout failed: {}", e.getMessage());
            throw new AuthenticationException("Invalid token", e);
        }
    }

    @Override
    public MeResponse me(String accessToken) {
        try {
            AuthService.UserInfo userInfo = authService.getUserInfoFromToken(accessToken);

            log.debug("Me request for user: {}", userInfo.username());

            return new MeResponse(
                    userInfo.userId(),
                    userInfo.username(),
                    userInfo.email(),
                    userInfo.roleSet(),
                    userInfo.enabled(),
                    userInfo.createdAt(),
                    userInfo.lastLoginAt()
            );

        } catch (AuthService.AuthenticationException e) {
            log.warn("Get user info failed: {}", e.getMessage());
            throw new AuthenticationException("Invalid token", e);
        }
    }
}
