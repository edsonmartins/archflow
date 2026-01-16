package br.com.archflow.security.auth;

import br.com.archflow.model.security.ApiKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

/**
 * Filter for API key authentication.
 *
 * This filter intercepts requests containing an API key in the Authorization header
 * (format: Bearer {api-key}) or X-API-Key header, validates the key, and sets the authentication
 * in the security context.
 *
 * API keys are typically used for service-to-service communication or CLI tools.
 */
public class ApiKeyAuthenticationFilter implements jakarta.servlet.Filter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyRepository apiKeyRepository;
    private final SecurityContext securityContext;

    /**
     * Creates a new ApiKeyAuthenticationFilter.
     *
     * @param apiKeyRepository The API key repository
     * @param securityContext The security context for storing authenticated user
     */
    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeyRepository, SecurityContext securityContext) {
        this.apiKeyRepository = apiKeyRepository;
        this.securityContext = securityContext;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {

        // Only process HTTP requests
        if (!(request instanceof HttpServletRequest httpServletRequest) ||
            !(response instanceof HttpServletResponse httpServletResponse)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Extract API key from request
        String apiKeyValue = extractApiKey(httpServletRequest);

        if (apiKeyValue != null && !apiKeyValue.isEmpty()) {
            // Validate API key
            Optional<ApiKey> apiKeyOpt = apiKeyRepository.findByKeyId(apiKeyValue);

            if (apiKeyOpt.isPresent()) {
                ApiKey apiKey = apiKeyOpt.get();

                if (apiKey.isValid()) {
                    // Update last used timestamp
                    apiKey.markAsUsed();
                    apiKeyRepository.save(apiKey);

                    // Set authentication in security context
                    ApiKeyAuthentication authentication = new ApiKeyAuthentication(
                            apiKey.getOwnerId(),
                            apiKey.getName(),
                            apiKey.getScopes()
                    );

                    securityContext.setAuthentication(authentication);
                    log.debug("API key authenticated: {} (owner: {})", apiKey.getName(), apiKey.getOwnerId());
                } else {
                    log.warn("Invalid or expired API key: {}", apiKey.getKeyId());
                    sendUnauthorized(httpServletResponse, "API key is expired or disabled");
                    return;
                }
            } else {
                log.warn("Invalid API key provided");
                sendUnauthorized(httpServletResponse, "Invalid API key");
                return;
            }
        }

        // Continue filter chain
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the API key from the request headers.
     *
     * Looks in two places:
     * 1. Authorization header with "Bearer {key}" format
     * 2. X-API-Key header
     *
     * @param request The HTTP request
     * @return The API key value, or null if not found
     */
    private String extractApiKey(HttpServletRequest request) {
        // Try Authorization header first (Bearer token format)
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());
            // Check if this looks like an API key (starts with "ak_")
            if (token.startsWith("ak_")) {
                return token;
            }
        }

        // Try X-API-Key header
        return request.getHeader(API_KEY_HEADER);
    }

    /**
     * Sends an unauthorized response.
     */
    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    /**
     * Repository interface for API key lookup.
     */
    public interface ApiKeyRepository {
        Optional<ApiKey> findByKeyId(String keyId);
        Optional<ApiKey> findById(String id);
        ApiKey save(ApiKey apiKey);
    }

    /**
     * Security context for storing authentication information.
     */
    public interface SecurityContext {
        void setAuthentication(ApiKeyAuthentication authentication);
        ApiKeyAuthentication getAuthentication();
        void clearAuthentication();
    }

    /**
     * Authentication object for API key authentication.
     */
    public static class ApiKeyAuthentication {
        private final String ownerId;
        private final String keyName;
        private final java.util.Set<br.com.archflow.model.security.ApiKeyScope> scopes;

        public ApiKeyAuthentication(String ownerId, String keyName,
                                   java.util.Set<br.com.archflow.model.security.ApiKeyScope> scopes) {
            this.ownerId = ownerId;
            this.keyName = keyName;
            this.scopes = scopes;
        }

        public String getOwnerId() {
            return ownerId;
        }

        public String getKeyName() {
            return keyName;
        }

        public java.util.Set<br.com.archflow.model.security.ApiKeyScope> getScopes() {
            return scopes;
        }

        /**
         * Checks if the authentication has a specific scope.
         */
        public boolean hasScope(br.com.archflow.model.security.ApiKeyScope scope) {
            return scopes.contains(scope) || scopes.contains(br.com.archflow.model.security.ApiKeyScope.ADMIN);
        }

        /**
         * Checks if the authentication has permission for a resource and action.
         */
        public boolean hasPermission(String resource, String action) {
            if (scopes.contains(br.com.archflow.model.security.ApiKeyScope.ADMIN)) {
                return true;
            }
            String permission = resource + ":" + action;
            return scopes.stream()
                    .anyMatch(scope -> scope.asString().equals(permission));
        }
    }
}
