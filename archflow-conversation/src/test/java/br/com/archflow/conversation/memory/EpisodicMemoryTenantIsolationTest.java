package br.com.archflow.conversation.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("InMemoryEpisodicMemory — Tenant Isolation")
class EpisodicMemoryTenantIsolationTest {

    private InMemoryEpisodicMemory memory;

    @BeforeEach
    void setUp() {
        memory = new InMemoryEpisodicMemory();
    }

    @Test
    @DisplayName("store with tenantId should isolate from other tenants")
    void storeShouldIsolateByTenant() {
        var epA = Episode.of("tenant-A", "ctx-1", "Data for A", Episode.EpisodeType.INTERACTION, 0.5);
        var epB = Episode.of("tenant-B", "ctx-1", "Data for B", Episode.EpisodeType.INTERACTION, 0.5);

        memory.store("tenant-A", epA);
        memory.store("tenant-B", epB);

        var resultsA = memory.getByContext("tenant-A", "ctx-1");
        var resultsB = memory.getByContext("tenant-B", "ctx-1");

        assertThat(resultsA).hasSize(1);
        assertThat(resultsA.get(0).content()).isEqualTo("Data for A");

        assertThat(resultsB).hasSize(1);
        assertThat(resultsB.get(0).content()).isEqualTo("Data for B");
    }

    @Test
    @DisplayName("recall with tenantId should only return episodes of that tenant")
    void recallShouldFilterByTenant() {
        memory.store("tenant-A", Episode.of("tenant-A", "ctx", "order payment issue", Episode.EpisodeType.INTERACTION, 0.8));
        memory.store("tenant-B", Episode.of("tenant-B", "ctx", "order payment problem", Episode.EpisodeType.INTERACTION, 0.8));

        var resultsA = memory.recall("tenant-A", "payment", "ctx", 10);
        var resultsB = memory.recall("tenant-B", "payment", "ctx", 10);

        assertThat(resultsA).hasSize(1);
        assertThat(resultsA.get(0).episode().tenantId()).isEqualTo("tenant-A");

        assertThat(resultsB).hasSize(1);
        assertThat(resultsB.get(0).episode().tenantId()).isEqualTo("tenant-B");
    }

    @Test
    @DisplayName("clear with tenantId should not affect other tenants")
    void clearShouldNotAffectOtherTenants() {
        memory.store("tenant-A", Episode.of("tenant-A", "ctx", "data A", Episode.EpisodeType.INTERACTION, 0.5));
        memory.store("tenant-B", Episode.of("tenant-B", "ctx", "data B", Episode.EpisodeType.INTERACTION, 0.5));

        memory.clear("tenant-A", "ctx");

        assertThat(memory.getByContext("tenant-A", "ctx")).isEmpty();
        assertThat(memory.getByContext("tenant-B", "ctx")).hasSize(1);
    }

    @Test
    @DisplayName("Episode.of with 3 args should default to SYSTEM tenant")
    void episodeOfShouldDefaultToSystem() {
        var ep = Episode.of("ctx", "content", 0.5);
        assertThat(ep.tenantId()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("Episode.of with tenantId should set tenant correctly")
    void episodeOfWithTenantId() {
        var ep = Episode.of("my-tenant", "ctx", "content", Episode.EpisodeType.ACTION, 0.7);
        assertThat(ep.tenantId()).isEqualTo("my-tenant");
        assertThat(ep.type()).isEqualTo(Episode.EpisodeType.ACTION);
    }

    @Test
    @DisplayName("Episode with null tenantId should default to SYSTEM")
    void episodeNullTenantShouldDefault() {
        var ep = new Episode(null, null, "ctx", "content", null, null, 0.5, null, null);
        assertThat(ep.tenantId()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("getByContext without tenant should use SYSTEM")
    void getByContextDefaultTenant() {
        memory.store(Episode.of("ctx", "data", 0.5)); // SYSTEM default

        var results = memory.getByContext("ctx"); // delegates to SYSTEM
        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("size should count all episodes across tenants")
    void sizeShouldCountAll() {
        memory.store("t1", Episode.of("t1", "ctx", "a", Episode.EpisodeType.INTERACTION, 0.5));
        memory.store("t2", Episode.of("t2", "ctx", "b", Episode.EpisodeType.INTERACTION, 0.5));

        assertThat(memory.size()).isEqualTo(2);
    }
}
