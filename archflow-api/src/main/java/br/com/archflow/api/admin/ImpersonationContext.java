package br.com.archflow.api.admin;

/**
 * Thread-local context for impersonation sessions.
 *
 * <p>When a superadmin sends the {@code X-Impersonate-Tenant} header,
 * the impersonation filter validates the JWT and sets the effective
 * tenant ID in this context. All downstream services use
 * {@code ImpersonationContext.getTenantId()} to resolve the tenant.
 *
 * <p>When not impersonating, the tenant ID comes from the JWT token.
 */
public final class ImpersonationContext {

    private static final ThreadLocal<String> IMPERSONATED_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<String> ACTOR_ID = new ThreadLocal<>();

    private ImpersonationContext() {}

    public static void set(String tenantId, String actorId) {
        IMPERSONATED_TENANT.set(tenantId);
        ACTOR_ID.set(actorId);
    }

    public static String getTenantId() {
        return IMPERSONATED_TENANT.get();
    }

    public static String getActorId() {
        return ACTOR_ID.get();
    }

    public static boolean isImpersonating() {
        return IMPERSONATED_TENANT.get() != null;
    }

    public static void clear() {
        IMPERSONATED_TENANT.remove();
        ACTOR_ID.remove();
    }

    /**
     * Returns the effective tenant ID — impersonated if set, otherwise from JWT.
     */
    public static String resolveEffectiveTenant(String jwtTenantId) {
        String impersonated = getTenantId();
        return impersonated != null ? impersonated : jwtTenantId;
    }
}
