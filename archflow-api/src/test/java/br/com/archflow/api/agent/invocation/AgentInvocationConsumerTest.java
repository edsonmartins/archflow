package br.com.archflow.api.agent.invocation;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.agent.dto.InvokeRequest;
import br.com.archflow.api.agent.dto.InvokeResponse;
import br.com.archflow.api.agent.impl.AgentControllerImpl;
import br.com.archflow.api.events.dto.MessageRequest;
import br.com.archflow.api.events.dto.MessageResponse;
import br.com.archflow.api.events.impl.EventControllerImpl;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static br.com.archflow.api.agent.invocation.InvocationTestSupport.awaitTerminal;
import static br.com.archflow.api.agent.invocation.InvocationTestSupport.fastSettings;
import static br.com.archflow.api.agent.invocation.InvocationTestSupport.realCatalog;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-80: a fila de invocações tinha produtores e nenhum consumidor — o Agent lab respondia
 * {@code ACCEPTED} e nada mais acontecia. Aqui o invoke e o chat passam pelos controllers reais, a
 * fila em memória e o catálogo real, e o resultado precisa aparecer na consulta por requestId.
 */
@DisplayName("Consumidor da fila de invocações (memória)")
class AgentInvocationConsumerTest {

    private final ComponentCatalog catalog = realCatalog();
    private AgentInvocationQueue queue;
    private AgentInvocationResultStore store;
    private AgentInvocationConsumer consumer;

    @BeforeEach
    void setUp() {
        queue = new InMemoryAgentInvocationQueue();
        store = new InMemoryAgentInvocationResultStore();
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    private AgentInvocationConsumer start(AgentInvocationExecutor executor, int maxConcurrency) {
        consumer = new AgentInvocationConsumer(queue, store, executor, fastSettings(maxConcurrency));
        consumer.start();
        return consumer;
    }

    private AgentInvocationExecutor catalogExecutor() {
        return new CatalogAgentInvocationExecutor(catalog, null);
    }

    @Test
    @DisplayName("Invocar (payload) executa o agente do catálogo e o resultado é consultável por requestId")
    void invokeRunsAgentAndResultIsQueryable() {
        start(catalogExecutor(), 4);
        InvokeResponse accepted = new AgentControllerImpl(queue).invoke("text-transform-tool",
                new InvokeRequest("tenant_demo", null, Map.of("operation", "uppercase", "message", "olá mundo")));

        AgentInvocationResult result = awaitTerminal(store, accepted.requestId());

        assertThat(result.status()).as("erro: %s", result.error())
                .isEqualTo(AgentInvocationResult.Status.COMPLETED);
        // A entrada é o texto da mensagem, não o mapa com o sessionId que o controller acrescenta.
        assertThat(result.output()).isEqualTo("OLÁ MUNDO");
        assertThat(result.tenantId()).isEqualTo("tenant_demo");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.startedAt()).isNotNull();
        assertThat(result.finishedAt()).isNotNull();
        assertThat(queue.size()).isZero();

        AgentInvocationLookup lookup = new AgentInvocationLookup(store, queue, consumer);
        assertThat(lookup.find(accepted.requestId(), "tenant_demo")).contains(result);
        // Outro tenant não vê nem que a invocação existe.
        assertThat(lookup.find(accepted.requestId(), "outro-tenant")).isEmpty();
    }

    @Test
    @DisplayName("Enviar mensagem (chat) executa o agente pela operação padrão que ele declara")
    void messageRunsAgentDefaultOperation() {
        start(catalogExecutor(), 4);
        MessageResponse accepted = new EventControllerImpl(queue).sendMessage(new MessageRequest(
                "tenant_demo", null, "conversational-agent", "minha impressora não liga", Map.of()));

        AgentInvocationResult result = awaitTerminal(store, accepted.requestId());

        assertThat(result.status()).as("erro: %s", result.error())
                .isEqualTo(AgentInvocationResult.Status.COMPLETED);
        assertThat(result.output()).isNotNull();
    }

    @Test
    @DisplayName("Enviar mensagem a um componente sem operação padrão falha pedindo a operação, sem adivinhar")
    void messageToComponentWithoutDefaultAsksForOperation() {
        start(catalogExecutor(), 4);
        MessageResponse accepted = new EventControllerImpl(queue).sendMessage(new MessageRequest(
                "tenant_demo", null, "tech-support-assistant", "minha impressora não liga", Map.of()));

        AgentInvocationResult result = awaitTerminal(store, accepted.requestId());

        assertThat(result.status()).isEqualTo(AgentInvocationResult.Status.FAILED);
        assertThat(result.error()).contains("nenhuma operação informada e tech-support-assistant")
                .contains("analyze, respond");
    }

