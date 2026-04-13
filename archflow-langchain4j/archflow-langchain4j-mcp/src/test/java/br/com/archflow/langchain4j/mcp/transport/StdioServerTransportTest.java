package br.com.archflow.langchain4j.mcp.transport;

import br.com.archflow.langchain4j.mcp.JsonRpc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StdioServerTransport")
class StdioServerTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private PipedWriter clientWriter;
    private BufferedReader serverInput;
    private ByteArrayOutputStream outputBytes;
    private PrintWriter serverOutput;
    private StdioServerTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        clientWriter = new PipedWriter();
        PipedReader pipedReader = new PipedReader(clientWriter);
        serverInput = new BufferedReader(pipedReader);

        outputBytes = new ByteArrayOutputStream();
        serverOutput = new PrintWriter(outputBytes, true);

        transport = new StdioServerTransport(serverInput, serverOutput, mapper);
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.close();
    }

    @Test
    @DisplayName("default constructor wires System.in/out without error")
    void defaultConstructor() {
        StdioServerTransport defaultTransport = new StdioServerTransport();
        try {
            assertThat(defaultTransport.isActive()).isFalse();
        } finally {
            defaultTransport.close();
        }
    }

    @Test
    @DisplayName("two-arg constructor uses default ObjectMapper")
    void twoArgConstructor() {
        StdioServerTransport t = new StdioServerTransport(serverInput, serverOutput);
        try {
            assertThat(t.isActive()).isFalse();
        } finally {
            t.close();
        }
    }

    @Test
    @DisplayName("start activates and stop deactivates")
    void startStopLifecycle() throws IOException {
        assertThat(transport.isActive()).isFalse();

        transport.start();
        assertThat(transport.isActive()).isTrue();

        transport.start(); // idempotent
        assertThat(transport.isActive()).isTrue();

        transport.stop();
        assertThat(transport.isActive()).isFalse();

        transport.stop(); // idempotent
    }

    @Test
    @DisplayName("send before start throws IOException")
    void sendBeforeStartThrows() {
        JsonRpc.Request req = JsonRpc.Request.create("ping", Map.of());
        assertThatThrownBy(() -> transport.send(req))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not active");
    }

    @Test
    @DisplayName("send writes JSON message to output")
    void sendWritesJson() throws IOException {
        transport.start();

        JsonRpc.Response resp = JsonRpc.Response.success("req-1", Map.of("ok", true));
        transport.send(resp);

        String written = outputBytes.toString();
        assertThat(written).contains("\"id\":\"req-1\"");
        assertThat(written).contains("\"jsonrpc\":\"2.0\"");
        assertThat(written).contains("\"ok\":true");
    }

    @Test
    @DisplayName("incoming request is dispatched to the message handler")
    void readLoopDispatchesRequest() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonRpc> received = new AtomicReference<>();
        transport.setMessageHandler(msg -> {
            received.set(msg);
            latch.countDown();
        });
        transport.start();

        clientWriter.write("{\"jsonrpc\":\"2.0\",\"id\":\"42\",\"method\":\"tools/list\",\"params\":{}}\n");
        clientWriter.flush();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).isInstanceOf(JsonRpc.Request.class);
        JsonRpc.Request r = (JsonRpc.Request) received.get();
        assertThat(r.method()).isEqualTo("tools/list");
        assertThat(r.id()).isEqualTo("42");
    }

    @Test
    @DisplayName("incoming notification is dispatched as Notification")
    void readLoopDispatchesNotification() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonRpc> received = new AtomicReference<>();
        transport.setMessageHandler(msg -> {
            received.set(msg);
            latch.countDown();
        });
        transport.start();

        clientWriter.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}\n");
        clientWriter.flush();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).isInstanceOf(JsonRpc.Notification.class);
    }

    @Test
    @DisplayName("incoming response is dispatched as Response")
    void readLoopDispatchesResponse() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonRpc> received = new AtomicReference<>();
        transport.setMessageHandler(msg -> {
            received.set(msg);
            latch.countDown();
        });
        transport.start();

        clientWriter.write("{\"jsonrpc\":\"2.0\",\"id\":\"99\",\"result\":{\"hello\":\"world\"}}\n");
        clientWriter.flush();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).isInstanceOf(JsonRpc.Response.class);
    }

    @Test
    @DisplayName("blank lines are skipped silently")
    void readLoopSkipsBlankLines() throws Exception {
        ConcurrentLinkedQueue<JsonRpc> received = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(1);
        transport.setMessageHandler(msg -> {
            received.add(msg);
            latch.countDown();
        });
        transport.start();

        clientWriter.write("\n   \n");
        clientWriter.write("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"ping\"}\n");
        clientWriter.flush();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
    }

    @Test
    @DisplayName("malformed JSON triggers the error handler")
    void malformedJsonTriggersErrorHandler() throws Exception {
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        transport.setErrorHandler(err -> {
            error.set(err);
            errorLatch.countDown();
        });
        transport.start();

        clientWriter.write("not-valid-json\n");
        clientWriter.flush();

        assertThat(errorLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("message with neither id nor method triggers error handler")
    void invalidJsonRpcShapeTriggersErrorHandler() throws Exception {
        CountDownLatch errorLatch = new CountDownLatch(1);
        transport.setErrorHandler(err -> errorLatch.countDown());
        transport.start();

        clientWriter.write("{\"jsonrpc\":\"2.0\"}\n");
        clientWriter.flush();

        assertThat(errorLatch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("message handler exceptions are routed to error handler")
    void handlerExceptionRoutedToErrorHandler() throws Exception {
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        transport.setMessageHandler(msg -> {
            throw new RuntimeException("boom");
        });
        transport.setErrorHandler(err -> {
            error.set(err);
            errorLatch.countDown();
        });
        transport.start();

        clientWriter.write("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"ping\"}\n");
        clientWriter.flush();

        assertThat(errorLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).hasMessageContaining("boom");
    }

    @Test
    @DisplayName("message without handler is silently dropped (no error)")
    void noHandlerNoCrash() throws Exception {
        transport.start();
        clientWriter.write("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"ping\"}\n");
        clientWriter.flush();

        // Give the reader a moment; transport must remain active.
        Thread.sleep(100);
        assertThat(transport.isActive()).isTrue();
    }

    @Test
    @DisplayName("close stops the transport")
    void closeStops() throws IOException {
        transport.start();
        assertThat(transport.isActive()).isTrue();
        transport.close();
        assertThat(transport.isActive()).isFalse();
    }
}
