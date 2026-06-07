package br.com.archflow.api.agui;

import br.com.archflow.conversation.agent.ConversationalAgent;
import br.com.archflow.conversation.agent.DefaultToolRegistry;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static br.com.archflow.api.agui.AgUiEvent.fields;

/**
 * AG-UI agent endpoint backed by the {@link ConversationalAgent} (ADR-0003 /
 * design-0006): a real chat-with-tools assistant exposed over AG-UI, so a
 * CopilotKit sidebar becomes an actual copilot (not just a workflow trigger).
 *
 * <p>The ConversationalAgent is synchronous (guardrail-in → LLM ↔ tool loop →
 * guardrail-out → final Result), so we run it off the request thread and emit
 * the AG-UI sequence: RUN_STARTED → TEXT_MESSAGE_* (the reply) → CUSTOM
 * (tools used) → RUN_FINISHED. Token-level streaming is a follow-up (the
 * ChatFunction returns a full reply).
 */
@RestController
@RequestMapping("/ag-ui")
public class AgUiAgentController {

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are the archflow assistant. Be concise and helpful.";

    private final LLMConfigResolver llmConfigResolver;
    private final ResolvedLLMConfig platformDefault;
    private final ObjectMapper json;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AgUiAgentController(LLMConfigResolver llmConfigResolver,
                              ResolvedLLMConfig platformDefaultLLMConfig,
                              ObjectMapper jackson2ObjectMapper) {
        this.llmConfigResolver = llmConfigResolver;
        this.platformDefault = platformDefaultLLMConfig;
        this.json = jackson2ObjectMapper;
    }

    @PostMapping(value = "/agent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agent(@RequestBody(required = false) RunAgentInput input) {
        SseEmitter sse = new SseEmitter(0L);
        Object lock = new Object();

        String runId = input != null && input.runId() != null ? input.runId()
                : "run-" + UUID.randomUUID().toString().substring(0, 8);
        String threadId = input != null && input.threadId() != null ? input.threadId() : runId;
        String userMessage = lastUserMessage(input);

        executor.submit(() -> {
            emit(sse, lock, AgUiEvent.of("RUN_STARTED", fields("threadId", threadId, "runId", runId)));
            try {
                ChatModel model = llmConfigResolver.resolveModel(
                        LLMResolutionRequest.builder(platformDefault).build());
                ConversationalAgent agent = ConversationalAgent.builder()
                        .systemPrompt(DEFAULT_SYSTEM_PROMPT)
                        .maxIterations(6)
                        .tools(new DefaultToolRegistry())
                        .chat(model::chat)
                        .build();

                ConversationalAgent.Result result = agent.chat(userMessage == null ? "" : userMessage);

                String messageId = "msg-" + UUID.randomUUID().toString().substring(0, 8);
                synchronized (lock) {
                    writeQuietly(sse, AgUiEvent.of("TEXT_MESSAGE_START", fields("messageId", messageId, "role", "assistant")));
                    writeQuietly(sse, AgUiEvent.of("TEXT_MESSAGE_CONTENT", fields("messageId", messageId, "delta", result.reply())));
                    writeQuietly(sse, AgUiEvent.of("TEXT_MESSAGE_END", fields("messageId", messageId)));
                    if (result.toolsUsed() != null && !result.toolsUsed().isEmpty()) {
                        writeQuietly(sse, AgUiEvent.of("CUSTOM", fields("name", "tools_used", "value", result.toolsUsed())));
                    }
                    writeQuietly(sse, AgUiEvent.of("RUN_FINISHED", fields("threadId", threadId, "runId", runId,
                            "result", Map.of(
                                    "status", result.blocked() ? "BLOCKED" : "COMPLETED",
                                    "iterations", result.iterations(),
                                    "toolsUsed", result.toolsUsed() == null ? List.of() : result.toolsUsed()))));
                }
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                emit(sse, lock, AgUiEvent.of("RUN_ERROR", fields("runId", runId, "message", message)));
            } finally {
                sse.complete();
            }
        });

        return sse;
    }

    private String lastUserMessage(RunAgentInput input) {
        if (input == null || input.messages() == null || input.messages().isEmpty()) {
            return null;
        }
        for (int i = input.messages().size() - 1; i >= 0; i--) {
            Object content = input.messages().get(i).get("content");
            if (content != null) {
                return content.toString();
            }
        }
        return null;
    }

    private void emit(SseEmitter sse, Object lock, AgUiEvent event) {
        synchronized (lock) {
            writeQuietly(sse, event);
        }
    }

    private void writeQuietly(SseEmitter sse, AgUiEvent event) {
        try {
            sse.send(SseEmitter.event().data(json.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // client gone / stream closed
        }
    }
}