    @Test
    @DisplayName("agente que não existe vira FAILED com a mensagem, em vez de ficar ACCEPTED para sempre")
    void unknownAgentFails() {
        start(catalogExecutor(), 4);
        InvokeResponse accepted = new AgentControllerImpl(queue).invoke("nao-existe",
                new InvokeRequest("tenant_demo", null, Map.of("message", "oi")));

        AgentInvocationResult result = awaitTerminal(store, accepted.requestId());

        assertThat(result.status()).isEqualTo(AgentInvocationResult.Status.FAILED);
        assertThat(result.error()).contains("nao-existe").contains("não está no catálogo");
        assertThat(result.output()).isNull();
    }

    @Test
    @DisplayName("null do JSON no payload é chave ausente: o agente roda, em vez de FAILED 'NullPointerException'")
    void nullInPayloadIsIgnored() {
        start(catalogExecutor(), 4);
        Map<String, Object> payload = new HashMap<>();
        payload.put("operation", "uppercase");
        payload.put("message", "oi");
        payload.put("extra", null);
        Map<String, Object> config = new HashMap<>();
        config.put("escalationThreshold", null);
        payload.put("config", config);
        InvokeResponse accepted = new AgentControllerImpl(queue).invoke("text-transform-tool",
                new InvokeRequest("tenant_demo", null, payload));

        AgentInvocationResult result = awaitTerminal(store, accepted.requestId());

        assertThat(result.status()).as("erro: %s", result.error())
                .isEqualTo(AgentInvocationResult.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("OI");
    }

    @Test
    @DisplayName("agente pendurado: o prazo o encerra como FAILED e a vaga volta para as próximas invocações")
    void hungAgentTimesOutAndFreesItsSlot() {
        CountDownLatch never = new CountDownLatch(1);
        AgentInvocationExecutor real = catalogExecutor();
        // Uma vaga só: sem prazo, o agente pendurado a segura e a segunda invocação nunca roda.
        consumer = new AgentInvocationConsumer(queue, store, request -> {
            if ("pendurado".equals(request.agentId())) {
                never.await();
            }
            return real.execute(request);
        }, new AgentInvocationConsumer.Settings(1, Duration.ofMillis(20), Duration.ofMinutes(5), 3,
                Duration.ofSeconds(1), Duration.ofMillis(300), Duration.ofDays(7)));
        consumer.start();
        InvocationRequest hung = InvocationRequest.root("t", "pendurado", Map.of());
        InvocationRequest next = InvocationRequest.root("t", "text-transform-tool", Map.of("operation", "uppercase", "message", "depois"));
        queue.submit(hung);
        queue.submit(next);

        AgentInvocationResult timedOut = awaitTerminal(store, hung.requestId());
        AgentInvocationResult after = awaitTerminal(store, next.requestId());

        assertThat(timedOut.status()).isEqualTo(AgentInvocationResult.Status.FAILED);
        assertThat(timedOut.error()).contains("tempo limite").contains("PT0.3S");
        assertThat(after.status()).as("erro: %s", after.error()).isEqualTo(AgentInvocationResult.Status.COMPLETED);
        assertThat(after.output()).isEqualTo("DEPOIS");
    }

    @Test
    @DisplayName("retenção: apaga só resultados finais terminados antes do prazo")
    void retentionPurgesOnlyOldTerminalResults() {
        Instant now = Instant.parse("2026-11-24T12:00:00Z");
        consumer = new AgentInvocationConsumer(queue, store, request -> null,
                new AgentInvocationConsumer.Settings(1, Duration.ofMillis(20), Duration.ofMinutes(5), 3,
                        Duration.ofSeconds(1), Duration.ofMinutes(10), Duration.ofDays(7)),
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        InvocationRequest old = InvocationRequest.root("t", "a", Map.of());
        InvocationRequest recent = InvocationRequest.root("t", "a", Map.of());
        InvocationRequest running = InvocationRequest.root("t", "a", Map.of());
        store.save(AgentInvocationResult.running(old, 1, now.minus(Duration.ofDays(9)))
                .completed("velho", now.minus(Duration.ofDays(8))));
        store.save(AgentInvocationResult.running(recent, 1, now.minus(Duration.ofDays(2)))
                .failed("recente", now.minus(Duration.ofDays(1))));
        store.save(AgentInvocationResult.running(running, 1, now.minus(Duration.ofDays(30))));

        assertThat(consumer.purgeExpiredResults()).isEqualTo(1);

        assertThat(store.find(old.requestId())).isEmpty();
        assertThat(store.find(recent.requestId())).isPresent();
        assertThat(store.find(running.requestId())).isPresent();
    }

    @Test
    @DisplayName("operação inexistente falha com a lista das operações do agente")
    void unknownOperationFails() {
        start(catalogExecutor(), 4);
        InvokeResponse accepted = new AgentControllerImpl(queue).invoke("text-transform-tool",
                new InvokeRequest("tenant_demo", null, Map.of("message", "oi", "operation", "voar")));

        AgentInvocationResult result = awaitTerminal(store, accepted.requestId());

        assertThat(result.status()).isEqualTo(AgentInvocationResult.Status.FAILED);
        assertThat(result.error()).contains("voar").contains("uppercase");
    }

    @Test
    @DisplayName("concorrência limitada: nunca mais execuções simultâneas que maxConcurrency")
    void boundedConcurrency() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        start(request -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
                return "ok";
            } finally {
                running.decrementAndGet();
            }
        }, 2);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            InvocationRequest r = InvocationRequest.root("t", "a", Map.of("i", i));
            ids.add(r.requestId());
            queue.submit(r);
        }

        InvocationTestSupport.await(() -> peak.get() == 2 ? java.util.Optional.of(true)
                : java.util.Optional.empty(), () -> "nunca chegou a 2 simultâneas");
        Thread.sleep(100);
        // Sem vaga, o despachante não retira: o resto espera na fila, não fora dela.
        assertThat(queue.size()).isEqualTo(4);
        release.countDown();

        for (String id : ids) {
            assertThat(awaitTerminal(store, id).status()).isEqualTo(AgentInvocationResult.Status.COMPLETED);
        }
        assertThat(peak.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("consumidor único: cada invocação executa exatamente uma vez")
    void eachInvocationRunsExactlyOnce() {
        Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
        start(request -> runs.computeIfAbsent(request.requestId(), k -> new AtomicInteger()).incrementAndGet(), 8);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            InvocationRequest r = InvocationRequest.root("t", "a", Map.of());
            ids.add(r.requestId());
            queue.submit(r);
        }

        ids.forEach(id -> awaitTerminal(store, id));

        assertThat(runs).hasSize(50);
        assertThat(runs.values()).allSatisfy(n -> assertThat(n.get()).isEqualTo(1));
    }

    @Test
    @DisplayName("enquanto executa, a consulta responde RUNNING; antes de retirar, QUEUED")
    void lookupShowsQueuedAndRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InvocationRequest r = InvocationRequest.root("t", "a", Map.of());
        AgentInvocationLookup idle = new AgentInvocationLookup(store, queue, null);
        queue.submit(r);
        assertThat(idle.find(r.requestId(), "t")).get()
                .extracting(AgentInvocationResult::status).isEqualTo(AgentInvocationResult.Status.QUEUED);

        start(request -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "fim";
        }, 1);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        AgentInvocationLookup lookup = new AgentInvocationLookup(store, queue, consumer);
        assertThat(lookup.find(r.requestId(), "t")).get()
                .extracting(AgentInvocationResult::status).isEqualTo(AgentInvocationResult.Status.RUNNING);
        release.countDown();

        assertThat(awaitTerminal(store, r.requestId()).output()).isEqualTo("fim");
    }

    @Test
    @DisplayName("resultado já final (caiu entre gravar e confirmar): confirma sem executar de novo")
    void alreadyTerminalIsNotRunAgain() {
        AtomicInteger runs = new AtomicInteger();
        consumer = new AgentInvocationConsumer(queue, store, request -> runs.incrementAndGet(), fastSettings(1));
        InvocationRequest r = InvocationRequest.root("t", "a", Map.of());
        store.save(AgentInvocationResult.running(r, 1, Instant.now()).completed("antes", Instant.now()));

        consumer.process(r);

        assertThat(runs.get()).isZero();
        assertThat(store.find(r.requestId())).get().extracting(AgentInvocationResult::output).isEqualTo("antes");
    }

    @Test
    @DisplayName("execuções interrompidas além de maxAttempts: desiste com FAILED em vez de repetir para sempre")
    void abandonsAfterMaxAttempts() {
        AtomicInteger runs = new AtomicInteger();
        consumer = new AgentInvocationConsumer(queue, store, request -> runs.incrementAndGet(), fastSettings(1));
        InvocationRequest r = InvocationRequest.root("t", "a", Map.of());
        store.save(AgentInvocationResult.running(r, 3, Instant.now()));

        consumer.process(r);

        assertThat(runs.get()).isZero();
        AgentInvocationResult result = store.find(r.requestId()).orElseThrow();
        assertThat(result.status()).isEqualTo(AgentInvocationResult.Status.FAILED);
        assertThat(result.error()).contains("abandonada");
    }
}
