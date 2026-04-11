package br.com.archflow.langchain4j.realtime.spi;

import java.util.function.Consumer;

/**
 * Transport abstraction that decouples a {@link RealtimeSession} from
 * the concrete WebSocket implementation.
 *
 * <p>Production adapters use the bundled
 * {@code OkHttpRealtimeTransport} (Java 11 HttpClient WebSocket) but
 * tests can swap in an in-memory {@code FakeRealtimeTransport} that
 * simply records sent frames and lets assertions push incoming frames
 * without a real network.
 *
 * <p>Thread-safety: implementations must accept {@link #send(String)}
 * from any thread and must deliver inbound frames on a single serial
 * executor (not necessarily the caller's thread).
 */
public interface RealtimeTransport extends AutoCloseable {

    /** Open the underlying WebSocket connection. Blocks until open or fails. */
    void connect() throws RealtimeException;

    /** Send a JSON text frame to the remote endpoint. */
    void send(String json);

    /** Send a binary frame (used for raw PCM16 payloads if the provider supports it). */
    default void sendBinary(byte[] data) {
        throw new UnsupportedOperationException("binary frames not supported by this transport");
    }

    /** Register a listener invoked for every inbound text frame. */
    void onText(Consumer<String> listener);

    /** Register a listener invoked whenever the transport terminates. */
    void onClose(Consumer<String> listener);

    /** Register a listener invoked on transport errors. */
    void onError(Consumer<Throwable> listener);

    /** Closes the transport and releases resources. Idempotent. */
    @Override
    void close();
}
