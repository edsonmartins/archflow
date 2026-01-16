package br.com.archflow.api.apikey.impl;

import br.com.archflow.api.apikey.ApiKeyController;
import br.com.archflow.api.apikey.dto.ApiKeyResponse;
import br.com.archflow.api.apikey.dto.CreateApiKeyRequest;
import br.com.archflow.api.apikey.dto.CreateApiKeyResponse;
import br.com.archflow.security.apikey.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link ApiKeyController}.
 *
 * <p>This implementation uses the {@link ApiKeyService} for API key operations.
 * It can be used directly or wrapped by framework-specific adapters (Spring, etc.).</p>
 */
public class ApiKeyControllerImpl implements ApiKeyController {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyControllerImpl.class);

    private final ApiKeyService apiKeyService;

    /**
     * Creates a new ApiKeyControllerImpl.
     *
     * @param apiKeyService The API key service
     */
    public ApiKeyControllerImpl(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    public CreateApiKeyResponse createApiKey(String userId, CreateApiKeyRequest request) {
        log.info("Creating API key for user: {} with name: {}", userId, request.name());

        ApiKeyService.ApiKeyWithSecret result = apiKeyService.createApiKey(
                userId,
                request.name(),
                request.scopes(),
                request.expiresAt()
        );

        br.com.archflow.model.security.ApiKey apiKey = result.apiKey();

        log.info("API key created successfully: {} for user: {}", apiKey.getKeyId(), userId);

        return new CreateApiKeyResponse(
                apiKey.getId(),
                apiKey.getKeyId(),
                result.secret(),  // Secret shown only once
                apiKey.getName(),
                apiKey.getScopes(),
                apiKey.getCreatedAt(),
                apiKey.getExpiresAt(),
                apiKey.getLastUsedAt(),
                apiKey.isEnabled()
        );
    }

    @Override
    public List<ApiKeyResponse> listApiKeys(String userId) {
        log.debug("Listing API keys for user: {}", userId);

        List<br.com.archflow.model.security.ApiKey> apiKeys = apiKeyService.listApiKeys(userId);

        return apiKeys.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ApiKeyResponse getApiKey(String userId, String keyId) {
        log.debug("Getting API key: {} for user: {}", keyId, userId);

        try {
            br.com.archflow.model.security.ApiKey apiKey = apiKeyService.getApiKey(keyId, userId);
            return toResponse(apiKey);
        } catch (ApiKeyService.ApiKeyNotFoundException e) {
            log.warn("API key not found: {} for user: {}", keyId, userId);
            throw new NotFoundException(e.getMessage());
        }
    }

    @Override
    public void revokeApiKey(String userId, String keyId) {
        log.info("Revoking API key: {} for user: {}", keyId, userId);

        try {
            apiKeyService.revokeApiKey(keyId, userId);
        } catch (ApiKeyService.ApiKeyNotFoundException e) {
            log.warn("API key not found: {} for user: {}", keyId, userId);
            throw new NotFoundException(e.getMessage());
        }
    }

    /**
     * Converts an ApiKey entity to an ApiKeyResponse DTO.
     */
    private ApiKeyResponse toResponse(br.com.archflow.model.security.ApiKey apiKey) {
        return new ApiKeyResponse(
                apiKey.getId(),
                apiKey.getKeyId(),
                apiKey.getName(),
                apiKey.getScopes(),
                apiKey.getCreatedAt(),
                apiKey.getExpiresAt(),
                apiKey.getLastUsedAt(),
                apiKey.isEnabled()
        );
    }
}
