package br.com.archflow.api.apikey;

import br.com.archflow.api.apikey.dto.ApiKeyResponse;
import br.com.archflow.api.apikey.dto.ApiKeyScopeResponse;
import br.com.archflow.api.apikey.dto.CreateApiKeyRequest;
import br.com.archflow.api.apikey.dto.CreateApiKeyResponse;

import java.util.List;

/**
 * REST controller for API key management.
 *
 * <p>This interface defines the contract for API key endpoints.
 * Implementations can be provided for different web frameworks (Spring Boot, JAX-RS, etc.).</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /api/apikeys/scopes - The scopes a key can be granted</li>
 *   <li>POST /api/apikeys - Create a new API key</li>
 *   <li>GET /api/apikeys - List all API keys for the current user</li>
 *   <li>GET /api/apikeys/{keyId} - Get a specific API key</li>
 *   <li>DELETE /api/apikeys/{keyId} - Revoke/disable an API key</li>
 * </ul>
 *
 * <p>{@code tenantId} e {@code userId} são sempre os do principal autenticado, resolvidos pelo
 * adaptador web — nunca um valor que o cliente envia (RCHFLW-86).</p>
 */
public interface ApiKeyController {

    /**
     * The scopes a key can be granted — the only vocabulary {@link #createApiKey} accepts — each
     * marked with whether this user's role grants it and whether it opens any endpoint yet.
     *
     * @param userId The ID of the authenticated user
     */
    List<ApiKeyScopeResponse> listScopes(String userId);

    /**
     * Creates a new API key for the current user.
     *
     * @param tenantId The tenant of the authenticated user
     * @param userId The ID of the authenticated user
     * @param request The API key creation request
     * @return Response with the created API key (including secret)
     */
    CreateApiKeyResponse createApiKey(String tenantId, String userId, CreateApiKeyRequest request);

    /**
     * Lists all API keys for the current user.
     *
     * @param tenantId The tenant of the authenticated user
     * @param userId The ID of the authenticated user
     * @return List of API keys (without secrets)
     */
    List<ApiKeyResponse> listApiKeys(String tenantId, String userId);

    /**
     * Gets a specific API key by ID.
     *
     * @param tenantId The tenant of the authenticated user
     * @param userId The ID of the authenticated user
     * @param keyId The public key ID
     * @return The API key (without secret)
     * @throws NotFoundException if the key doesn't exist or doesn't belong to the user
     */
    ApiKeyResponse getApiKey(String tenantId, String userId, String keyId);

    /**
     * Revokes (disables) an API key.
     *
     * @param tenantId The tenant of the authenticated user
     * @param userId The ID of the authenticated user
     * @param keyId The public key ID
     * @throws NotFoundException if the key doesn't exist or doesn't belong to the user
     */
    void revokeApiKey(String tenantId, String userId, String keyId);

    /**
     * Exception thrown when a resource is not found.
     */
    class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
