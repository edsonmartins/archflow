package br.com.archflow.conversation.governance;

import br.com.archflow.model.config.LLMConfigPatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultGovernanceResolver")
class DefaultGovernanceResolverTest {

    private GovernanceProfile profile(String tenant, LLMConfigPatch patch) {
        GovernanceSettings settings = new GovernanceSettings(true, "custom prompt",
                new LLMSettings(patch, "tenant-key", 3), null, null, 2L, Instant.now(), "admin", null);
        return new GovernanceProfile("p-" + tenant, tenant, true, settings,
                Instant.now(), "admin", Instant.now(), "admin");
    }

    @Test
    @DisplayName("resolves the tenant profile from the store (fromDatabase=true)")
    void resolvesFromStore() {
        GovernanceProfileStore store = new GovernanceProfileStore() {
            @Override public Optional<GovernanceProfile> findActiveByTenant(String t) {
                return Optional.of(profile(t, LLMConfigPatch.builder().model("m1").maxTokens(999).build()));
            }
            @Override public Optional<GovernanceProfile> findActiveById(String id) { return Optional.empty(); }
        };
        var resolver = new DefaultGovernanceResolver(store);

        GovernanceSnapshot snap = resolver.resolve("acme");

        assertThat(snap.fromDatabase()).isTrue();
        assertThat(snap.tenantId()).isEqualTo("acme");
        assertThat(snap.llmPatch().model()).contains("m1");
        assertThat(snap.llmPatch().maxTokens().getAsInt()).isEqualTo(999);
    }

    @Test
    @DisplayName("falls back to defaults (never null) when no profile exists")
    void fallsBackToDefaults() {
        var resolver = new DefaultGovernanceResolver(GovernanceProfileStore.EMPTY);

        GovernanceSnapshot snap = resolver.resolve("unknown");

        assertThat(snap).isNotNull();
        assertThat(snap.fromDatabase()).isFalse();
        assertThat(snap.settings().agentEnabled()).isTrue();
        assertThat(snap.llmPatch().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("blank tenant resolves defaults without hitting the store")
    void blankTenant() {
        var resolver = new DefaultGovernanceResolver(GovernanceProfileStore.EMPTY);
        assertThat(resolver.resolve("   ").fromDatabase()).isFalse();
    }

    @Test
    @DisplayName("caches per tenant; store is hit once until invalidated")
    void caches() {
        AtomicInteger hits = new AtomicInteger();
        GovernanceProfileStore store = new GovernanceProfileStore() {
            @Override public Optional<GovernanceProfile> findActiveByTenant(String t) {
                hits.incrementAndGet();
                return Optional.of(profile(t, LLMConfigPatch.empty()));
            }
            @Override public Optional<GovernanceProfile> findActiveById(String id) { return Optional.empty(); }
        };
        var resolver = new DefaultGovernanceResolver(store);

        resolver.resolve("acme");
        resolver.resolve("acme");
        assertThat(hits.get()).isEqualTo(1);

        resolver.invalidate("acme");
        resolver.resolve("acme");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("resolveByProfileId resolves by id and falls back to defaults")
    void byProfileId() {
        GovernanceProfileStore store = new GovernanceProfileStore() {
            @Override public Optional<GovernanceProfile> findActiveByTenant(String t) { return Optional.empty(); }
            @Override public Optional<GovernanceProfile> findActiveById(String id) {
                return "p-x".equals(id) ? Optional.of(profile("x", LLMConfigPatch.empty())) : Optional.empty();
            }
        };
        var resolver = new DefaultGovernanceResolver(store);

        assertThat(resolver.resolveByProfileId("p-x").fromDatabase()).isTrue();
        assertThat(resolver.resolveByProfileId("missing").fromDatabase()).isFalse();
        assertThat(resolver.resolveByProfileId(null).fromDatabase()).isFalse();
    }

    @Test
    @DisplayName("inactive profile is ignored → defaults")
    void inactiveIgnored() {
        GovernanceProfileStore store = new GovernanceProfileStore() {
            @Override public Optional<GovernanceProfile> findActiveByTenant(String t) {
                GovernanceProfile p = profile(t, LLMConfigPatch.empty());
                return Optional.of(new GovernanceProfile(p.id(), p.tenantId(), false, p.settings(),
                        null, null, null, null));   // active=false
            }
            @Override public Optional<GovernanceProfile> findActiveById(String id) { return Optional.empty(); }
        };
        var resolver = new DefaultGovernanceResolver(store);

        assertThat(resolver.resolve("acme").fromDatabase()).isFalse();
    }
}
