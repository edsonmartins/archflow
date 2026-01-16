package br.com.archflow.api.apikey;

import br.com.archflow.api.apikey.dto.ApiKeyResponse;
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
 *   <li>POST /api/apikeys - Create a new API key</li>
 *   <li>GET /api/apikeys - List all API keys for the current user</li>
 *   <li>GET /api/apikeys/{id} - Get a specific API key</li>
 *   <li>DELETE /api/apikeys/{id} - Revoke/disable an API key</li>
 * </ul>
 */
public interface ApiKeyController {

    /**
     * Creates a new API key for the current user.
     *
     * @param userId The ID of the user creating the key
     * @param request The API key creation request
     * @return Response with the created API key (including secret)
     */
    CreateApiKeyResponse createApiKey(String userId, CreateApiKeyRequest request);

    /**
     * Lists all API keys for the current user.
     *
     * @param userId The ID of the user
     * @return List of API keys (without secrets)
     */
    List<ApiKeyResponse> listApiKeys(String userId);

    /**
     * Gets a specific API key by ID.
     *
     * @param userId The ID of the user
     * @param keyId The public key ID
     * @return The API key (without secret)
     * @throws NotFoundException if the key doesn't exist or doesn't belong to the user
     */
    ApiKeyResponse getApiKey(String userId, String keyId);

    /**
     * Revokes (disables) an API key.
     *
     * @param userId The ID of the user
     * @param keyId The public key ID
     * @throws NotFoundException if the key doesn't exist or doesn't belong to the user
     */
    void revokeApiKey(String userId, String keyId);

    /**
     * Exception thrown when a resource is not found.
     */
    class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
