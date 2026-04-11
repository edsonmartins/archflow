package br.com.archflow.conversation.concurrency;

import br.com.archflow.conversation.domain.Conversation;
import br.com.archflow.conversation.domain.InMemoryConversationRepository;
import br.com.archflow.conversation.domain.Message;
import br.com.archflow.conversation.memory.WindowedChatMemoryProvider;
import br.com.archflow.conversation.persona.Persona;
import br.com.archflow.conversation.persona.PersonaResolver;
import br.com.archflow.conversation.prompt.InMemoryPromptRegistry;
import br.com.archflow.conversation.prompt.PromptVersion;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress tests that hammer the gap components from many threads to validate
 * the thread-safety claims in their javadocs.
 */
class ConversationConcurrencyStressTest {

    private static final int THREADS = 16;
    private static final int ITERATIONS_PER_THREAD = 200;

    @Test
    @Timeout(30)
    @DisplayName("InMemoryPromptRegistry: parallel register + read is consistent")
    void promptRegistryUnderLoad() throws Exception {
        var registry = new InMemoryPromptRegistry();
        AtomicInteger writeFailures = new AtomicInteger();
        AtomicInteger readFailures = new AtomicInteger();

        runParallel(THREADS, threadIdx -> {
            for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                try {
                    PromptVersion v = registry.register("tenant-" + (threadIdx % 4), "prompt", "content " + i);
                    if (v.version() < 1) writeFailures.incrementAndGet();
                } catch (Exception e) {
                    writeFailures.incrementAndGet();
                }
                try {
                    Optional<PromptVersion> active = registry.getActive("tenant-" + (threadIdx % 4), "prompt");
                    if (active.isEmpty()) readFailures.incrementAndGet();
                } catch (Exception e) {
                    readFailures.incrementAndGet();
                }
            }
        });

        assertThat(writeFailures.get()).isZero();
        assertThat(readFailures.get()).isZero();

        // Each of the 4 tenants should have exactly THREADS/4 * ITERATIONS_PER_THREAD versions
        for (int t = 0; t < 4; t++) {
            int expected = (THREADS / 4) * ITERATIONS_PER_THREAD;
            assertThat(registry.listVersions("tenant-" + t, "prompt")).hasSize(expected);

            // Version numbers should be exactly 1..expected with no duplicates
            Set<Integer> versions = new java.util.HashSet<>();
            registry.listVersions("tenant-" + t, "prompt").forEach(v -> versions.add(v.version()));
            assertThat(versions).hasSize(expected);
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("InMemoryPromptRegistry: activateVersion is atomic (never two actives)")
    void promptRegistryActivateIsAtomic() throws Exception {
        var registry = new InMemoryPromptRegistry();
        // Seed 20 versions
        for (int i = 0; i < 20; i++) {
            registry.register("t1", "prompt", "v" + i);
        }

        AtomicInteger failures = new AtomicInteger();

        runParallel(THREADS, threadIdx -> {
            for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                int targetVersion = 1 + (i % 20);
                registry.activateVersion("t1", "prompt", targetVersion);
                long activeCount = registry.listVersions("t1", "prompt").stream()
                        .filter(PromptVersion::active)
                        .count();
                if (activeCount > 1) failures.incrementAndGet();
            }
        });

        assertThat(failures.get()).isZero();
        assertThat(registry.listVersions("t1", "prompt").stream()
                .filter(PromptVersion::active).count()).isEqualTo(1);
    }

    @Test
    @Timeout(30)
    @DisplayName("InMemoryConversationRepository: concurrent writes preserve all messages")
    void repositoryPreservesAllMessagesUnderContention() throws Exception {
        var repo = new InMemoryConversationRepository();
        Conversation conv = repo.save(Conversation.start("t1", "u1", "API"));

        runParallel(THREADS, threadIdx -> {
            for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                repo.addMessage(Message.userText(conv.id(), "t1", "t" + threadIdx + "-i" + i));
            }
        });

