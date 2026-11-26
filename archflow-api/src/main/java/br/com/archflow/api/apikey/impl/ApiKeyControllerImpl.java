package br.com.archflow.api.apikey.impl;

import br.com.archflow.api.apikey.ApiKeyController;
import br.com.archflow.api.apikey.dto.ApiKeyResponse;
import br.com.archflow.api.apikey.dto.ApiKeyScopeResponse;
import br.com.archflow.api.apikey.dto.CreateApiKeyRequest;
import br.com.archflow.api.apikey.dto.CreateApiKeyResponse;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.config.ApiKeyScopeRequirement;
import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.security.apikey.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private volatile AuditTrail auditTrail = AuditTrail.noop();

    /**
     * Creates a new ApiKeyControllerImpl.
     *
     * @param apiKeyService The API key service
     */
    public ApiKeyControllerImpl(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /** Audit event producer (create/revoke) — optional, no-op default. */
    public void setAuditTrail(AuditTrail auditTrail) {
        if (auditTrail != null) {
            this.auditTrail = auditTrail;
        }
    }

    @Override
    public List<ApiKeyScopeResponse> listScopes(String userId) {
        Set<ApiKeyScope> grantable = apiKeyService.grantableScopes(userId);
        return Arrays.stream(ApiKeyScope.values())
                .map(scope -> ApiKeyScopeResponse.of(scope, grantable.contains(scope),
                        ApiKeyScopeRequirement.opensAnyEndpoint(scope)))
                .toList();
    }

    @Override
    public CreateApiKeyResponse createApiKey(String tenantId, String userId, CreateApiKeyRequest request) {
        // As anotações do DTO não são validadas por ninguém (não há @Valid no adaptador): sem estas
        // checagens, um nome vazio ou uma chave sem escopo — que não abriria endpoint nenhum — eram
        // criados calados. A mensagem volta legível pelo GlobalExceptionHandler (400).
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("API key name is required");
        }
        if (request.scopes() == null || request.scopes().isEmpty()) {
            throw new IllegalArgumentException("At least one scope is required");
        }
        Set<ApiKeyScope> scopes = resolveScopes(request.scopes());
        LocalDateTime expiresAt = parseExpiry(request.expiresAt());
        log.info("Creating API key for user: {} with name: {}", userId, request.name());

        ApiKeyService.ApiKeyWithSecret result = apiKeyService.createApiKey(
                tenantId,
                userId,
                request.name().trim(),
                scopes,
                expiresAt
        );

        br.com.archflow.model.security.ApiKey apiKey = result.apiKey();

        log.info("API key created successfully: {} for user: {}", apiKey.getKeyId(), userId);

        auditTrail.record(userId, null, AuditAction.CREATE, "apikey", apiKey.getKeyId(),
                true, null, Map.of("name", String.valueOf(request.name())));

        return new CreateApiKeyResponse(
                apiKey.getId(),
                apiKey.getKeyId(),
                result.secret(),       // Secret shown only once
                result.credential(),   // Credential shown only once
                apiKey.getName(),
                apiKey.getScopes(),
                instante(apiKey.getCreatedAt()),
                instante(apiKey.getExpiresAt()),
                instante(apiKey.getLastUsedAt()),
                apiKey.isEnabled()
        );
    }

    /**
     * A validade pedida, na hora local do servidor — como o {@code ApiKeyService} a guarda e compara
     * ({@code LocalDateTime.now()}). Uma validade que já passou é recusada (400): a chave nasceria
     * vencida, não autenticaria, e a tela a listaria como ativa (RCHFLW-195 item 12).
     */
    LocalDateTime parseExpiry(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        LocalDateTime local;
        try {
            local = LocalDateTime.ofInstant(OffsetDateTime.parse(value.trim()).toInstant(), zone());
        } catch (DateTimeParseException comFuso) {
            try {
                local = LocalDateTime.parse(value.trim());
            } catch (DateTimeParseException semFuso) {
                throw new IllegalArgumentException("Invalid expiresAt '" + value
                        + "': expected ISO-8601, e.g. 2026-12-31T23:59:00Z");
            }
        }
        if (!local.isAfter(LocalDateTime.now(zone()))) {
            throw new IllegalArgumentException("expiresAt must be in the future: " + value);
        }
        return local;
    }

    /** O fuso em que o {@code ApiKeyService} grava as datas (o do servidor). */
    private static ZoneId zone() {
        return ZoneId.systemDefault();
    }

    /**
     * A data guardada (hora local do servidor, sem fuso) como instante UTC. Saía sem fuso
     * ({@code 2026-11-26T08:46:41}) e a tela não tinha como formatá-la (RCHFLW-195 item 11).
     */
    private static Instant instante(LocalDateTime local) {
        return local == null ? null : local.atZone(zone()).toInstant();
    }

    @Override
    public List<ApiKeyResponse> listApiKeys(String tenantId, String userId) {
        log.debug("Listing API keys for user: {}", userId);

        List<br.com.archflow.model.security.ApiKey> apiKeys = apiKeyService.listApiKeys(tenantId, userId);

        return apiKeys.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ApiKeyResponse getApiKey(String tenantId, String userId, String keyId) {
        log.debug("Getting API key: {} for user: {}", keyId, userId);

        try {
            br.com.archflow.model.security.ApiKey apiKey = apiKeyService.getApiKey(tenantId, userId, keyId);
            return toResponse(apiKey);
        } catch (ApiKeyService.ApiKeyNotFoundException e) {
            log.warn("API key not found: {} for user: {}", keyId, userId);
            throw new NotFoundException(e.getMessage());
        }
    }

    @Override
    public void revokeApiKey(String tenantId, String userId, String keyId) {
        log.info("Revoking API key: {} for user: {}", keyId, userId);

        try {
            apiKeyService.revokeApiKey(tenantId, userId, keyId);
            auditTrail.record(userId, null, AuditAction.DELETE, "apikey", keyId,
                    true, null, null);
        } catch (ApiKeyService.ApiKeyNotFoundException e) {
            log.warn("API key not found: {} for user: {}", keyId, userId);
            auditTrail.record(userId, null, AuditAction.DELETE, "apikey", keyId,
                    false, "API key not found", null);
            throw new NotFoundException(e.getMessage());
        }
    }

    /**
     * Um vocabulário só: o nome da constante de {@link ApiKeyScope}, exatamente como
     * {@link #listScopes()} o publica. Nenhuma grafia alternativa é aceita ({@code workflow.read},
     * {@code workflow:read}, minúsculas) — nenhuma chave foi criada com elas, porque a criação sempre
     * falhava. O erro diz o valor recusado e os válidos.
     */
    private static Set<ApiKeyScope> resolveScopes(Set<String> names) {
        Set<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);
        for (String name : names) {
            ApiKeyScope scope = Arrays.stream(ApiKeyScope.values())
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown scope '" + name
                            + "'. Valid scopes: " + Arrays.stream(ApiKeyScope.values())
                                    .map(Enum::name).collect(Collectors.joining(", "))));
            scopes.add(scope);
        }
        return scopes;
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
                instante(apiKey.getCreatedAt()),
                instante(apiKey.getExpiresAt()),
                instante(apiKey.getLastUsedAt()),
                apiKey.isEnabled(),
                apiKey.isExpired()
        );
    }
}
