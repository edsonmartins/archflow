package br.com.archflow.standalone;

import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.events.proto.ProtobufEventMapper;
import br.com.archflow.events.proto.ProtobufEventPublisher;
import br.com.archflow.events.proto.generated.FlowEventBatch;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: verifies that {@link ProtobufEventPublisher} correctly
 * delivers protobuf-encoded events to an HTTP endpoint when events are broadcast
 * through an {@link EventStreamRegistry}.
 *
 * <p>Uses an in-JVM {@link HttpServer} on a random port to capture the raw
 * protobuf bodies, then decodes them with {@link ProtobufEventMapper} and
 * asserts event content.
 */
@DisplayName("StandaloneIngestIT — protobuf publisher → HTTP ingest")
class StandaloneIngestIT {

    private HttpServer server;
    private int serverPort;
    private final CopyOnWriteArrayList<FlowEventBatch> receivedBatches = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger(0);

    private EventStreamRegistry registry;
    private ProtobufEventPublisher publisher;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = server.getAddress().getPort();

        server.createContext("/api/events/ingest", exchange -> {
            try {
                InputStream in = exchange.getRequestBody();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                }
                byte[] body = buf.toByteArray();

                FlowEventBatch batch = FlowEventBatch.parseFrom(body);
                receivedBatches.add(batch);
                requestCount.incrementAndGet();

                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().close();
            } catch (Exception e) {
                exchange.sendResponseHeaders(400, 0);
                exchange.getResponseBody().close();
            }
        });

        server.setExecutor(null); // default executor
        server.start();
    }

    @BeforeEach
    void setUpRegistry() {
        registry = new EventStreamRegistry(60_000, 300_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (publisher != null) {
            publisher.close();
        }
        registry.shutdown();
        server.stop(0);
        receivedBatches.clear();
        requestCount.set(0);
    }

    // ----------------------------------------------------------------
    // Core test: events arrive at the HTTP endpoint
    // ----------------------------------------------------------------

    @Test
    @DisplayName("events broadcast to registry are delivered to ingest endpoint")
    void eventsDeliveredToEndpoint() throws Exception {
        URI ingestUri = URI.create("http://localhost:" + serverPort + "/api/events/ingest");
        publisher = new ProtobufEventPublisher(registry, ingestUri, "it-agent");

        // Broadcast 3 events through the registry
        for (int i = 1; i <= 3; i++) {
            ArchflowEvent event = br.com.archflow.agent.streaming.domain.FlowEvent
                    .flowStarted("flow-" + i, "tenant-it", 2, "exec-" + i);
            registry.broadcast("exec-" + i, event);
        }

        // Force flush by closing; it drains remaining events with a 2s budget
        publisher.close();
        publisher = null;

        // Wait briefly for async HTTP to land
        waitForRequests(1, 3_000);

        assertThat(requestCount.get()).isGreaterThanOrEqualTo(1);

        // Count total events across all received batches
        int totalEvents = receivedBatches.stream()
                .mapToInt(FlowEventBatch::getEventsCount)
                .sum();
        assertThat(totalEvents).isEqualTo(3);
    }

    @Test
    @DisplayName("source agent ID is set correctly in the batch")
    void sourceAgentIdInBatch() throws Exception {
        URI ingestUri = URI.create("http://localhost:" + serverPort + "/api/events/ingest");
        publisher = new ProtobufEventPublisher(registry, ingestUri, "my-standalone-agent");

        ArchflowEvent event = br.com.archflow.agent.streaming.domain.FlowEvent
                .flowCompleted("flow-x", "tenant-a", 100L, "exec-x");
        registry.broadcast("exec-x", event);

        publisher.close();
        publisher = null;

        waitForRequests(1, 3_000);

        assertThat(receivedBatches).isNotEmpty();
        assertThat(receivedBatches.get(0).getSourceAgentId()).isEqualTo("my-standalone-agent");
    }

    @Test
    @DisplayName("decoded event preserves type and executionId")
    void decodedEventPreservesFields() throws Exception {
        URI ingestUri = URI.create("http://localhost:" + serverPort + "/api/events/ingest");
        publisher = new ProtobufEventPublisher(registry, ingestUri, "decode-agent");

        ArchflowEvent original = br.com.archflow.agent.streaming.domain.FlowEvent
                .stepCompleted("flow-decode", "step-1", 0, 42L, "exec-decode");
        registry.broadcast("exec-decode", original);

        publisher.close();
        publisher = null;

        waitForRequests(1, 3_000);

        assertThat(receivedBatches).isNotEmpty();
        FlowEventBatch batch = receivedBatches.get(0);
        assertThat(batch.getEventsCount()).isEqualTo(1);

        ArchflowEvent decoded = ProtobufEventMapper.fromProto(batch.getEvents(0));
        assertThat(decoded.getType()).isEqualTo(
                br.com.archflow.agent.streaming.ArchflowEventType.STEP_COMPLETED);
        assertThat(decoded.getExecutionId()).isEqualTo("exec-decode");
    }

    @Test
    @DisplayName("publisher with null agentId uses default and delivers events")
    void nullAgentIdUsesDefault() throws Exception {
        URI ingestUri = URI.create("http://localhost:" + serverPort + "/api/events/ingest");
        publisher = new ProtobufEventPublisher(registry, ingestUri, null);

        ArchflowEvent event = br.com.archflow.agent.streaming.domain.SystemEvent.heartbeat("exec-hb");
        registry.broadcast("exec-hb", event);

        publisher.close();
        publisher = null;

        waitForRequests(1, 3_000);

        assertThat(requestCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("many events are batched into one or few HTTP requests")
    void manyEventsBatchedEfficiently() throws Exception {
        URI ingestUri = URI.create("http://localhost:" + serverPort + "/api/events/ingest");
        publisher = new ProtobufEventPublisher(registry, ingestUri, "batch-agent");

        int eventCount = 50;
        for (int i = 0; i < eventCount; i++) {
            ArchflowEvent e = br.com.archflow.agent.streaming.domain.FlowEvent
                    .stepStarted("flow-b", "step-" + i, "Step " + i, i, eventCount, "exec-batch");
            registry.broadcast("exec-batch", e);
        }

        publisher.close();
        publisher = null;

        waitForRequests(1, 3_000);

        int totalReceived = receivedBatches.stream()
                .mapToInt(FlowEventBatch::getEventsCount)
                .sum();
        assertThat(totalReceived).isEqualTo(eventCount);

        // All events in ≤ 2 batches (well under the 100-event flush threshold)
        assertThat(receivedBatches.size()).isLessThanOrEqualTo(2);
    }

    // ----------------------------------------------------------------
    // CliArgs round-trip
    // ----------------------------------------------------------------

    @Test
    @DisplayName("CliArgs parses --events-url and --agent-id")
    void cliArgs_eventsUrlAndAgentId() {
        String[] args = {"flow.json", "--events-url",
                "http://localhost:9090/api/events/ingest", "--agent-id", "my-agent-007"};

        StandaloneRunner.CliArgs cli = StandaloneRunner.CliArgs.parse(args);

        assertThat(cli.eventsUrl()).isEqualTo("http://localhost:9090/api/events/ingest");
        assertThat(cli.agentId()).isEqualTo("my-agent-007");
    }

    @Test
    @DisplayName("CliArgs defaults eventsUrl and agentId to null")
    void cliArgs_defaultsToNull() {
        StandaloneRunner.CliArgs cli = StandaloneRunner.CliArgs.parse(new String[]{"flow.json"});

        assertThat(cli.eventsUrl()).isNull();
        assertThat(cli.agentId()).isNull();
    }

    // ----------------------------------------------------------------
    // Helper
    // ----------------------------------------------------------------

    private void waitForRequests(int minCount, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (requestCount.get() < minCount && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }
}