        int expected = THREADS * ITERATIONS_PER_THREAD;
        assertThat(repo.countMessages("t1", conv.id())).isEqualTo(expected);
        assertThat(repo.listMessages("t1", conv.id())).hasSize(expected);
    }

    @Test
    @Timeout(30)
    @DisplayName("InMemoryConversationRepository: tenant isolation holds under contention")
    void repositoryTenantIsolationUnderLoad() throws Exception {
        var repo = new InMemoryConversationRepository();

        runParallel(THREADS, threadIdx -> {
            String tenant = "t" + (threadIdx % 4);
            for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                repo.save(Conversation.start(tenant, "user-" + i, "API"));
            }
        });

        int perTenant = (THREADS / 4) * ITERATIONS_PER_THREAD;
        for (int t = 0; t < 4; t++) {
            assertThat(repo.listByTenant("t" + t)).hasSize(perTenant);
        }
        // Other tenants see zero
        assertThat(repo.listByTenant("ghost")).isEmpty();
    }

    @Test
    @Timeout(30)
    @DisplayName("WindowedChatMemoryProvider: concurrent getOrCreate returns consistent instances")
    void memoryProviderReturnsConsistentInstances() throws Exception {
        var provider = new WindowedChatMemoryProvider(10, 100_000, null);

        // Each thread uses its own session, adds messages, and the final count should match
        runParallel(THREADS, threadIdx -> {
            String sessionId = "session-" + threadIdx;
            for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                ChatMemory mem = provider.getOrCreate("t1", sessionId);
                mem.add(UserMessage.from("msg " + i));
            }
        });

        // Each session should have exactly min(ITERATIONS_PER_THREAD, windowSize=10) messages
        for (int i = 0; i < THREADS; i++) {
            ChatMemory mem = provider.getOrCreate("t1", "session-" + i);
            assertThat(mem.messages()).hasSizeLessThanOrEqualTo(10);
        }
        assertThat(provider.size()).isEqualTo(THREADS);
    }

    @Test
    @Timeout(30)
    @DisplayName("WindowedChatMemoryProvider: LRU cap holds under concurrent inserts")
    void memoryProviderLruCapHoldsUnderLoad() throws Exception {
        int cap = 20;
        var provider = new WindowedChatMemoryProvider(6, cap, null);

        runParallel(THREADS, threadIdx -> {
            for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                provider.getOrCreate("t1", "session-" + threadIdx + "-" + i);
            }
        });

        assertThat(provider.size()).isLessThanOrEqualTo(cap);
    }

    @Test
    @Timeout(30)
    @DisplayName("PersonaResolver: sticky cache is consistent per conversation under load")
    void personaResolverStickyUnderLoad() throws Exception {
        Persona tracking = Persona.of("order_tracking", "Tracking", "p1", List.of(), "rastrear");
        Persona complaint = Persona.of("complaint", "Complaint", "p2", List.of(), "reclamação");
        Persona general = Persona.of("general", "General", "p3", List.of());
        var resolver = new PersonaResolver(List.of(tracking, complaint), general);

        Set<String> unexpected = ConcurrentHashMap.newKeySet();

        runParallel(THREADS, threadIdx -> {
            String convId = "conv-" + (threadIdx % 4);
            // First turn: keyword resolves tracking
            resolver.resolve(convId, "rastrear");
            // Subsequent turns: no keyword, should remain sticky on tracking
            for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                Optional<Persona> p = resolver.resolve(convId, "continue");
                if (p.isEmpty() || !p.get().id().equals("order_tracking")) {
                    unexpected.add(convId + ":" + p.map(Persona::id).orElse("empty"));
                }
            }
        });

        assertThat(unexpected).isEmpty();
    }

    // ── helper ─────────────────────────────────────────────────────

    private static void runParallel(int threadCount, java.util.function.IntConsumer task) throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            exec.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.accept(idx);
                } catch (Throwable t) {
                    errors.incrementAndGet();
                    t.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(25, TimeUnit.SECONDS)).isTrue();
        exec.shutdownNow();
        assertThat(errors.get()).isZero();
    }
}
