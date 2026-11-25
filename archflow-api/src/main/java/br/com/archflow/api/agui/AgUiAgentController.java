package br.com.archflow.api.agui;

import br.com.archflow.api.trust.UntrustedContentFence;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>Uma requisição é uma chamada ao modelo; o laço de ferramentas fica no browser. Quando uma
 * ferramenta do front tem {@code followUp} (addNode devolve o id do nó, e o agente precisa dele para o
 * connectNodes), o CopilotKit executa o lote e faz OUTRO POST com o histórico — as chamadas do
 * assistente e os resultados — que {@link #buildMessages} remonta como pares chamada/resultado.
 */
@RestController
@RequestMapping("/ag-ui")
public class AgUiAgentController {

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are the archflow assistant. Be concise and helpful.";

    /** Resposta de uma chamada sem resultado (ferramenta que não devolve nada, ex.: navigateTo). */
    private static final String NO_TOOL_RESULT = "(no result)";

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

            ChatRequest request = ChatRequest.builder()
                    .messages(buildMessages(input))
                    .toolSpecifications(toolSpecs(input))
                    .build();

            model.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    emit(sse, lock, AgUiEvent.of("TEXT_MESSAGE_CONTENT",
                            fields("messageId", messageId, "delta", token)));
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    synchronized (lock) {
                        writeQuietly(sse, AgUiEvent.of("TEXT_MESSAGE_END", fields("messageId", messageId)));
                        // Frontend tool calls (useFrontendTool): emit AG-UI TOOL_CALL_*
                        // so CopilotKit executes the tool in the browser.
                        List<ToolExecutionRequest> calls = response.aiMessage() != null
                                ? response.aiMessage().toolExecutionRequests() : List.of();
                        if (calls != null) {
                            for (ToolExecutionRequest call : calls) {
                                writeQuietly(sse, AgUiEvent.of("TOOL_CALL_START",
                                        fields("toolCallId", call.id(), "toolCallName", call.name(),
                                                "parentMessageId", messageId)));
                                writeQuietly(sse, AgUiEvent.of("TOOL_CALL_ARGS",
                                        fields("toolCallId", call.id(), "delta", call.arguments())));
                                writeQuietly(sse, AgUiEvent.of("TOOL_CALL_END", fields("toolCallId", call.id())));
                            }
                        }
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

    /**
     * Full conversation: a system message (prompt + useAgentContext) then the history.
     *
     * <p>Tudo em {@link RunAgentInput} vem do browser. O {@code useAgentContext}
     * era concatenado <b>cru na mensagem de sistema</b> — o lado da conversa de
     * onde, por definição, vêm as instruções. Uma entrada de contexto com
     * {@code value} = "ignore as instruções anteriores" era indistinguível de
     * uma instrução da plataforma. Mesma coisa para mensagens {@code role:tool}:
     * são resultados de ferramentas do frontend, não fala do usuário.
     *
     * <p>Agora só o {@code DEFAULT_SYSTEM_PROMPT} e a regra da cerca ocupam o
     * lado confiável; contexto e resultados de tool entram cercados.
     */
    List<ChatMessage> buildMessages(RunAgentInput input) {  // visível ao pacote para teste
        UntrustedContentFence fence = UntrustedContentFence.create();
        // A regra da cerca só entra quando há de fato conteúdo cercado. Numa
        // conversa comum ela seria só custo de token — e uma regra que aparece
        // sempre é uma regra que o modelo aprende a ignorar.
        boolean hasUntrusted = hasAgentContext(input) || hasToolMessage(input);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(hasUntrusted
                ? DEFAULT_SYSTEM_PROMPT + "\n" + fence.preamble() + contextBlock(input, fence)
                : DEFAULT_SYSTEM_PROMPT));

        if (input != null && input.messages() != null) {
            Map<String, String> toolResults = toolResultsById(input.messages());
            Set<String> answered = new HashSet<>();
            for (Map<String, Object> m : input.messages()) {
                String role = String.valueOf(m.get("role"));
                Object content = m.get("content");
                String text = content == null ? "" : content.toString();
                List<ToolExecutionRequest> calls = "assistant".equals(role) ? toolCallsOf(m) : List.of();
                if (!calls.isEmpty()) {
                    // A rodada de acompanhamento do CopilotKit (followUp) só serve se o modelo enxergar o que
                    // pediu e o que voltou: era aqui que a mensagem do assistente com toolCalls (content vazio)
                    // sumia e o resultado virava uma fala de usuário solta, sem dizer de qual chamada era — o
                    // modelo recebia "node-…-1", "node-…-2" (na ordem INVERSA: o CopilotKit insere cada
                    // resultado logo após a mensagem) e não sabia qual nó era qual para o connectNodes.
                    messages.add(text.isBlank() ? AiMessage.from(calls) : AiMessage.from(text, calls));
                    for (ToolExecutionRequest call : calls) {
                        // Toda chamada precisa de resposta, ou o provedor recusa o histórico (OpenAI: 400).
                        String result = toolResults.getOrDefault(call.id(), "");
                        answered.add(call.id());
                        messages.add(ToolExecutionResultMessage.from(call.id(), call.name(),
                                fence.wrap(call.name(), result.isBlank() ? NO_TOOL_RESULT : result)));
                    }
                    continue;
                }
                if ("tool".equals(role) && answered.contains(String.valueOf(m.get("toolCallId")))) {
                    continue;
                }
                if (text.isBlank()) {
                    continue;
                }
                if ("assistant".equals(role)) {
                    messages.add(AiMessage.from(text));
                } else if ("tool".equals(role)) {
                    // Resultado sem a chamada correspondente no histórico: sem o par, o provedor recusaria
                    // um ToolExecutionResultMessage, então segue como dado cercado na fala do usuário.
                    // Resultado de tool do frontend: dado, não instrução.
                    messages.add(UserMessage.from(fence.wrap("frontend-tool", text)));
                } else if (!"system".equals(role)) {
                    // O usuário é o principal da conversa — a fala dele não é cercada.
                    messages.add(UserMessage.from(text));
                }
            }
        }
        if (messages.size() == 1) {
            messages.add(UserMessage.from("Hello"));
        }
        return messages;
    }

    /** Resultado das mensagens {@code role:tool} do histórico, pelo {@code toolCallId} da chamada. */
    private static Map<String, String> toolResultsById(List<Map<String, Object>> history) {
        Map<String, String> byId = new HashMap<>();
        for (Map<String, Object> m : history) {
            Object id = m.get("toolCallId");
            if ("tool".equals(String.valueOf(m.get("role"))) && id != null) {
                Object content = m.get("content");
                byId.put(id.toString(), content == null ? "" : content.toString());
            }
        }
        return byId;
    }

    /** Os {@code toolCalls} AG-UI ({id, function:{name, arguments}}) de uma mensagem do assistente. */
    private static List<ToolExecutionRequest> toolCallsOf(Map<String, Object> message) {
        if (!(message.get("toolCalls") instanceof List<?> raw)) {
            return List.of();
        }
        List<ToolExecutionRequest> calls = new ArrayList<>();
        for (Object item : raw) {
            Map<String, Object> call = asMap(item);
            Map<String, Object> function = asMap(call.get("function"));
            Object id = call.get("id");
            Object name = function.get("name");
            if (id == null || name == null) {
                continue;
            }
            Object args = function.get("arguments");
            calls.add(ToolExecutionRequest.builder()
                    .id(id.toString())
                    .name(name.toString())
                    .arguments(args == null ? "{}" : args.toString())
                    .build());
        }
        return calls;
    }

    /**
     * Renders useAgentContext entries ({description, value}) into the system prompt,
     * fenced — os valores descrevem a tela do usuário e são controlados pelo cliente.
     */
    private static boolean hasAgentContext(RunAgentInput input) {
        return input != null && input.context() != null && !input.context().isEmpty();
    }

    private static boolean hasToolMessage(RunAgentInput input) {
        if (input == null || input.messages() == null) {
            return false;
        }
        return input.messages().stream()
                .anyMatch(m -> "tool".equals(String.valueOf(m.get("role"))));
    }

    private String contextBlock(RunAgentInput input, UntrustedContentFence fence) {
        if (!hasAgentContext(input)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> entry : input.context()) {
            Object desc = entry.get("description");
            Object value = entry.get("value");
            sb.append("\n- ").append(desc).append(": ").append(value);
        }
        return "\n\nContext about the app and the user's screen:\n"
                + fence.wrap("useAgentContext", sb.toString().stripLeading());
    }

    /** Converts AG-UI frontend tools (useFrontendTool) to langchain4j ToolSpecifications. */
    private List<ToolSpecification> toolSpecs(RunAgentInput input) {
        List<ToolSpecification> specs = new ArrayList<>();
        if (input == null || input.tools() == null) {
            return specs;
        }
        for (Map<String, Object> tool : input.tools()) {
            Object name = tool.get("name");
            if (name == null) {
                continue;
            }
            ToolSpecification.Builder builder = ToolSpecification.builder().name(name.toString());
            Object desc = tool.get("description");
            if (desc != null) {
                builder.description(desc.toString());
            }
            JsonObjectSchema schema = toSchema(asMap(tool.get("parameters")));
            if (schema != null) {
                builder.parameters(schema);
            }
            specs.add(builder.build());
        }
        return specs;
    }

    /** JSON-schema object -> langchain4j JsonObjectSchema. Properties are treated as strings (covers the common case). */
    private JsonObjectSchema toSchema(Map<String, Object> params) {
        Map<String, Object> properties = asMap(params.get("properties"));
        if (properties.isEmpty()) {
            return null;
        }
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Object pdesc = asMap(entry.getValue()).get("description");
            builder.addProperty(entry.getKey(), JsonStringSchema.builder()
                    .description(pdesc != null ? pdesc.toString() : null).build());
        }
        if (params.get("required") instanceof List<?> reqList) {
            List<String> required = new ArrayList<>();
            for (Object o : reqList) {
                if (o != null) {
                    required.add(o.toString());
                }
            }
            if (!required.isEmpty()) {
                builder.required(required);
            }
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
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
