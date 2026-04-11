package br.com.archflow.langchain4j.realtime;

import br.com.archflow.langchain4j.realtime.spi.RealtimeException;
import br.com.archflow.langchain4j.realtime.spi.RealtimeTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * In-memory {@link RealtimeTransport} used by unit tests. Records every
 * sent frame so tests can assert on the protocol, and exposes
 * {@link #receive} and {@link #fail} to simulate inbound events without
 * a real network.
 */
public class FakeRealtimeTransport implements RealtimeTransport {

    public final List<String> sentText = new ArrayList<>();
    public final List<byte[]> sentBinary = new ArrayList<>();
    public boolean connected = false;
    public boolean closed = false;
    public boolean failOnConnect = false;

    private Consumer<String> textListener = t -> {};
    private Consumer<String> closeListener = r -> {};
    private Consumer<Throwable> errorListener = err -> {};

    @Override
    public void connect() throws RealtimeException {
        if (failOnConnect) {
            throw new RealtimeException("forced failure");
        }
        connected = true;
    }

    @Override
    public void send(String json) {
        if (!connected) throw new IllegalStateException("not connected");
        sentText.add(json);
    }

    @Override
    public void sendBinary(byte[] data) {
        if (!connected) throw new IllegalStateException("not connected");
        sentBinary.add(data);
    }

    @Override
    public void onText(Consumer<String> listener) {
        this.textListener = listener != null ? listener : t -> {};
    }

    @Override
    public void onClose(Consumer<String> listener) {
        this.closeListener = listener != null ? listener : r -> {};
    }

    @Override
    public void onError(Consumer<Throwable> listener) {
        this.errorListener = listener != null ? listener : err -> {};
    }

    @Override
    public void close() {
        closed = true;
        closeListener.accept("closed");
    }

    // ── test helpers ───────────────────────────────────────────────

    /** Simulate an inbound text frame from the provider. */
    public void receive(String json) {
        textListener.accept(json);
    }

    /** Simulate an inbound error. */
    public void fail(Throwable err) {
        errorListener.accept(err);
    }
}
