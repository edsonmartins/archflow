package br.com.archflow.api.agent.invocation;

import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.agent.dto.InvokeRequest;
import br.com.archflow.api.agent.dto.InvokeResponse;
import br.com.archflow.api.agent.impl.AgentControllerImpl;
import br.com.archflow.api.queue.JdbcAgentInvocationQueue;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static br.com.archflow.api.agent.invocation.InvocationTestSupport.awaitTerminal;
import static br.com.archflow.api.agent.invocation.InvocationTestSupport.fastSettings;
import static br.com.archflow.api.agent.invocation.InvocationTestSupport.realCatalog;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-80 sobre a fila durável: as migrations reais (V6_2 + V6_13) num H2 em modo PostgreSQL. O
 * {@code poll()} antigo apagava a linha ao retirar — um consumidor que morresse no meio perdia a
 * invocação. Aqui o claim reserva com lease, só o {@code complete} remove, e um lease vencido devolve
 * a invocação.
 */
@DisplayName("Consumidor da fila de invocações (JDBC)")
class JdbcAgentInvocationConsumerTest {

    private static final Duration LEASE = Duration.ofMinutes(5);

    private JdbcDataSource dataSource;
    private MutableClock clock;
    private JdbcAgentInvocationQueue queue;
    private JdbcAgentInvocationResultStore store;
    private final List<AgentInvocationConsumer> consumers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:inv-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        migrate("V6_2__create_agent_invocations.sql");
        migrate("V6_13__lease_agent_invocations_and_results.sql");
        clock = new MutableClock(Instant.parse("2026-11-24T12:00:00Z"));
        queue = new JdbcAgentInvocationQueue(dataSource, 10, 10_000, clock);
        store = new JdbcAgentInvocationResultStore(dataSource);
    }

    @AfterEach
    void tearDown() {
        consumers.forEach(AgentInvocationConsumer::close);
    }

    private void migrate(String file) throws Exception {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/" + file)) {
            assertThat(in).as(file).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.strip().startsWith("--")) {
                withoutComments.append(line).append('\n');
            }
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String statement : withoutComments.toString().split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
        }
    }

    private AgentInvocationConsumer start(AgentInvocationExecutor executor) {
        AgentInvocationConsumer consumer = new AgentInvocationConsumer(queue, store, executor, fastSettings(4));
        consumers.add(consumer);
        consumer.start();
        return consumer;
    }

    private int rows() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM agent_invocations")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("invoke → consumidor → resultado gravado no banco e invocação removida da fila")
    void invokeCompletesAndIsRemoved() throws Exception {
        start(new CatalogAgentInvocationExecutor(realCatalog(), null));
        InvokeResponse accepted = new AgentControllerImpl(queue).invoke("tech-support-assistant",
                new InvokeRequest("tenant_demo", null, Map.of("operation", "analyze", "message", "minha impressora não liga")));

        AgentInvocationResult result = awaitTerminal(store, accepted.requestId());

        assertThat(result.status()).as("erro: %s", result.error())
                .isEqualTo(AgentInvocationResult.Status.COMPLETED);
        // A saída faz a volta pelo JSON da coluna e chega como dados.
        assertThat(result.output()).isInstanceOfSatisfying(Map.class, m -> assertThat(m).containsKey("intent"));
        assertThat(result.tenantId()).isEqualTo("tenant_demo");
        assertThat(rows()).isZero();
        assertThat(new AgentInvocationLookup(store, queue, null).find(accepted.requestId(), "tenant_demo"))
                .contains(result);
    }

    @Test
    @DisplayName("claim reserva com lease: ninguém mais a vê até o lease vencer; aí outro consumidor a pega")
    void leaseHidesAndExpiredLeaseReturns() throws Exception {
        InvocationRequest r = InvocationRequest.root("t", "a", Map.of("k", "v"));
        queue.submit(r);

        assertThat(queue.claim("a", LEASE)).get().extracting(InvocationRequest::requestId)
                .isEqualTo(r.requestId());
        // Reservada: não sai no claim, no poll nem no pending de outro — mas continua na tabela.
        assertThat(queue.claim("b", LEASE)).isEmpty();
        assertThat(queue.poll()).isEmpty();
        assertThat(queue.pending()).isEmpty();
        assertThat(queue.find(r.requestId())).isPresent();
        assertThat(queue.renew(r.requestId(), "b", LEASE)).isFalse();

        // "a" morreu: o lease vence e a invocação volta, inteira.
        clock.advance(LEASE.plusSeconds(1));
        assertThat(queue.claim("b", LEASE)).get().satisfies(back -> {
            assertThat(back.requestId()).isEqualTo(r.requestId());
            assertThat(back.payload()).containsEntry("k", "v");
        });
        // A confirmação tardia de "a" não apaga a invocação que agora é de "b".
        queue.complete(r.requestId(), "a");
        assertThat(rows()).isEqualTo(1);
        assertThat(queue.renew(r.requestId(), "b", LEASE)).isTrue();
        queue.complete(r.requestId(), "b");
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("consumidor que morreu no meio: a execução é retomada (tentativa 2) e termina")
    void crashedMidExecutionIsResumed() {
        InvocationRequest r = InvocationRequest.root("t", "a", Map.of());
        queue.submit(r);
        // Simula o consumidor "morto": reivindicou, gravou RUNNING e sumiu sem confirmar.
        assertThat(queue.claim("morto", LEASE)).isPresent();
        store.save(AgentInvocationResult.running(r, 1, clock.instant()));
        clock.advance(LEASE.plusSeconds(1));

        start(request -> "retomada");
        AgentInvocationResult result = awaitTerminal(store, r.requestId());

        assertThat(result.status()).isEqualTo(AgentInvocationResult.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("retomada");
        assertThat(result.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("caiu entre gravar e confirmar: a nova reivindicação confirma sem executar de novo")
    void terminalButUnconfirmedIsNotRerun() throws Exception {
        InvocationRequest r = InvocationRequest.root("t", "a", Map.of());
        queue.submit(r);
        assertThat(queue.claim("morto", LEASE)).isPresent();
        store.save(AgentInvocationResult.running(r, 1, clock.instant()).completed("feito", clock.instant()));
        clock.advance(LEASE.plusSeconds(1));
        AtomicInteger runs = new AtomicInteger();

        start(request -> runs.incrementAndGet());
        InvocationTestSupport.await(() -> {
            try {
                return rows() == 0 ? java.util.Optional.of(true) : java.util.Optional.empty();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, () -> "a invocação não foi confirmada");

        assertThat(runs.get()).isZero();
        assertThat(store.find(r.requestId())).get().extracting(AgentInvocationResult::output).isEqualTo("feito");
    }

    @Test
    @DisplayName("duas réplicas na mesma fila: cada invocação executa exatamente uma vez")
    void twoConsumersNeverDuplicate() {
        Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
        AgentInvocationExecutor counting =
                request -> runs.computeIfAbsent(request.requestId(), k -> new AtomicInteger()).incrementAndGet();
        start(counting);
        start(counting);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            InvocationRequest r = InvocationRequest.root("t", "a", Map.of("i", i));
            ids.add(r.requestId());
            queue.submit(r);
        }

        ids.forEach(id -> awaitTerminal(store, id));

        assertThat(runs).hasSize(30);
        assertThat(runs.values()).allSatisfy(n -> assertThat(n.get()).isEqualTo(1));
    }

    @Test
    @DisplayName("desligamento no meio da execução: a invocação volta para a fila na hora e sem gastar tentativa")
    void shutdownGivesTheInvocationBack() throws Exception {
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        AgentInvocationConsumer consumer = new AgentInvocationConsumer(queue, store, request -> {
            running.countDown();
            new java.util.concurrent.CountDownLatch(1).await(); // só sai interrompido
            return null;
        }, new AgentInvocationConsumer.Settings(1, Duration.ofMillis(20), LEASE, 3, Duration.ofMillis(100)),
                clock);
        consumers.add(consumer);
        consumer.start();
        InvocationRequest r = InvocationRequest.root("t", "a", Map.of());
        queue.submit(r);
        assertThat(running.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        consumer.close();

        // Sem avançar o relógio: outra réplica a vê já, em vez de esperar o lease de 5 minutos vencer.
        assertThat(queue.claim("nova-replica", LEASE)).get().extracting(InvocationRequest::requestId)
                .isEqualTo(r.requestId());
        // E o registro não conta o desligamento como uma execução que morreu no meio.
        assertThat(store.find(r.requestId())).get().satisfies(back -> {
            assertThat(back.status()).isEqualTo(AgentInvocationResult.Status.QUEUED);
            assertThat(back.attempts()).isZero();
        });
    }

    @Test
    @DisplayName("retenção no banco: apaga os resultados finais velhos e mantém os recentes e os em execução")
    void retentionPurgesTheTable() throws Exception {
        Instant now = clock.instant();
        InvocationRequest old = InvocationRequest.root("t", "a", Map.of());
        InvocationRequest recent = InvocationRequest.root("t", "a", Map.of());
        InvocationRequest running = InvocationRequest.root("t", "a", Map.of());
        store.save(AgentInvocationResult.running(old, 1, now.minus(Duration.ofDays(9)))
                .completed(Map.of("saida", "grande"), now.minus(Duration.ofDays(8))));
        store.save(AgentInvocationResult.running(recent, 1, now.minus(Duration.ofDays(1)))
                .completed("ok", now.minus(Duration.ofHours(1))));
        store.save(AgentInvocationResult.running(running, 1, now.minus(Duration.ofDays(30))));
        AgentInvocationConsumer consumer = new AgentInvocationConsumer(queue, store, request -> null,
                fastSettings(1), clock);

        assertThat(consumer.purgeExpiredResults()).isEqualTo(1);

        assertThat(store.find(old.requestId())).isEmpty();
        assertThat(store.find(recent.requestId())).isPresent();
        assertThat(store.find(running.requestId())).isPresent();
    }

    @Test
    @DisplayName("falha do agente fica gravada como FAILED com a mensagem")
    void failureIsRecorded() {
        start(request -> {
            throw new IllegalStateException("o agente caiu");
        });
        InvocationRequest r = InvocationRequest.root("t", "a", Map.of());
        queue.submit(r);

        AgentInvocationResult result = awaitTerminal(store, r.requestId());

        assertThat(result.status()).isEqualTo(AgentInvocationResult.Status.FAILED);
        assertThat(result.error()).isEqualTo("o agente caiu");
    }

    /** Relógio que o teste avança — o lease é medido nele, nos dois lados. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
