package br.com.archflow.langchain4j.realtime.openai;

import br.com.archflow.langchain4j.realtime.spi.RealtimeException;
import br.com.archflow.langchain4j.realtime.spi.RealtimeTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Production {@link RealtimeTransport} backed by the JDK 11+ built-in
 * WebSocket client. No external dependencies needed.
 *
 * <p>Handles text fragmentation by accumulating chunks until
 * {@code last == true} is delivered, then forwards the complete JSON
 * string to the registered text listener.
 */
public class JdkWebSocketTransport implements RealtimeTransport {

    private static final Logger log = LoggerFactory.getLogger(JdkWebSocketTransport.class);

    private final URI endpoint;
    private final String apiKey;
    private final HttpClient httpClient;

    private volatile WebSocket webSocket;
    private volatile Consumer<String> textListener = t -> {};
    private volatile Consumer<String> closeListener = r -> {};
    private volatile Consumer<Throwable> errorListener = err -> {};
    private final StringBuilder textBuffer = new StringBuilder();

    public JdkWebSocketTransport(URI endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void connect() throws RealtimeException {
        try {
            WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .header("OpenAI-Beta", "realtime=v1");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            webSocket = builder.buildAsync(endpoint, new Listener(failure))
                    .get();
            if (failure.get() != null) {
                throw new RealtimeException("WebSocket handshake failed", failure.get());
            }
        } catch (RealtimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RealtimeException("Failed to open WebSocket to " + endpoint, e);
        }
    }

    @Override
    public void send(String json) {
        if (webSocket == null) {
            throw new IllegalStateException("transport not connected");
        }
        webSocket.sendText(json, true);
    }

    @Override
    public void sendBinary(byte[] data) {
        if (webSocket == null) {
            throw new IllegalStateException("transport not connected");
        }
        webSocket.sendBinary(ByteBuffer.wrap(data), true);
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
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {
            }
            webSocket = null;
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final AtomicReference<Throwable> failure;

        Listener(AtomicReference<Throwable> failure) {
            this.failure = failure;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String full = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    textListener.accept(full);
                } catch (Exception e) {
                    log.warn("text listener threw", e);
                }
            }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            try {
                closeListener.accept(reason);
            } catch (Exception e) {
                log.warn("close listener threw", e);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            failure.set(error);
            try {
                errorListener.accept(error);
            } catch (Exception e) {
                log.warn("error listener threw", e);
            }
        }
    }
}
