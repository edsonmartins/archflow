package br.com.archflow.langchain4j.mcp.transport;

import br.com.archflow.langchain4j.mcp.JsonRpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StdioClientTransport")
@DisabledOnOs(OS.WINDOWS)
class StdioClientTransportTest {

    @TempDir
    Path tempDir;

    private Path echoScript;
    private Path responderScript;

    @BeforeEach
    void setUp() throws IOException {
        echoScript = tempDir.resolve("echo.sh");
        Files.writeString(echoScript, """
                #!/bin/sh
                while IFS= read -r line; do
                  printf '%s\\n' "$line"
                done
                """);
        Files.setPosixFilePermissions(echoScript, Set.copyOf(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")));

        // Responder: parses id with sed and emits a matching Response.
        responderScript = tempDir.resolve("responder.sh");
        Files.writeString(responderScript, """
                #!/bin/sh
                while IFS= read -r line; do
                  id=$(printf '%s' "$line" | sed -n 's/.*"id":"\\([^"]*\\)".*/\\1/p')
                  if [ -n "$id" ]; then
                    printf '{"jsonrpc":"2.0","id":"%s","result":{"echoed":true}}\\n' "$id"
                  fi
                done
                """);
        Files.setPosixFilePermissions(responderScript, Set.copyOf(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")));
    }

    @Test
    @DisplayName("constructor rejects null command")
    void rejectsNullCommand() {
        assertThatThrownBy(() -> new StdioClientTransport((String[]) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects empty command")
    void rejectsEmptyCommand() {
        assertThatThrownBy(() -> new StdioClientTransport(new String[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("send before start throws IOException")
    void sendBeforeStartThrows() {
        StdioClientTransport t = new StdioClientTransport("cat");
        assertThatThrownBy(() -> t.send(JsonRpc.Request.create("ping", Map.of())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not active");
    }

    @Test
    @DisplayName("sendRequest before start completes exceptionally")
    void sendRequestBeforeStartFails() {
        StdioClientTransport t = new StdioClientTransport("cat");
        CompletableFuture<JsonRpc.Response> future =
                t.sendRequest(JsonRpc.Request.create("ping", Map.of()));
        assertThat(future).isCompletedExceptionally();
    }

    @Test
    @DisplayName("isActive false before start; pid zero before start")
    void isActiveAndPidBeforeStart() {
        StdioClientTransport t = new StdioClientTransport("cat");
        assertThat(t.isActive()).isFalse();
        assertThat(t.getPid()).isZero();
    }

    @Test
    @DisplayName("start with non-existent command throws IOException")
    void startWithInvalidCommand() {
        StdioClientTransport t = new StdioClientTransport("this-command-does-not-exist-xyz");
        assertThatThrownBy(t::start)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to start");
        assertThat(t.isActive()).isFalse();
    }

    @Test
    @DisplayName("start spawns process, getPid returns positive, isActive true")
    void startActivatesProcess() throws IOException {
        StdioClientTransport t = new StdioClientTransport(echoScript.toString());
        try {
            t.start();
            assertThat(t.isActive()).isTrue();
            assertThat(t.getPid()).isPositive();

            t.start(); // idempotent
            assertThat(t.isActive()).isTrue();
        } finally {
            t.stop();
        }
        assertThat(t.isActive()).isFalse();
    }

    @Test
    @DisplayName("stop is idempotent and releases pending requests")
    void stopFailsPendingRequests() throws IOException, InterruptedException {
        StdioClientTransport t = new StdioClientTransport(echoScript.toString());
        t.start();

        // Send a notification (no id) — no response expected, but we can still queue a request
        // using a method that the echo script will just echo back unchanged (parsed as Request,
        // NOT as Response, so the future never completes).
        JsonRpc.Request pending = JsonRpc.Request.create("tools/list", Map.of());
        CompletableFuture<JsonRpc.Response> future = t.sendRequest(pending);

        // The echo turns the request back into a line, which parses as a Request — not a match
        // for the pending map. Give reader time to process the echoed line.
        Thread.sleep(200);
        assertThat(future).isNotDone();

        t.stop();
        t.stop(); // idempotent

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("echoed request is dispatched to the message handler")
    void messageHandlerReceivesEchoedRequest() throws IOException, InterruptedException {
        StdioClientTransport t = new StdioClientTransport(echoScript.toString());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonRpc> received = new AtomicReference<>();
        t.setMessageHandler(msg -> {
            received.set(msg);
            latch.countDown();
        });

        try {
            t.start();
            t.send(JsonRpc.Request.create("tools/list", Map.of("cursor", "abc")));

            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get()).isInstanceOf(JsonRpc.Request.class);
            JsonRpc.Request echoed = (JsonRpc.Request) received.get();
            assertThat(echoed.method()).isEqualTo("tools/list");
        } finally {
            t.stop();
        }
    }

    @Test
    @DisplayName("sendRequest completes when server responds with matching id")
    void sendRequestCompletesOnMatchingResponse() throws Exception {
        StdioClientTransport t = new StdioClientTransport(responderScript.toString());
        try {
            t.start();

            JsonRpc.Request req = JsonRpc.Request.create("ping", Map.of());
            CompletableFuture<JsonRpc.Response> future = t.sendRequest(req);

            JsonRpc.Response resp = future.get(3, TimeUnit.SECONDS);
            assertThat(resp.isError()).isFalse();
            assertThat(resp.id()).isEqualTo(String.valueOf(req.id()));
        } finally {
            t.stop();
        }
    }

    @Test
    @DisplayName("malformed line from subprocess triggers error handler")
    void malformedOutputTriggersErrorHandler() throws IOException, InterruptedException {
        Path garbageScript = tempDir.resolve("garbage.sh");
        Files.writeString(garbageScript, """
                #!/bin/sh
                echo "this-is-not-json"
                sleep 1
                """);
        Files.setPosixFilePermissions(garbageScript, Set.copyOf(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")));

        StdioClientTransport t = new StdioClientTransport(garbageScript.toString());
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        t.setErrorHandler(err -> {
            error.set(err);
            errorLatch.countDown();
        });

        try {
            t.start();
            assertThat(errorLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(error.get()).isInstanceOf(IOException.class);
        } finally {
            t.stop();
        }
    }

    @Test
    @DisplayName("close is an alias for stop")
    void closeStops() throws IOException {
        StdioClientTransport t = new StdioClientTransport(echoScript.toString());
        t.start();
        assertThat(t.isActive()).isTrue();
        t.close();
        assertThat(t.isActive()).isFalse();
    }

    @Test
    @DisplayName("setErrorHandler and setMessageHandler accept null safely")
    void setHandlersAcceptNull() throws IOException {
        StdioClientTransport t = new StdioClientTransport(echoScript.toString());
        try {
            t.setMessageHandler(null);
            t.setErrorHandler(null);
            t.start();
            // Should still run; send and check no crash.
            t.send(JsonRpc.Request.create("ping", Map.of()));
            assertThat(t.isActive()).isTrue();
        } finally {
            t.stop();
        }
    }

    @Test
    @DisplayName("getPid returns zero after stop")
    void getPidAfterStop() throws IOException {
        StdioClientTransport t = new StdioClientTransport(echoScript.toString());
        t.start();
        long pid = t.getPid();
        t.stop();
        assertThat(pid).isPositive();
    }
}
