package br.com.archflow.agent.streaming.domain;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Domain event factories")
class DomainEventsTest {

    @Nested
    @DisplayName("ChatEvent")
    class ChatEventTests {
        @Test void delta() {
            ArchflowEvent e = ChatEvent.delta("hello", "exec-1");
            assertThat(e.getDomain()).isEqualTo(ArchflowDomain.CHAT);
            assertThat(e.getType()).isEqualTo(ArchflowEventType.DELTA);
            assertThat(e.getData("content")).isEqualTo("hello");
            assertThat(e.getExecutionId()).isEqualTo("exec-1");
        }
        @Test void deltaWithIndex() {
            ArchflowEvent e = ChatEvent.delta("chunk", "exec-1", 3);
            assertThat(e.getData("index")).isEqualTo(3);
        }
        @Test void message() {
            ArchflowEvent e = ChatEvent.message("Hello World!", "assistant", "gpt-4o");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.MESSAGE);
            assertThat(e.getData("role")).isEqualTo("assistant");
            assertThat(e.getData("model")).isEqualTo("gpt-4o");
        }
        @Test void messageWithTokens() {
            ArchflowEvent e = ChatEvent.message("Hi", "assistant", "gpt-4o", 150, Map.of("finishReason", "stop"));
            assertThat(e.getData("totalTokens")).isEqualTo(150);
            assertThat(e.getData("finishReason")).isEqualTo("stop");
        }
        @Test void start() {
            ArchflowEvent e = ChatEvent.start("exec-1", "gpt-4o");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.START);
        }
        @Test void startWithParams() {
            ArchflowEvent e = ChatEvent.start("exec-1", "gpt-4o", 0.7, 2048);
            assertThat(e.getData("temperature")).isEqualTo(0.7);
            assertThat(e.getData("maxTokens")).isEqualTo(2048);
        }
        @Test void end() {
            ArchflowEvent e = ChatEvent.end("exec-1", "stop");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.END);
            assertThat(e.getData("finishReason")).isEqualTo("stop");
        }
        @Test void endWithTokenStats() {
            ArchflowEvent e = ChatEvent.end("exec-1", "stop", 200, 50, 150);
            assertThat(e.getData("totalTokens")).isEqualTo(200);
            assertThat(e.getData("promptTokens")).isEqualTo(50);
            assertThat(e.getData("completionTokens")).isEqualTo(150);
        }
        @Test void error() {
            ArchflowEvent e = ChatEvent.error("exec-1", "timeout");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.ERROR);
            assertThat(e.getData("error")).isEqualTo("timeout");
        }
        @Test void errorFromThrowable() {
            ArchflowEvent e = ChatEvent.error("exec-1", new RuntimeException("boom"));
            assertThat(e.getData("errorType")).isEqualTo("RuntimeException");
        }
    }

    @Nested
    @DisplayName("ToolEvent")
    class ToolEventTests {
        @Test void start() {
            ArchflowEvent e = ToolEvent.start("search", Map.of("query", "java"), "exec-1");
            assertThat(e.getDomain()).isEqualTo(ArchflowDomain.TOOL);
            assertThat(e.getType()).isEqualTo(ArchflowEventType.TOOL_START);
            assertThat(e.getData("toolName")).isEqualTo("search");
        }
        @Test void startWithCallId() {
            ArchflowEvent e = ToolEvent.start("search", "tc-1", Map.of("q", "x"), "exec-1");
            assertThat(e.getData("toolCallId")).isEqualTo("tc-1");
        }
        @Test void progress() {
            ArchflowEvent e = ToolEvent.progress("search", "Searching...", 50, "exec-1");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.PROGRESS);
            assertThat(e.getData("percentage")).isEqualTo(50);
        }
        @Test void progressWithTotals() {
            ArchflowEvent e = ToolEvent.progress("search", "msg", 50, 100, 200, "exec-1");
            assertThat(e.getData("current")).isEqualTo(100L);
            assertThat(e.getData("total")).isEqualTo(200L);
        }
        @Test void result() {
            ArchflowEvent e = ToolEvent.result("search", Map.of("results", 5), "exec-1");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.RESULT);
        }
        @Test void resultWithCallIdAndDuration() {
            ArchflowEvent e = ToolEvent.result("search", "tc-1", "data", 245L, "exec-1");
            assertThat(e.getData("durationMs")).isEqualTo(245L);
        }
        @Test void error() {
            ArchflowEvent e = ToolEvent.error("search", "not found", "exec-1");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.TOOL_ERROR);
        }
        @Test void errorFromThrowable() {
            ArchflowEvent e = ToolEvent.error("search", "tc-1", new RuntimeException("boom"), "exec-1");
            assertThat(e.getData("errorType")).isEqualTo("RuntimeException");
        }
        @Test void end() {
            ArchflowEvent e = ToolEvent.end("search", "exec-1");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.END);
        }
        @Test void endWithStats() {
            ArchflowEvent e = ToolEvent.end("search", "tc-1", 300L, true, "exec-1");
            assertThat(e.getData("durationMs")).isEqualTo(300L);
            assertThat(e.getData("success")).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("SystemEvent")
    class SystemEventTests {
        @Test void connected() {
            ArchflowEvent e = SystemEvent.connected("client-1", "session-1");
            assertThat(e.getDomain()).isEqualTo(ArchflowDomain.SYSTEM);
            assertThat(e.getType()).isEqualTo(ArchflowEventType.CONNECTED);
            assertThat(e.getData("clientId")).isEqualTo("client-1");
        }
        @Test void heartbeat() {
            ArchflowEvent e = SystemEvent.heartbeat();
            assertThat(e.getType()).isEqualTo(ArchflowEventType.HEARTBEAT);
        }
        @Test void error() {
            ArchflowEvent e = SystemEvent.error("DB down", "CONNECTION_ERROR");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.ERROR);
            assertThat(e.getData("error")).isEqualTo("DB down");
        }
    }

    @Nested
    @DisplayName("AuditEvent (streaming)")
    class AuditEventTests {
        @Test void trace() {
            ArchflowEvent e = AuditEvent.trace("Step completed", "exec-1", AuditEvent.LogLevel.INFO);
            assertThat(e.getDomain()).isEqualTo(ArchflowDomain.AUDIT);
            assertThat(e.getType()).isEqualTo(ArchflowEventType.TRACE);
        }
        @Test void metric() {
            ArchflowEvent e = AuditEvent.metric("latency", 42.0, "ms");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.METRIC);
            assertThat(e.getData("value")).isEqualTo(42.0);
        }
        @Test void log() {
            ArchflowEvent e = AuditEvent.log("msg", "exec-1", AuditEvent.LogLevel.WARN);
            assertThat(e.getType()).isEqualTo(ArchflowEventType.LOG);
        }
        @Test void start() {
            ArchflowEvent e = AuditEvent.start("exec-1", "trace-abc");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.START);
        }
    }

    @Nested
    @DisplayName("ThinkingEvent")
    class ThinkingEventTests {
        @Test void thinking() {
            ArchflowEvent e = ThinkingEvent.thinking("Let me analyze...", "exec-1");
            assertThat(e.getDomain()).isEqualTo(ArchflowDomain.THINKING);
            assertThat(e.getType()).isEqualTo(ArchflowEventType.THINKING);
        }
        @Test void reflection() {
            ArchflowEvent e = ThinkingEvent.reflection("I should reconsider", "exec-1", 1);
            assertThat(e.getType()).isEqualTo(ArchflowEventType.REFLECTION);
        }
        @Test void verification() {
            ArchflowEvent e = ThinkingEvent.verification("check", "exec-1", true);
            assertThat(e.getType()).isEqualTo(ArchflowEventType.VERIFICATION);
        }
    }

    @Nested
    @DisplayName("InteractionEvent")
    class InteractionEventTests {
        @Test void suspend() {
            ArchflowEvent e = InteractionEvent.suspend("exec-1", "Waiting", java.time.Duration.ofMinutes(5));
            assertThat(e.getDomain()).isEqualTo(ArchflowDomain.INTERACTION);
            assertThat(e.getType()).isEqualTo(ArchflowEventType.SUSPEND);
        }
        @Test void resume() {
            ArchflowEvent e = InteractionEvent.resume("exec-1", "token-123");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.RESUME);
        }
        @Test void cancel() {
            ArchflowEvent e = InteractionEvent.cancel("exec-1");
            assertThat(e.getType()).isEqualTo(ArchflowEventType.CANCEL);
        }
        @Test void form() {
            ArchflowEvent e = InteractionEvent.form("form-1", Map.of("name", Map.of("type", "text")));
            assertThat(e.getType()).isEqualTo(ArchflowEventType.FORM);
        }
    }

    @Test
    @DisplayName("all events produce valid JSON")
    void allEventsProduceValidJson() {
        ArchflowEvent[] events = {
            ChatEvent.delta("x", "e1"),
            ChatEvent.message("x", "user", "gpt"),
            ToolEvent.start("t", Map.of(), "e1"),
            ToolEvent.result("t", "data", "e1"),
            SystemEvent.heartbeat(),
            AuditEvent.trace("msg", "e1", AuditEvent.LogLevel.INFO),
            ThinkingEvent.thinking("hmm", "e1"),
            InteractionEvent.suspend("e1", "wait", java.time.Duration.ofMinutes(1)),
        };
        for (ArchflowEvent e : events) {
            String json = e.toJson();
            assertThat(json).isNotBlank();
            assertThat(json).startsWith("{");
            assertThat(json).contains("\"domain\"");
            assertThat(json).contains("\"type\"");
        }
    }
}
