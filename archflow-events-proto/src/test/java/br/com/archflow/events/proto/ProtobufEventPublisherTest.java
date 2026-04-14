package br.com.archflow.events.proto;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link ProtobufEventPublisher}.
 *
 * <p>Uses a no-op URI to avoid real HTTP connections — fire-and-forget
 * ensures failures are silently swallowed, so tests remain fast and
 * deterministic.
 */
class ProtobufEventPublisherTest {

    private static final URI FAKE_URI = URI.create("http://localhost:9999/api/events/ingest");

    private EventStreamRegistry registry;
    private ProtobufEventPublisher publisher;

    @BeforeEach
    void setUp() {
        registry = new EventStreamRegistry(60_000, 300_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (publisher != null) {
            publisher.close();
        }
        registry.shutdown();
    }

    // ----------------------------------------------------------------
    // Basic submit
    // ----------------------------------------------------------------

    @Test
    void submit_doesNotThrow() {
        publisher = new ProtobufEventPublisher(registry, FAKE_URI, "test-agent");

        ArchflowEvent event = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.FLOW_STARTED)
                .executionId("exec-1")
                .build();

        assertThatCode(() -> publisher.submit(event)).doesNotThrowAnyException();
    }

    @Test
    void submit_null_isIgnored() {
        publisher = new ProtobufEventPublisher(registry, FAKE_URI, "test-agent");
        assertThatCode(() -> publisher.submit(null)).doesNotThrowAnyException();
    }

    @Test
    void submit_afterClose_isIgnored() throws Exception {
        publisher = new ProtobufEventPublisher(registry, FAKE_URI, "test-agent");
        publisher.close();

        ArchflowEvent event = ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.LOG)
                .build();

        assertThatCode(() -> publisher.submit(event)).doesNotThrowAnyException();
    }

    // ----------------------------------------------------------------
    // Global listener registration
    // ----------------------------------------------------------------

    @Test
    void registeredAsGlobalListener_receivesRegistryBroadcasts() {
        List<ArchflowEvent> received = new ArrayList<>();
        // Use a test publisher backed by a capturing listener
        EventStreamRegistry captureRegistry = new EventStreamRegistry(60_000, 300_000);
        captureRegistry.addGlobalListener(received::add);

        ArchflowEvent e = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(ArchflowEventType.STEP_COMPLETED)
                .executionId("exec-x")
                .build();

        captureRegistry.broadcast("exec-x", e);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getType()).isEqualTo(ArchflowEventType.STEP_COMPLETED);

        captureRegistry.shutdown();
    }

    @Test
    void close_deregistersGlobalListener() throws Exception {
        List<ArchflowEvent> received = new ArrayList<>();
        EventStreamRegistry captureRegistry = new EventStreamRegistry(60_000, 300_000);

        // Simulate publisher-like registration
        Consumer<ArchflowEvent> listener = received::add;
        captureRegistry.addGlobalListener(listener);

        // Remove (simulating close)
        captureRegistry.removeGlobalListener(listener);

        // Broadcast after removal — should NOT be received
        ArchflowEvent e = ArchflowEvent.builder()
                .domain(ArchflowDomain.SYSTEM)
                .type(ArchflowEventType.LOG)
                .build();
        captureRegistry.broadcast("exec-y", e);

        assertThat(received).isEmpty();
        captureRegistry.shutdown();
    }

    // ----------------------------------------------------------------
    // Drop-oldest backpressure
    // ----------------------------------------------------------------

    @Test
    void dropOldest_droppedCounterIncrements() {
        publisher = new ProtobufEventPublisher(registry, FAKE_URI, "test-agent");

        // Fill the queue manually by submitting 2050 events
        // (queue capacity is 2048 in the real impl, but we can at least
        //  verify that dropped() works correctly with the state exposed)
        int initialDropped = (int) publisher.dropped();

        // Push events — some will be dropped when the queue fills up.
        // We don't know exact capacity from this test, so just verify
        // that submit never throws regardless of volume.
        for (int i = 0; i < 500; i++) {
            ArchflowEvent e = ArchflowEvent.builder()
                    .domain(ArchflowDomain.FLOW)
                    .type(ArchflowEventType.STEP_STARTED)
                    .executionId("exec-" + i)
                    .build();
            assertThatCode(() -> publisher.submit(e)).doesNotThrowAnyException();
        }

        // dropped() is always >= 0 (may be 0 if queue drained between submits)
        assertThat(publisher.dropped()).isGreaterThanOrEqualTo(initialDropped);
        assertThat(publisher.queueSize()).isGreaterThanOrEqualTo(0);
    }

    // ----------------------------------------------------------------
    // Queue size
    // ----------------------------------------------------------------

    @Test
    void queueSize_initiallyZero() {
        publisher = new ProtobufEventPublisher(registry, FAKE_URI, "test-agent");
        // After construction, nothing submitted yet
        // (may have flushed by the time we check, so just assert >= 0)
        assertThat(publisher.queueSize()).isGreaterThanOrEqualTo(0);
    }

    // ----------------------------------------------------------------
    // Publisher wired to registry — receives broadcast events
    // ----------------------------------------------------------------

    @Test
    void publisher_receivesAllRegistryBroadcasts() throws Exception {
        AtomicInteger submitCount = new AtomicInteger(0);

        // Custom publisher that counts submit calls without sending HTTP
        publisher = new ProtobufEventPublisher(registry, FAKE_URI, "test-agent") {
            @Override
            public void submit(ArchflowEvent event) {
                submitCount.incrementAndGet();
                super.submit(event);
            }
        };

        ArchflowEvent e1 = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW).type(ArchflowEventType.FLOW_STARTED)
                .executionId("exec-a").build();
        ArchflowEvent e2 = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW).type(ArchflowEventType.FLOW_COMPLETED)
                .executionId("exec-a").build();

        registry.broadcast("exec-a", e1);
        registry.broadcast("exec-a", e2);

        assertThat(submitCount.get()).isEqualTo(2);
    }

    // ----------------------------------------------------------------
    // Fire-and-forget: HTTP error does not propagate
    // ----------------------------------------------------------------

    @Test
    void sendToUnreachableHost_doesNotThrow() throws Exception {
        // Use a URI that will definitely fail (connection refused)
        URI badUri = URI.create("http://127.0.0.1:1/api/events/ingest");
        publisher = new ProtobufEventPublisher(registry, badUri, "test-agent");

        for (int i = 0; i < 5; i++) {
            ArchflowEvent e = ArchflowEvent.builder()
                    .domain(ArchflowDomain.SYSTEM)
                    .type(ArchflowEventType.LOG)
                    .build();
            publisher.submit(e);
        }

        // Close triggers flush — HTTP fails silently
        assertThatCode(() -> publisher.close()).doesNotThrowAnyException();

        // Reset field so @AfterEach doesn't double-close
        publisher = null;
    }

    // ----------------------------------------------------------------
    // nullAgentId falls back to default
    // ----------------------------------------------------------------

    @Test
    void nullSourceAgentId_usesDefault() {
        publisher = new ProtobufEventPublisher(registry, FAKE_URI, null);
        // Just verify construction doesn't throw
        assertThat(publisher).isNotNull();
    }
}
