package br.com.archflow.agent.streaming;

import br.com.archflow.agent.streaming.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes do protocolo de streaming.
 */
class StreamingProtocolTest {

    @Test
    void testChatEventDelta() {
        ArchflowEvent event = ChatEvent.delta("Hello, world!", "exec_123");

        assertEquals(ArchflowDomain.CHAT, event.getDomain());
        assertEquals(ArchflowEventType.DELTA, event.getType());
        assertEquals("exec_123", event.getExecutionId());
        assertEquals("Hello, world!", event.getData("content"));
    }

    @Test
    void testChatEventMessageWithTokenStats() {
        ArchflowEvent event = ChatEvent.message(
                "Full response here",
                "assistant",
                "gpt-4",
                150,
                Map.of("finishReason", "stop")
        );

        assertEquals(ArchflowEventType.MESSAGE, event.getType());
        assertEquals("assistant", event.getData("role"));
        assertEquals(150, event.getData("totalTokens"));
        assertEquals("stop", event.getData("finishReason"));
    }

    @Test
    void testChatEventStartAndEnd() {
        // Start
        ArchflowEvent start = ChatEvent.start("exec_123", "gpt-4", 0.7, 1000);
        assertEquals(ArchflowEventType.START, start.getType());
        assertEquals("gpt-4", start.getData("model"));
        assertEquals(0.7, start.getData("temperature"));
        assertEquals(1000, start.getData("maxTokens"));

        // End
        ArchflowEvent end = ChatEvent.end("exec_123", "stop", 150, 50, 100);
        assertEquals(ArchflowEventType.END, end.getType());
        assertEquals("stop", end.getData("finishReason"));
        assertEquals(150, end.getData("totalTokens"));
        assertEquals(50, end.getData("promptTokens"));
        assertEquals(100, end.getData("completionTokens"));
    }

    @Test
    void testThinkingEvent() {
        ArchflowEvent thinking = ThinkingEvent.thinking(
                "Let me analyze this step by step...",
                "exec_123",
                0
        );

        assertEquals(ArchflowDomain.THINKING, thinking.getDomain());
        assertEquals(ArchflowEventType.THINKING, thinking.getType());
        assertEquals(0, thinking.getData("index"));
    }

    @Test
    void testThinkingEventReflection() {
        ArchflowEvent reflection = ThinkingEvent.reflection(
                "I need to reconsider the approach",
                "exec_123",
                1,
                "analysis"
        );

        assertEquals(ArchflowEventType.REFLECTION, reflection.getType());
        assertEquals(1, reflection.getData("stepNumber"));
        assertEquals("analysis", reflection.getData("reasoningType"));
    }

    @Test
    void testThinkingEventVerification() {
        ArchflowEvent verification = ThinkingEvent.verification(
                "Result validated successfully",
                "exec_123",
                true,
                0.95
        );

        assertEquals(ArchflowEventType.VERIFICATION, verification.getType());
        assertEquals(true, verification.getData("passed"));
        assertEquals(0.95, verification.getData("confidenceScore"));
    }

    @Test
    void testToolEventStart() {
        Map<String, Object> input = Map.of("query", "Java programming");
        ArchflowEvent start = ToolEvent.start("search", "call_123", input, "exec_123");

        assertEquals(ArchflowDomain.TOOL, start.getDomain());
        assertEquals(ArchflowEventType.TOOL_START, start.getType());
        assertEquals("search", start.getData("toolName"));
        assertEquals("call_123", start.getData("toolCallId"));
    }

    @Test
    void testToolEventProgress() {
        ArchflowEvent progress = ToolEvent.progress(
                "search",
                "Searching database...",
                50,
                500L,
                1000L,
                "exec_123"
        );

        assertEquals(ArchflowEventType.PROGRESS, progress.getType());
        assertEquals(50, progress.getData("percentage"));
        assertEquals(500L, progress.getData("current"));
        assertEquals(1000L, progress.getData("total"));
    }

    @Test
    void testToolEventResult() {
        Map<String, Object> result = Map.of(
                "results", List.of("item1", "item2"),
                "count", 2
        );
        ArchflowEvent toolResult = ToolEvent.result(
                "search",
                "call_123",
                result,
                1500L,
                "exec_123"
        );

        assertEquals(ArchflowEventType.RESULT, toolResult.getType());
        assertEquals(1500L, toolResult.getData("durationMs"));
        assertNotNull(toolResult.getData("result"));
    }

    @Test
    void testAuditEventTrace() {
        ArchflowEvent trace = AuditEvent.trace(
                "Entering workflow step",
                "exec_123",
                AuditEvent.LogLevel.INFO,
                "FlowEngine"
        );

        assertEquals(ArchflowDomain.AUDIT, trace.getDomain());
        assertEquals(ArchflowEventType.TRACE, trace.getType());
        assertEquals("INFO", trace.getData("level"));
        assertEquals("FlowEngine", trace.getData("component"));
    }

