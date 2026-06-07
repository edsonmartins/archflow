package br.com.archflow.conversation.governance;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementação genérica do {@link GovernanceResolver} — o esqueleto comum dos dois
 * {@code AgentGovernanceService}, com as divergências resolvidas por contrato:
 *
 * <ul>
 *   <li>ausência/erro ⇒ snapshot de <b>defaults</b> ({@code fromDatabase=false}),
 *       nunca {@code null};</li>
 *   <li>cache TTL próprio via {@link ConcurrentHashMap} (sem dependência de
 *       Caffeine) que <b>respeita</b> o TTL informado;</li>
 *   <li>(de)serialização de JSON e mapeamento de tenant ficam no
 *       {@link GovernanceProfileStore} do produto.</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class DefaultGovernanceResolver implements GovernanceResolver {

    private static final String DEFAULT_KEY = "__default__";
    private static final String PROFILE_PREFIX = "profile:";

    private final GovernanceProfileStore store;
    private final GovernanceSettings defaults;
    private final long cacheTtlSeconds;
    private final ConcurrentHashMap<String, CachedSnapshot> cache = new ConcurrentHashMap<>();

    public DefaultGovernanceResolver(GovernanceProfileStore store) {
        this(store, GovernanceSettings.defaults(), 300L);
    }

    public DefaultGovernanceResolver(GovernanceProfileStore store, GovernanceSettings defaults, long cacheTtlSeconds) {
        this.store = store != null ? store : GovernanceProfileStore.EMPTY;
        this.defaults = defaults != null ? defaults : GovernanceSettings.defaults();
        this.cacheTtlSeconds = Math.max(1L, cacheTtlSeconds);
    }

    @Override
    public GovernanceSnapshot resolve(String tenantId) {
        String key = normalize(tenantId);
        CachedSnapshot cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.snapshot();
        }
        GovernanceSnapshot snapshot = store.findActiveByTenant(tenantId)
                .filter(GovernanceProfile::active)
                .map(p -> fromProfile(p, tenantId))
                .orElseGet(() -> defaultSnapshot(tenantId));
        cache.put(key, new CachedSnapshot(snapshot, expiry()));
        return snapshot;
    }

    @Override
    public GovernanceSnapshot resolveByProfileId(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return defaultSnapshot(null);
        }
        String key = PROFILE_PREFIX + profileId;
        CachedSnapshot cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.snapshot();
        }
        GovernanceSnapshot snapshot = store.findActiveById(profileId)
                .filter(GovernanceProfile::active)
                .map(p -> fromProfile(p, p.tenantId()))
                .orElseGet(() -> defaultSnapshot(null));
        cache.put(key, new CachedSnapshot(snapshot, expiry()));
        return snapshot;
    }

    @Override
    public void invalidate(String tenantId) {
        cache.remove(normalize(tenantId));
    }

    @Override
    public void invalidateAll() {
        cache.clear();
    }

    private GovernanceSnapshot fromProfile(GovernanceProfile profile, String tenantId) {
        return new GovernanceSnapshot(
                profile.id(), tenantId, profile.settings(), true, Instant.now());
    }

    private GovernanceSnapshot defaultSnapshot(String tenantId) {
        return new GovernanceSnapshot(null, tenantId, defaults, false, Instant.now());
    }

    private static String normalize(String tenantId) {
        return (tenantId != null && !tenantId.isBlank())
                ? tenantId.toLowerCase(Locale.ROOT)
                : DEFAULT_KEY;
    }

    private Instant expiry() {
        return Instant.now().plus(Duration.ofSeconds(cacheTtlSeconds));
    }

    private record CachedSnapshot(GovernanceSnapshot snapshot, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
