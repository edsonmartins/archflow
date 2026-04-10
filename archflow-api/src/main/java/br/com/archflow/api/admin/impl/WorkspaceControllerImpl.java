package br.com.archflow.api.admin.impl;

import br.com.archflow.api.admin.WorkspaceController;
import br.com.archflow.api.admin.dto.UserDto;
import br.com.archflow.api.admin.dto.UserDto.*;
import br.com.archflow.api.admin.dto.ApiKeyDto;
import br.com.archflow.api.admin.dto.ApiKeyDto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WorkspaceControllerImpl implements WorkspaceController {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceControllerImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, UserDto> users = new ConcurrentHashMap<>();
    private final Map<String, ApiKeyDto> apiKeys = new ConcurrentHashMap<>();
    private final Map<String, String> apiKeyFullValues = new ConcurrentHashMap<>();

    public WorkspaceControllerImpl() {
        // Seed demo data
        users.put("u1", new UserDto("u1", "João Silva", "joao@rioquality.com.br", "admin", "active", "2026-04-09", 8));
        users.put("u2", new UserDto("u2", "Maria Santos", "maria@rioquality.com.br", "editor", "active", "2026-04-08", 4));

        apiKeys.put("k1", new ApiKeyDto("k1", "VendaX Backend", "production", "af_live_",
                "af_live_rq_••••••••3f2a", "2026-01-15", "2026-04-09"));
    }

    // ── Users ───────────────────────────────────────────────────────

    @Override
    public List<UserDto> listUsers() {
        return new ArrayList<>(users.values());
    }

    @Override
    public UserDto inviteUser(InviteUserRequest request) {
        log.info("Inviting user: {} as {}", request.email(), request.role());
        String id = "u-" + UUID.randomUUID().toString().substring(0, 8);
        var user = new UserDto(id, request.email().split("@")[0], request.email(),
                request.role(), "invited", null, 0);
        users.put(id, user);
        return user;
    }

    @Override
    public UserDto updateUserRole(String userId, UpdateRoleRequest request) {
        UserDto existing = users.get(userId);
        if (existing == null) throw new IllegalArgumentException("User not found: " + userId);
        var updated = new UserDto(existing.id(), existing.name(), existing.email(),
                request.role(), existing.status(), existing.lastAccessAt(), existing.workflowCount());
        users.put(userId, updated);
        return updated;
    }

    @Override
    public void removeUser(String userId) {
        log.info("Removing user: {}", userId);
        users.remove(userId);
    }

    @Override
    public void revokeInvite(String userId) {
        log.info("Revoking invite for user: {}", userId);
        UserDto existing = users.get(userId);
        if (existing != null && "invited".equals(existing.status())) {
            users.remove(userId);
        }
    }

    // ── API Keys ────────────────────────────────────────────────────

    private static final Map<String, String> KEY_PREFIXES = Map.of(
            "production", "af_live_",
            "staging", "af_test_",
            "web_component", "af_pub_"
    );

    @Override
    public List<ApiKeyDto> listApiKeys() {
        return new ArrayList<>(apiKeys.values());
    }

    @Override
    public CreateApiKeyResponse createApiKey(CreateApiKeyRequest request) {
        log.info("Creating API key: {} ({})", request.name(), request.type());
        String id = "k-" + UUID.randomUUID().toString().substring(0, 8);
        String prefix = KEY_PREFIXES.getOrDefault(request.type(), "af_key_");
        String randomPart = generateRandomHex(16);
        String fullKey = prefix + randomPart;
        String maskedKey = prefix + "••••••••" + randomPart.substring(randomPart.length() - 4);

        var key = new ApiKeyDto(id, request.name(), request.type(), prefix,
                maskedKey, Instant.now().toString().substring(0, 10), null);
        apiKeys.put(id, key);
        apiKeyFullValues.put(id, fullKey);

        return new CreateApiKeyResponse(id, request.name(), request.type(), prefix,
                maskedKey, fullKey, key.createdAt());
    }

    @Override
    public void revokeApiKey(String keyId) {
        log.info("Revoking API key: {}", keyId);
        apiKeys.remove(keyId);
        apiKeyFullValues.remove(keyId);
    }

    private String generateRandomHex(int length) {
        byte[] bytes = new byte[length / 2];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