    @Test
    void testAuditEventSpan() {
        ArchflowEvent span = AuditEvent.span(
                "llm_call",
                "exec_123",
                "parent_span",
                0L,
                1500L,
                Map.of("model", "gpt-4", "tokens", 150)
        );

        assertEquals(ArchflowEventType.SPAN, span.getType());
        assertEquals("llm_call", span.getData("spanName"));
        assertEquals(1500L, span.getData("duration"));
        assertEquals("gpt-4", span.getData("model"));
    }

    @Test
    void testAuditEventMetric() {
        ArchflowEvent metric = AuditEvent.metric(
                "tool_duration",
                500.0,
                "ms",
                "exec_123",
                new String[]{"tool:search", "status:success"}
        );

        assertEquals(ArchflowEventType.METRIC, metric.getType());
        assertEquals("tool_duration", metric.getData("name"));
        assertEquals(500.0, metric.getData("value"));
        assertEquals("ms", metric.getData("unit"));
    }

    @Test
    void testInteractionEventSuspend() {
        ArchflowEvent suspend = InteractionEvent.suspend(
                "exec_123",
                "Need user confirmation",
                Duration.ofMinutes(5)
        );

        assertEquals(ArchflowDomain.INTERACTION, suspend.getDomain());
        assertEquals(ArchflowEventType.SUSPEND, suspend.getType());
        assertEquals("Need user confirmation", suspend.getData("reason"));
        assertNotNull(suspend.getData("resumeToken"));
        assertTrue((Long) suspend.getData("timeoutMs") > 0);
    }

    @Test
    void testInteractionEventForm() {
        Map<String, Object> fields = Map.of(
                "email", Map.of("type", "email", "required", true),
                "name", Map.of("type", "text", "required", true)
        );

        ArchflowEvent form = InteractionEvent.form(
                "user_info",
                "User Information",
                "Please fill in your details",
                fields,
                "exec_123",
                60000L
        );

        assertEquals(ArchflowEventType.FORM, form.getType());
        assertEquals("user_info", form.getData("formId"));
        assertEquals("User Information", form.getData("title"));
        assertNotNull(form.getData("fields"));
    }

    @Test
    void testInteractionEventResume() {
        Map<String, Object> userData = Map.of(
                "email", "user@example.com",
                "name", "John Doe"
        );

        ArchflowEvent resume = InteractionEvent.resume("exec_123", "resume_token_abc", userData);

        assertEquals(ArchflowEventType.RESUME, resume.getType());
        assertEquals("resume_token_abc", resume.getData("resumeToken"));
        assertNotNull(resume.getData("userData"));
    }

    @Test
    void testSystemEventConnected() {
        ArchflowEvent connected = SystemEvent.connected(
                "client_123",
                "session_abc",
                "Mozilla/5.0",
                "192.168.1.1"
        );

        assertEquals(ArchflowDomain.SYSTEM, connected.getDomain());
        assertEquals(ArchflowEventType.CONNECTED, connected.getType());
        assertEquals("client_123", connected.getData("clientId"));
        assertEquals("192.168.1.1", connected.getData("ipAddress"));
    }

    @Test
    void testSystemEventHeartbeat() {
        ArchflowEvent heartbeat = SystemEvent.heartbeat("session_abc");

        assertEquals(ArchflowEventType.HEARTBEAT, heartbeat.getType());
        assertEquals("session_abc", heartbeat.getData("sessionId"));
        assertNotNull(heartbeat.getData("timestamp"));
    }

    @Test
    void testEventStreamEmitter() {
        EventStreamEmitter emitter = new EventStreamEmitter("exec_123");

        assertEquals("exec_123", emitter.getExecutionId());
        assertFalse(emitter.isCompleted());

        // Test attribute
        emitter.setAttribute("key", "value");
        assertEquals("value", emitter.getAttribute("key"));
    }

    @Test
    void testEventStreamRegistry() {
        EventStreamRegistry registry = new EventStreamRegistry(1000, 5000);

        // Create emitter
        EventStreamEmitter emitter = registry.createEmitter("exec_123");
        assertNotNull(emitter);
        assertEquals("exec_123", emitter.getExecutionId());

        // Broadcast
        int sent = registry.broadcastDelta("exec_123", "Hello");
        assertEquals(1, sent);

        // Stats
        EventStreamRegistry.RegistryStats stats = registry.getStats();
        assertTrue(stats.totalEmitters() > 0);

        // Cleanup
        registry.shutdown();
    }

    @Test
    void testEventToJson() {
        ArchflowEvent event = ChatEvent.delta("Hello", "exec_123");
        String json = event.toJson();

        assertTrue(json.contains("\"domain\":\"chat\""));
        assertTrue(json.contains("\"type\":\"delta\""));
        assertTrue(json.contains("\"content\":\"Hello\""));
        assertTrue(json.contains("\"executionId\":\"exec_123\""));
    }

    @Test
    void testEventBuilderWithCorrelationId() {
        String correlationId = "corr_abc_123";

        ArchflowEvent event = ArchflowEvent.builder()
                .domain(ArchflowDomain.CHAT)
                .type(ArchflowEventType.DELTA)
                .correlationId(correlationId)
                .addData("content", "test")
                .build();

        assertEquals(correlationId, event.getCorrelationId());
    }
}
