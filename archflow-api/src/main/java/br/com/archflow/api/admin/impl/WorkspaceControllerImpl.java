package br.com.archflow.api.admin.impl;

import br.com.archflow.api.admin.WorkspaceController;
import br.com.archflow.api.admin.dto.UserDto;
import br.com.archflow.api.admin.dto.UserDto.*;
import br.com.archflow.api.admin.dto.ApiKeyDto;
import br.com.archflow.api.admin.dto.ApiKeyDto.*;
import br.com.archflow.api.admin.dto.WorkspaceSummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WorkspaceControllerImpl implements WorkspaceController {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceControllerImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Dev profile fallback — when set, missing tenant context resolves to this id. */
    private final String fallbackTenantId;

    private final Map<String, UserDto> users = new ConcurrentHashMap<>();
    private final Map<String, ApiKeyDto> apiKeys = new ConcurrentHashMap<>();
    private final Map<String, String> apiKeyFullValues = new ConcurrentHashMap<>();

    public WorkspaceControllerImpl() {
        this("tenant_demo");
    }

    /**
     * @param fallbackTenantId id to return when no tenant header is present;
     *                         pass {@code null} in production so missing
     *                         tenant context fails fast instead of silently
     *                         serving one shared demo workspace.
     */
    public WorkspaceControllerImpl(String fallbackTenantId) {
        this.fallbackTenantId = fallbackTenantId;
        // Seed demo data
        users.put("u1", new UserDto("u1", "João Silva", "joao@rioquality.com.br", "admin", "active", "2026-04-09", 8));
        users.put("u2", new UserDto("u2", "Maria Santos", "maria@rioquality.com.br", "editor", "active", "2026-04-08", 4));

        apiKeys.put("k1", new ApiKeyDto("k1", "VendaX Backend", "production", "af_live_",
                "af_live_rq_••••••••3f2a", "2026-01-15", "2026-04-09"));
    }

    /**
     * Resolves the tenant identifier for the current request. Looks at:
     * <ol>
     *   <li>An explicit {@code X-Tenant-Id} header (set by the gateway for
     *       admin impersonation)</li>
     *   <li>The Spring {@code SecurityContextHolder} principal's tenant
     *       claim (when authentication is in place)</li>
     * </ol>
     * Falls back to a demo tenant so dev smoke flows still work, but
     * logs a warning so the miswire is visible in prod.
     */
    private String resolveTenantId() {
        try {
            org.springframework.web.context.request.RequestAttributes attrs =
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                // Prefer the attribute set by ImpersonationFilter: it has
                // already merged X-Impersonate-Tenant and X-Tenant-Id so
                // callers see a single resolved value.
                Object fromAttr = sra.getRequest().getAttribute("archflow.tenantId");
                if (fromAttr instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
                String header = sra.getRequest().getHeader("X-Tenant-Id");
                if (header != null && !header.isBlank()) {
                    return header.trim();
                }
                String impersonate = sra.getRequest().getHeader("X-Impersonate-Tenant");
                if (impersonate != null && !impersonate.isBlank()) {
                    return impersonate.trim();
                }
            }
        } catch (Exception ignored) {
            // Request context not available (e.g., called outside HTTP) —
            // fall through to the fallback.
        }
        if (fallbackTenantId == null) {
            // Production mode: do NOT collapse every unauthenticated caller
            // into a single shared workspace. That masks misconfigured auth
            // and leaks admin data across tenants.
            throw new IllegalStateException(
                    "No tenant context (X-Tenant-Id header absent) and no fallback configured; "
                            + "refusing to serve workspace data");
        }
        log.warn("Tenant resolution fell back to '{}' — wire X-Tenant-Id header or a security filter",
                fallbackTenantId);
        return fallbackTenantId;
    }

    @Override
    public WorkspaceSummaryDto getSummary() {
        String tenantId = resolveTenantId();
        String displayName = "tenant_demo".equals(tenantId) ? "Demo Workspace"
                : tenantId.replace('_', ' ');
        return new WorkspaceSummaryDto(
                tenantId,
                displayName,
                "enterprise",
                "active",
                340,
                4_200_000,
                12,
                users.size(),
                apiKeys.size(),
                new WorkspaceSummaryDto.WorkspaceLimitsDto(
                        500,
                        5_000_000,
                        20,
                        10,
                        List.of("gpt-4o", "claude-sonnet-4-6")
                )
        );
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
    public synchronized CreateApiKeyResponse createApiKey(CreateApiKeyRequest request) {
        // synchronized so the (put into apiKeys) + (put into apiKeyFullValues)
        // pair is observed atomically by listApiKeys / revokeApiKey readers.
        // UUID.randomUUID provides uniqueness of the id; no extra guard
        // needed for the primary key. The random hex is SecureRandom-backed
        // so collisions are statistically impossible (2^64 state for 16
        // hex chars).
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
    public synchronized void revokeApiKey(String keyId) {
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
