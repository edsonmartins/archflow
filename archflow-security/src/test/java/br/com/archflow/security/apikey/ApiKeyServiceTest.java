package br.com.archflow.security.apikey;

import br.com.archflow.model.security.ApiKey;
import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.security.auth.InMemoryUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiKeyService.
 *
 * <p>Uses a simple in-memory {@link ApiKeyService.ApiKeyRepository} stub so the
 * service can be exercised without any framework dependencies.</p>
 */
class ApiKeyServiceTest {

    private static final String TENANT = "tenant-a";

    private InMemoryApiKeyRepository repository;
    private InMemoryUserRepository users;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryApiKeyRepository();
        users = new InMemoryUserRepository();
        // Os donos dos testes existem e são admins: a chave age como o dono, e o serviço o relê.
        for (String id : List.of("owner-1", "owner-5", "owner-6", "owner-7", "owner-8", "owner-9", "owner-42",
                "owner-A", "owner-B")) {
            user(id, Role.createAdminRole());
        }
        service = new ApiKeyService(repository, users);
    }

    private User user(String id, Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(id);
        user.setEmail(id + "@example.com");
        user.setEnabled(true);
        user.addRole(role);
        return users.save(user);
    }

    // ========== countActiveKeys ==========

    @Test
    void countActiveKeys_countsEveryOwnerOfTheTenantButNotRevokedExpiredOrOtherTenants() {
        service.createApiKey(TENANT, "owner-1", "a", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        service.createApiKey(TENANT, "owner-5", "b", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        var revoked = service.createApiKey(TENANT, "owner-6", "c", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        service.revokeApiKey(TENANT, "owner-6", revoked.apiKey().getKeyId());
        service.createApiKey(TENANT, "owner-7", "d", Set.of(ApiKeyScope.WORKFLOW_READ),
                LocalDateTime.now().minusMinutes(1));
        service.createApiKey("tenant-b", "owner-8", "e", Set.of(ApiKeyScope.WORKFLOW_READ), null);

        assertEquals(2, service.countActiveKeys(TENANT));
        assertEquals(0, service.countActiveKeys("tenant-sem-chaves"));
    }

    /**
     * Chave de dono removido ou desativado não autentica (401) e não aparece em tela de chaves nenhuma:
     * contá-la como ativa no resumo do workspace dizia que havia acesso onde não há.
     */
    @Test
    void countActiveKeys_ignoresKeysWhoseOwnerWasRemovedOrDisabled() {
        service.createApiKey(TENANT, "owner-1", "fica", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        service.createApiKey(TENANT, "owner-5", "removido-1", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        service.createApiKey(TENANT, "owner-5", "removido-2", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        service.createApiKey(TENANT, "owner-6", "desativado", Set.of(ApiKeyScope.WORKFLOW_READ), null);

        users.delete(users.findById("owner-5").orElseThrow());
        users.findById("owner-6").orElseThrow().setEnabled(false);

        assertEquals(1, service.countActiveKeys(TENANT));
    }

    // ========== createApiKey ==========

    @Test
    void createApiKey_returnsApiKeyWithSecretWithAkPrefix() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                TENANT, "owner-1", "My Key", Set.of(ApiKeyScope.WORKFLOW_READ), null);

        assertNotNull(result);
        String keyId = result.apiKey().getKeyId();
        assertTrue(keyId.startsWith("ak_"),
                "keyId should start with 'ak_' but was: " + keyId);
    }

    @Test
    void createApiKey_secretIsNonNull() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                TENANT, "owner-1", "My Key", Set.of(ApiKeyScope.WORKFLOW_READ), null);

        assertNotNull(result.secret());
        assertFalse(result.secret().isBlank());
    }

    @Test
    void createApiKey_storesKeyInRepository() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                TENANT, "owner-1", "Stored Key", Set.of(ApiKeyScope.WORKFLOW_READ), null);

        String keyId = result.apiKey().getKeyId();
        Optional<ApiKey> stored = repository.findByKeyId(keyId);
        assertTrue(stored.isPresent());
    }

    @Test
    void createApiKey_storedKeyHasCorrectOwnerAndName() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                TENANT, "owner-42", "Integration Key", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null);

        ApiKey stored = repository.findByKeyId(result.apiKey().getKeyId()).orElseThrow();
        assertEquals("owner-42", stored.getOwnerId());
        assertEquals("Integration Key", stored.getName());
    }

    @Test
    void createApiKey_storedKeyIsEnabled() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                TENANT, "owner-1", "Active Key", Set.of(ApiKeyScope.AGENT_READ), null);

        ApiKey stored = repository.findByKeyId(result.apiKey().getKeyId()).orElseThrow();
        assertTrue(stored.isEnabled());
    }

    @Test
    void createApiKey_differentCallsProduceDifferentSecrets() {
        ApiKeyService.ApiKeyWithSecret first = service.createApiKey(
                TENANT, "owner-1", "Key A", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        ApiKeyService.ApiKeyWithSecret second = service.createApiKey(
                TENANT, "owner-1", "Key B", Set.of(ApiKeyScope.WORKFLOW_READ), null);

        assertNotEquals(first.secret(), second.secret());
        assertNotEquals(first.apiKey().getKeyId(), second.apiKey().getKeyId());
    }

    // ========== listApiKeys ==========

    @Test
    void listApiKeys_returnsKeysForOwner() {
        service.createApiKey(TENANT, "owner-A", "Key 1", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        service.createApiKey(TENANT, "owner-A", "Key 2", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null);
        service.createApiKey(TENANT, "owner-B", "Key 3", Set.of(ApiKeyScope.AGENT_READ), null);

        List<ApiKey> ownerAKeys = service.listApiKeys(TENANT, "owner-A");

        assertEquals(2, ownerAKeys.size());
        ownerAKeys.forEach(k -> assertEquals("owner-A", k.getOwnerId()));
    }

    @Test
    void listApiKeys_returnsEmptyListForUnknownOwner() {
        List<ApiKey> keys = service.listApiKeys(TENANT, "owner-unknown");

        assertNotNull(keys);
        assertTrue(keys.isEmpty());
    }

    // ========== getApiKey ==========

    @Test
    void getApiKey_returnsKeyForCorrectOwner() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                TENANT, "owner-5", "My Key", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        String keyId = result.apiKey().getKeyId();

        ApiKey found = service.getApiKey(TENANT, "owner-5", keyId);

        assertNotNull(found);
        assertEquals(keyId, found.getKeyId());
    }

    @Test
    void getApiKey_throwsForWrongOwner() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                TENANT, "owner-5", "My Key", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        String keyId = result.apiKey().getKeyId();

        assertThrows(ApiKeyService.ApiKeyNotFoundException.class,
                () -> service.getApiKey(TENANT, "attacker", keyId));
    }

    @Test
    void getApiKey_throwsForNonExistentKey() {
        assertThrows(ApiKeyService.ApiKeyNotFoundException.class,
                () -> service.getApiKey(TENANT, "owner-5", "ak_doesnotexist"));
    }

    // ========== revokeApiKey ==========

    @Test
    void revokeApiKey_disablesTheKey() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                TENANT, "owner-6", "Revoke Me", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        String keyId = result.apiKey().getKeyId();

        service.revokeApiKey(TENANT, "owner-6", keyId);

        ApiKey revoked = repository.findByKeyId(keyId).orElseThrow();
        assertFalse(revoked.isEnabled());
    }

    @Test
    void revokeApiKey_throwsForWrongOwner() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                TENANT, "owner-6", "Revoke Me", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        String keyId = result.apiKey().getKeyId();

        assertThrows(ApiKeyService.ApiKeyNotFoundException.class,
                () -> service.revokeApiKey(TENANT, "intruder", keyId));
    }

    // ========== validateApiKey ==========

    @Test
    void validateApiKey_succeedsWithCorrectSecret() {
        ApiKeyService.ApiKeyWithSecret created = service.createApiKey(
                TENANT, "owner-7", "Valid Key", Set.of(ApiKeyScope.EXECUTION_READ), null);

        ApiKey validated = service.validateApiKey(
                created.apiKey().getKeyId(), created.secret());

        assertNotNull(validated);
        assertEquals(created.apiKey().getKeyId(), validated.getKeyId());
    }

    @Test
    void validateApiKey_throwsForWrongSecret() {
        ApiKeyService.ApiKeyWithSecret created = service.createApiKey(
                TENANT, "owner-7", "Valid Key", Set.of(ApiKeyScope.EXECUTION_READ), null);

        assertThrows(ApiKeyService.ApiKeyNotFoundException.class,
                () -> service.validateApiKey(created.apiKey().getKeyId(), "wrong-secret"));
    }

    @Test
    void validateApiKey_throwsForNonExistentKey() {
        assertThrows(ApiKeyService.ApiKeyNotFoundException.class,
                () -> service.validateApiKey("ak_ghost", "any-secret"));
    }

    // ========== tenant (RCHFLW-86) ==========

    @Test
    void createApiKey_storesTheTenant() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                TENANT, "owner-1", "Tenant Key", Set.of(ApiKeyScope.WORKFLOW_READ), null);

        assertEquals(TENANT, repository.findByKeyId(result.apiKey().getKeyId()).orElseThrow().getTenantId());
    }

    @Test
    void createApiKey_requiresTenantAndOwner() {
        assertThrows(IllegalArgumentException.class, () -> service.createApiKey(
                null, "owner-1", "k", Set.of(ApiKeyScope.WORKFLOW_READ), null));
        assertThrows(IllegalArgumentException.class, () -> service.createApiKey(
                TENANT, " ", "k", Set.of(ApiKeyScope.WORKFLOW_READ), null));
    }

    @Test
    void sameOwnerInAnotherTenant_doesNotSeeListOrRevokeTheKey() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                TENANT, "owner-1", "Tenant A key", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        String keyId = result.apiKey().getKeyId();

        assertTrue(service.listApiKeys("tenant-b", "owner-1").isEmpty());
        assertThrows(ApiKeyService.ApiKeyNotFoundException.class,
                () -> service.getApiKey("tenant-b", "owner-1", keyId));
        assertThrows(ApiKeyService.ApiKeyNotFoundException.class,
                () -> service.revokeApiKey("tenant-b", "owner-1", keyId));
        assertTrue(repository.findByKeyId(keyId).orElseThrow().isEnabled());
    }

    // ========== authenticate (RCHFLW-84) ==========

    @Test
    void authenticate_acceptsTheCredentialReturnedAtCreation() {
        ApiKeyService.ApiKeyWithSecret created = service.createApiKey(
                TENANT, "owner-9", "CI", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null);

        ApiKey key = service.authenticate(created.credential()).apiKey();

        assertEquals("owner-9", key.getOwnerId());
        assertEquals(TENANT, key.getTenantId());
        assertEquals(Set.of(ApiKeyScope.WORKFLOW_EXECUTE), key.getScopes());
        assertNotNull(repository.findByKeyId(key.getKeyId()).orElseThrow().getLastUsedAt());
    }

    @Test
    void credential_isTheKeyIdAndTheSecret_andLooksLikeAnApiKey() {
        ApiKeyService.ApiKeyWithSecret created = service.createApiKey(
                TENANT, "owner-9", "CI", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null);

        assertEquals(created.apiKey().getKeyId() + "." + created.secret(), created.credential());
        assertTrue(ApiKeyService.isApiKeyCredential(created.credential()));
        assertFalse(ApiKeyService.isApiKeyCredential("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig"));
    }

    @Test
    void authenticate_rejectsWrongSecretUnknownAndMalformed() {
        ApiKeyService.ApiKeyWithSecret created = service.createApiKey(
                TENANT, "owner-9", "CI", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null);

        for (String bad : List.of(created.apiKey().getKeyId() + ".wrong", "ak_ghost.secret",
                created.apiKey().getKeyId(), created.secret(), "ak_.x", created.apiKey().getKeyId() + ".")) {
            ApiKeyService.InvalidApiKeyException e = assertThrows(
                    ApiKeyService.InvalidApiKeyException.class, () -> service.authenticate(bad), bad);
            assertEquals(ApiKeyService.InvalidApiKeyException.Reason.INVALID, e.reason());
        }
    }

    @Test
    void authenticate_rejectsARevokedKey() {
        ApiKeyService.ApiKeyWithSecret created = service.createApiKey(
                TENANT, "owner-9", "CI", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null);
        service.revokeApiKey(TENANT, "owner-9", created.apiKey().getKeyId());

        ApiKeyService.InvalidApiKeyException e = assertThrows(
                ApiKeyService.InvalidApiKeyException.class, () -> service.authenticate(created.credential()));
        assertEquals(ApiKeyService.InvalidApiKeyException.Reason.REVOKED, e.reason());
    }

    @Test
    void authenticate_rejectsAnExpiredKey() {
        ApiKeyService.ApiKeyWithSecret created = service.createApiKey(
                TENANT, "owner-9", "CI", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), LocalDateTime.now().minusMinutes(1));

        ApiKeyService.InvalidApiKeyException e = assertThrows(
                ApiKeyService.InvalidApiKeyException.class, () -> service.authenticate(created.credential()));
        assertEquals(ApiKeyService.InvalidApiKeyException.Reason.EXPIRED, e.reason());
    }

    // ========== dono: existe, está ativo e o papel limita a chave ==========

    @Test
    void authenticate_rejectsAKeyWhoseOwnerWasDisabled() {
        ApiKeyService.ApiKeyWithSecret created = service.createApiKey(
                TENANT, "owner-9", "CI", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null);
        users.findById("owner-9").orElseThrow().setEnabled(false);

        ApiKeyService.InvalidApiKeyException e = assertThrows(
                ApiKeyService.InvalidApiKeyException.class, () -> service.authenticate(created.credential()));
        assertEquals(ApiKeyService.InvalidApiKeyException.Reason.OWNER_INACTIVE, e.reason());
        assertNull(repository.findByKeyId(created.apiKey().getKeyId()).orElseThrow().getLastUsedAt());
    }

    @Test
    void authenticate_rejectsAKeyWhoseOwnerWasRemoved() {
        ApiKeyService.ApiKeyWithSecret created = service.createApiKey(
                TENANT, "owner-9", "CI", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null);
        users.delete(users.findById("owner-9").orElseThrow());

        ApiKeyService.InvalidApiKeyException e = assertThrows(
                ApiKeyService.InvalidApiKeyException.class, () -> service.authenticate(created.credential()));
        assertEquals(ApiKeyService.InvalidApiKeyException.Reason.OWNER_INACTIVE, e.reason());
    }

    @Test
    void createApiKey_rejectsScopesBeyondTheOwnersRole() {
        user("viewer-1", Role.createViewerRole());

        ApiKeyService.ApiKeyForbiddenException e = assertThrows(ApiKeyService.ApiKeyForbiddenException.class,
                () -> service.createApiKey(TENANT, "viewer-1", "k",
                        Set.of(ApiKeyScope.WORKFLOW_READ, ApiKeyScope.WORKFLOW_DELETE, ApiKeyScope.ADMIN), null));
        assertTrue(e.getMessage().contains("WORKFLOW_DELETE"), e.getMessage());
        assertTrue(e.getMessage().contains("ADMIN"), e.getMessage());
        assertFalse(e.getMessage().contains("WORKFLOW_READ"), e.getMessage());
        assertTrue(service.listApiKeys(TENANT, "viewer-1").isEmpty());

        assertNotNull(service.createApiKey(TENANT, "viewer-1", "k", Set.of(ApiKeyScope.WORKFLOW_READ), null));
    }

    @Test
    void createApiKey_rejectsAnOwnerThatDoesNotExistOrIsDisabled() {
        assertThrows(ApiKeyService.ApiKeyForbiddenException.class, () -> service.createApiKey(
                TENANT, "ghost", "k", Set.of(ApiKeyScope.WORKFLOW_READ), null));
        users.findById("owner-1").orElseThrow().setEnabled(false);
        assertThrows(ApiKeyService.ApiKeyForbiddenException.class, () -> service.createApiKey(
                TENANT, "owner-1", "k", Set.of(ApiKeyScope.WORKFLOW_READ), null));
    }

    @Test
    void grantableScopes_followTheRole() {
        user("viewer-1", Role.createViewerRole());

        assertEquals(Set.of(ApiKeyScope.WORKFLOW_READ, ApiKeyScope.AGENT_READ, ApiKeyScope.EXECUTION_READ),
                service.grantableScopes("viewer-1"));
        assertEquals(Set.of(ApiKeyScope.values()), service.grantableScopes("owner-1"));
        assertTrue(service.grantableScopes("ghost").isEmpty());
    }

    @Test
    void ownerPermits_followsTheCurrentRole_notTheOneAtCreation() {
        User designer = user("designer-1", Role.createDesignerRole());
        ApiKeyService.ApiKeyWithSecret created = service.createApiKey(
                TENANT, "designer-1", "CI", Set.of(ApiKeyScope.WORKFLOW_DELETE), null);
        assertTrue(service.authenticate(created.credential()).ownerPermits(ApiKeyScope.WORKFLOW_DELETE));

        designer.getRoles().clear();
        designer.addRole(Role.createViewerRole());

        ApiKeyService.AuthenticatedApiKey now = service.authenticate(created.credential());
        assertFalse(now.ownerPermits(ApiKeyScope.WORKFLOW_DELETE));
        assertTrue(now.ownerPermits(ApiKeyScope.WORKFLOW_READ));
    }

    // ========== ApiKeyWithSecret accessors ==========

    @Test
    void apiKeyWithSecret_bothAccessorStylesReturnSameValues() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                TENANT, "owner-8", "Accessor Test", Set.of(ApiKeyScope.METRICS_READ), null);

        assertSame(result.apiKey(), result.getApiKey());
        assertEquals(result.secret(), result.getSecret());
    }

    // ========== Minimal in-memory stub ==========

    /**
     * Simple in-memory implementation of {@link ApiKeyService.ApiKeyRepository}
     * used exclusively for testing — no production logic, no framework.
     */
    private static class InMemoryApiKeyRepository implements ApiKeyService.ApiKeyRepository {

        private final Map<String, ApiKey> store = new HashMap<>();

        @Override
        public ApiKey save(ApiKey apiKey) {
            store.put(apiKey.getKeyId(), apiKey);
            return apiKey;
        }

        @Override
        public List<ApiKey> findByOwnerId(String ownerId) {
            List<ApiKey> result = new ArrayList<>();
            for (ApiKey k : store.values()) {
                if (ownerId.equals(k.getOwnerId())) {
                    result.add(k);
                }
            }
            return result;
        }

        @Override
        public List<ApiKey> findByTenantId(String tenantId) {
            return store.values().stream().filter(k -> tenantId.equals(k.getTenantId())).toList();
        }

        @Override
        public Optional<ApiKey> findByKeyId(String keyId) {
            return Optional.ofNullable(store.get(keyId));
        }

        @Override
        public Optional<ApiKey> findById(String id) {
            return store.values().stream()
                    .filter(k -> id.equals(k.getId()))
                    .findFirst();
        }

        @Override
        public void delete(ApiKey apiKey) {
            store.remove(apiKey.getKeyId());
        }

        @Override
        public void markUsed(String id, LocalDateTime usedAt) {
            findById(id).ifPresent(k -> k.setLastUsedAt(usedAt));
        }
    }
}
