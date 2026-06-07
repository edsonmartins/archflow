package br.com.archflow.api.agui;

import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.archflow.api.agui.AgUiEvent.fields;

/**
 * AG-UI agent endpoint with token-level streaming (ADR-0003 / design-0006): a
 * chat assistant exposed over AG-UI so a CopilotKit sidebar renders tokens as
 * they are generated.
 *
 * <p>Uses a {@link StreamingChatModel} (resolved tenant-aware via the
 * {@link LLMConfigResolver}) whose handler is async, so we kick off the stream
 * and return the {@link SseEmitter} immediately; tokens flow as
 * {@code TEXT_MESSAGE_CONTENT}. Sequence: RUN_STARTED → TEXT_MESSAGE_START →
 * TEXT_MESSAGE_CONTENT* → TEXT_MESSAGE_END → RUN_FINISHED (or RUN_ERROR).
 *
 * <p>This streaming path is single-turn chat. The buffered ConversationalAgent
 * (text-based tool loop + guardrails) can't stream user-facing tokens while still
 * parsing tool calls from the same text; native tool-calling + streaming is the
 * follow-up.
 */
@RestController
@RequestMapping("/ag-ui")
public class AgUiAgentController {

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are the archflow assistant. Be concise and helpful.";

    private final LLMConfigResolver llmConfigResolver;
    private final ResolvedLLMConfig platformDefault;
    private final ObjectMapper json;

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
        String messageId = "msg-" + UUID.randomUUID().toString().substring(0, 8);

        emit(sse, lock, AgUiEvent.of("RUN_STARTED", fields("threadId", threadId, "runId", runId)));

        try {
            StreamingChatModel model = llmConfigResolver.resolveStreamingModel(
                    LLMResolutionRequest.builder(platformDefault).build());

            emit(sse, lock, AgUiEvent.of("TEXT_MESSAGE_START", fields("messageId", messageId, "role", "assistant")));

            model.chat(buildMessages(input), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    emit(sse, lock, AgUiEvent.of("TEXT_MESSAGE_CONTENT",
                            fields("messageId", messageId, "delta", token)));
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    synchronized (lock) {
                        writeQuietly(sse, AgUiEvent.of("TEXT_MESSAGE_END", fields("messageId", messageId)));
                        writeQuietly(sse, AgUiEvent.of("RUN_FINISHED", fields("threadId", threadId, "runId", runId,
                                "result", Map.of("status", "COMPLETED"))));
                    }
                    sse.complete();
                }

                @Override
                public void onError(Throwable error) {
                    String message = error.getMessage() != null ? error.getMessage() : error.toString();
                    emit(sse, lock, AgUiEvent.of("RUN_ERROR", fields("runId", runId, "message", message)));
                    sse.complete();
                }
            });
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            emit(sse, lock, AgUiEvent.of("RUN_ERROR", fields("runId", runId, "message", message)));
            sse.complete();
        }

        return sse;
    }

    /** Full conversation: a system message (prompt + useAgentContext) then the history. */
    private List<ChatMessage> buildMessages(RunAgentInput input) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(DEFAULT_SYSTEM_PROMPT + contextBlock(input)));

        if (input != null && input.messages() != null) {
            for (Map<String, Object> m : input.messages()) {
                String role = String.valueOf(m.get("role"));
                Object content = m.get("content");
                String text = content == null ? "" : content.toString();
                if (text.isBlank()) {
                    continue;
                }
                if ("assistant".equals(role)) {
                    messages.add(AiMessage.from(text));
                } else if (!"system".equals(role)) {
                    messages.add(UserMessage.from(text)); // user / tool / other → user turn
                }
            }
        }
        if (messages.size() == 1) {
            messages.add(UserMessage.from("Hello"));
        }
        return messages;
    }

    /** Renders useAgentContext entries ({description, value}) into the system prompt. */
    private String contextBlock(RunAgentInput input) {
        if (input == null || input.context() == null || input.context().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nContext about the app and the user's screen:");
        for (Map<String, Object> entry : input.context()) {
            Object desc = entry.get("description");
            Object value = entry.get("value");
            sb.append("\n- ").append(desc).append(": ").append(value);
        }
        return sb.toString();
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
