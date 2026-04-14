package br.com.archflow.events.proto;

import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.events.proto.generated.FlowEventBatch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Batching protobuf publisher for cross-machine event ingestion.
 *
 * <h3>Backpressure strategy (drop-oldest)</h3>
 * <ul>
 *   <li>A bounded {@code ArrayBlockingQueue(2048)} holds pending events.</li>
 *   <li>{@link #submit(ArchflowEvent)} calls {@code offer()}. When the queue
 *       is full it drops the oldest entry ({@code poll()}) and retries —
 *       the caller is never blocked.</li>
 *   <li>A {@link #dropped()} counter tracks discarded events for monitoring.</li>
 * </ul>
 *
 * <h3>Flush triggers</h3>
 * <ul>
 *   <li>Scheduled flush every 1 second.</li>
 *   <li>Immediate flush when the queue reaches 100 events.</li>
 * </ul>
 *
 * <h3>HTTP transport</h3>
 * <ul>
 *   <li>{@code java.net.http.HttpClient.sendAsync()} with 5 s timeout.</li>
 *   <li>Fire-and-forget — HTTP errors are logged, never re-tried.</li>
 *   <li>{@code Content-Type: application/x-protobuf}.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * <pre>{@code
 * ProtobufEventPublisher pub = new ProtobufEventPublisher(registry, ingestUri, "my-agent");
 * // ... runs automatically ...
 * pub.close(); // drains remaining events and deregisters the global listener
 * }</pre>
 */
public class ProtobufEventPublisher implements AutoCloseable {

    private static final Logger log = Logger.getLogger(ProtobufEventPublisher.class.getName());

    private static final int QUEUE_CAPACITY = 2048;
    private static final int BATCH_FLUSH_THRESHOLD = 100;
    private static final long FLUSH_PERIOD_MS = 1_000L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final EventStreamRegistry registry;
    private final URI ingestUrl;
    private final String sourceAgentId;
    private final ArrayBlockingQueue<ArchflowEvent> queue;
    private final AtomicLong dropped;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Consumer<ArchflowEvent> globalListener;
    private volatile boolean closed = false;

    public ProtobufEventPublisher(EventStreamRegistry registry, URI ingestUrl, String sourceAgentId) {
        this.registry = registry;
        this.ingestUrl = ingestUrl;
        this.sourceAgentId = sourceAgentId != null ? sourceAgentId : "archflow-standalone";
        this.queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        this.dropped = new AtomicLong(0);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("proto-publisher-flush").factory());

        // Register as global listener on the registry
        this.globalListener = this::submit;
        registry.addGlobalListener(globalListener);

        // Periodic flush
        scheduler.scheduleAtFixedRate(this::flushNow, FLUSH_PERIOD_MS, FLUSH_PERIOD_MS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Submits an event for batched delivery. Never blocks.
     *
     * @param event the event to send
     */
    public void submit(ArchflowEvent event) {
        if (closed || event == null) return;
        if (!queue.offer(event)) {
            // Queue full — drop oldest, retry
            queue.poll();
            dropped.incrementAndGet();
            queue.offer(event);
        }
        // Trigger immediate flush if we're at the threshold
        if (queue.size() >= BATCH_FLUSH_THRESHOLD) {
            scheduler.execute(this::flushNow);
        }
    }

    /**
     * Returns the number of events that were dropped due to queue overflow.
     *
     * @return total dropped events since creation
     */
    public long dropped() {
        return dropped.get();
    }

    /**
     * Returns the current number of events waiting to be flushed.
     *
     * @return queue size
     */
    public int queueSize() {
        return queue.size();
    }

    /**
     * Drains remaining events (2 s budget) and shuts down.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;

        registry.removeGlobalListener(globalListener);

        // Drain remaining events with a 2-second budget
        scheduler.schedule(this::flushNow, 0, TimeUnit.MILLISECONDS);
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Final flush on the calling thread
        flushNow();
    }

    // ── Internal ────────────────────────────────────────────────────

    private void flushNow() {
        if (queue.isEmpty()) return;
        List<ArchflowEvent> batch = new ArrayList<>(Math.min(queue.size(), BATCH_FLUSH_THRESHOLD * 2));
        queue.drainTo(batch);
        if (batch.isEmpty()) return;
        sendBatch(batch);
    }

    private void sendBatch(List<ArchflowEvent> events) {
        try {
            FlowEventBatch proto = ProtobufEventMapper.toBatch(events, sourceAgentId);
            byte[] body = proto.toByteArray();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(ingestUrl)
                    .header("Content-Type", "application/x-protobuf")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(HTTP_TIMEOUT)
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.warning("ProtobufEventPublisher send failed (fire-and-forget): "
                                + ex.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            log.warning("ProtobufEventPublisher batch serialization failed: " + e.getMessage());
        }
    }
}
