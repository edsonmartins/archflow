package br.com.archflow.conversation.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowedChatMemoryProviderTest {

    @Test
    void rejectsInvalidMaxMessages() {
        assertThatThrownBy(() -> new WindowedChatMemoryProvider(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidMaxCachedSessions() {
        assertThatThrownBy(() -> new WindowedChatMemoryProvider(6, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeTtl() {
        assertThatThrownBy(() -> new WindowedChatMemoryProvider(6, 100, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameKeyReturnsSameInstance() {
        var provider = new WindowedChatMemoryProvider(6);
        ChatMemory a = provider.getOrCreate("t1", "s1");
        ChatMemory b = provider.getOrCreate("t1", "s1");
        assertThat(a).isSameAs(b);
    }

    @Test
    void differentSessionsAreIsolated() {
        var provider = new WindowedChatMemoryProvider(6);
        ChatMemory a = provider.getOrCreate("t1", "session-A");
        ChatMemory b = provider.getOrCreate("t1", "session-B");
        assertThat(a).isNotSameAs(b);

        a.add(UserMessage.from("hello A"));
        assertThat(a.messages()).hasSize(1);
        assertThat(b.messages()).isEmpty();
    }

    @Test
    void differentTenantsAreIsolated() {
        var provider = new WindowedChatMemoryProvider(6);
        ChatMemory tenantA = provider.getOrCreate("tenantA", "session-1");
        ChatMemory tenantB = provider.getOrCreate("tenantB", "session-1");
        assertThat(tenantA).isNotSameAs(tenantB);

        tenantA.add(UserMessage.from("only A"));
        assertThat(tenantB.messages()).isEmpty();
    }

    @Test
    void slidingWindowDropsOldestWhenExceeded() {
        var provider = new WindowedChatMemoryProvider(4);
        ChatMemory mem = provider.getOrCreate("t1", "s1");

        for (int i = 1; i <= 10; i++) {
            mem.add(UserMessage.from("user " + i));
            mem.add(AiMessage.from("ai " + i));
        }

        assertThat(mem.messages()).hasSizeLessThanOrEqualTo(4);
        String lastContent = mem.messages().get(mem.messages().size() - 1).toString();
        assertThat(lastContent).contains("ai 10");
    }

    @Test
    void clearRemovesSpecificSession() {
        var provider = new WindowedChatMemoryProvider(6);
        ChatMemory mem = provider.getOrCreate("t1", "s1");
        mem.add(UserMessage.from("hello"));
        provider.getOrCreate("t1", "s2");

        assertThat(provider.clear("t1", "s1")).isTrue();

        assertThat(provider.size()).isEqualTo(1);
        ChatMemory fresh = provider.getOrCreate("t1", "s1");
        assertThat(fresh.messages()).isEmpty();
    }

    @Test
    void clearReturnsFalseForUnknownSession() {
        var provider = new WindowedChatMemoryProvider(6);
        assertThat(provider.clear("t1", "ghost")).isFalse();
    }

    @Test
    void clearTenantRemovesAllSessionsForTenant() {
        var provider = new WindowedChatMemoryProvider(6);
        provider.getOrCreate("t1", "s1");
        provider.getOrCreate("t1", "s2");
        provider.getOrCreate("t1", "s3");
        provider.getOrCreate("t2", "s1");

        int removed = provider.clearTenant("t1");

        assertThat(removed).isEqualTo(3);
        assertThat(provider.size()).isEqualTo(1);
    }

    @Test
    void lruEvictsLeastRecentlyAccessedWhenCapExceeded() {
        var provider = new WindowedChatMemoryProvider(6, 3, null);

        provider.getOrCreate("t1", "s1");
        provider.getOrCreate("t1", "s2");
        provider.getOrCreate("t1", "s3");

        // Touch s1 and s2 so s3 becomes least recent
        provider.getOrCreate("t1", "s1");
        provider.getOrCreate("t1", "s2");

        // Add s4 → should evict s3
        provider.getOrCreate("t1", "s4");

        assertThat(provider.size()).isEqualTo(3);

        // Recreating s3 gives a fresh empty memory
        ChatMemory s3reborn = provider.getOrCreate("t1", "s3");
        assertThat(s3reborn.messages()).isEmpty();
    }

    @Test
    void lruKeepsCapRespectedAcrossManyInserts() {
        var provider = new WindowedChatMemoryProvider(6, 5, null);
        for (int i = 0; i < 50; i++) {
            provider.getOrCreate("t1", "s" + i);
        }
        assertThat(provider.size()).isEqualTo(5);
    }

    @Test
    void ttlExpiresIdleSessionsOnNextAccess() throws InterruptedException {
        var provider = new WindowedChatMemoryProvider(6, 100, Duration.ofMillis(50));

        provider.getOrCreate("t1", "s1").add(UserMessage.from("hello"));
        provider.getOrCreate("t1", "s2");

        Thread.sleep(100);

        // Next access expires both prior sessions before creating s3
        provider.getOrCreate("t1", "s3");

        assertThat(provider.size()).isEqualTo(1);

        // Recreating s1 gives a fresh memory
        ChatMemory fresh = provider.getOrCreate("t1", "s1");
        assertThat(fresh.messages()).isEmpty();
    }

    @Test
    void ttlDoesNotExpireRecentlyAccessedSessions() throws InterruptedException {
        var provider = new WindowedChatMemoryProvider(6, 100, Duration.ofMillis(100));

        ChatMemory s1 = provider.getOrCreate("t1", "s1");
        s1.add(UserMessage.from("keep alive"));

        Thread.sleep(40);
        provider.getOrCreate("t1", "s1"); // touch to reset lastAccess
        Thread.sleep(40);

        provider.getOrCreate("t1", "s2"); // triggers expireIdle
        assertThat(provider.size()).isEqualTo(2);
        assertThat(provider.getOrCreate("t1", "s1").messages()).hasSize(1);
    }

    @Test
    void getMaxMessagesReturnsConfiguredValue() {
        var provider = new WindowedChatMemoryProvider(8);
        assertThat(provider.getMaxMessages()).isEqualTo(8);
        assertThat(provider.getMaxCachedSessions())
                .isEqualTo(WindowedChatMemoryProvider.DEFAULT_MAX_CACHED_SESSIONS);
        assertThat(provider.getIdleTtl()).isNull();
    }
}
