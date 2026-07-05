package br.com.archflow.conversation.agent;

import br.com.archflow.conversation.guardrail.GuardrailChain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agente conversacional com loop de tool-calling — molde do
 * {@code LLMAgentOrchestratorImpl} do integrall-commerce-api:
 *
 * <pre>
 *   guardrail-in → LLM → [tool → LLM]* → guardrail-out
 * </pre>
 *
 * <p>Desenhado contra abstrações (o {@link ChatFunction} e {@link ConversationTool}),
 * sem acoplar a langchain4j — o módulo {@code archflow-conversation} depende apenas
 * de {@code archflow-model}. Um produto liga um {@code ChatModel} real fornecendo
 * uma {@link ChatFunction}. Determinístico e testável com uma função de chat scriptada.
 *
 * @since 1.0.0
 */
public class ConversationalAgent {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ConversationalAgent.class);

    /** Abstração mínima do LLM: dado o transcript atual, devolve o texto do assistente. */
    @FunctionalInterface
    public interface ChatFunction {
        String reply(String prompt);
    }

    /**
     * Resultado de uma interação.
     *
     * @param reply       resposta final ao usuário (ou mensagem de bloqueio)
     * @param blocked     se um guardrail bloqueou (entrada ou saída)
     * @param blockReason razão do bloqueio (ou {@code null})
     * @param iterations  número de turnos de LLM consumidos
     * @param toolsUsed   ferramentas executadas, na ordem
     */
    public record Result(String reply, boolean blocked, String blockReason,
                         int iterations, List<String> toolsUsed) {}

    private final String systemPrompt;
    private final int maxIterations;
    private final ToolRegistry tools;
    private final GuardrailChain guardrails;
    private final ChatFunction chat;

    private ConversationalAgent(Builder b) {
        if (b.chat == null) {
            throw new IllegalArgumentException("chat function is required");
        }
        this.systemPrompt = b.systemPrompt;
        this.maxIterations = Math.max(1, b.maxIterations);
        this.tools = b.tools;
        this.guardrails = b.guardrails;
        this.chat = b.chat;
    }

    public Result chat(String userMessage) {
        return chat(userMessage, Map.of());
    }

    /**
     * Executa o loop para uma mensagem do usuário.
     *
     * @param userMessage      mensagem do usuário
     * @param guardrailContext contexto passado aos guardrails (tenantId, sessionId, …)
     */
    public Result chat(String userMessage, Map<String, Object> guardrailContext) {
        Map<String, Object> gctx = guardrailContext != null ? guardrailContext : Map.of();

        // 1. guardrail de entrada
        String userText = userMessage;
        if (guardrails != null) {
            GuardrailChain.ChainResult in = guardrails.evaluateInput(userMessage, gctx);
            if (in.blocked()) {
                return new Result(in.blockMessage(), true, in.blockReason(), 0, List.of());
            }
            userText = in.finalText();
        }

        StringBuilder transcript = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            transcript.append(systemPrompt).append("\n\n");
        }
        transcript.append("User: ").append(userText);

        List<String> toolsUsed = new ArrayList<>();
        String assistant = "";

        // 2. loop LLM ↔ tools
        for (int i = 1; i <= maxIterations; i++) {
            assistant = chat.reply(transcript.toString());

            var call = ToolCallParser.parse(assistant);
            if (call.isPresent() && tools != null) {
                ToolCallParser.ToolCall tc = call.get();
                String toolResult = executeTool(tc);
                toolsUsed.add(tc.tool());
                transcript.append("\nAssistant: ").append(assistant)
                        .append("\nToolResult(").append(tc.tool()).append("): ").append(toolResult);
                continue;
            }
            // 3. resposta final → guardrail de saída
            return finalize(assistant, gctx, i, toolsUsed);
        }
        // maxIterations esgotado: trata o último texto como resposta final
        return finalize(assistant, gctx, maxIterations, toolsUsed);
    }

    private String executeTool(ToolCallParser.ToolCall tc) {
        var tool = tools.get(tc.tool());
        if (tool.isEmpty()) {
            return "ERROR: unknown tool '" + tc.tool() + "'";
        }
        try {
            return tool.get().execute(tc.params());
        } catch (Exception e) {
            // O texto "ERROR:" volta ao loop do LLM (que pode reagir), mas a
            // exceção precisa ficar no log do servidor com stack trace.
            log.error("Tool '{}' failed during conversational loop", tc.tool(), e);
            return "ERROR: " + e.getMessage();
        }
    }

    private Result finalize(String assistant, Map<String, Object> gctx, int iterations, List<String> toolsUsed) {
        if (guardrails != null) {
            GuardrailChain.ChainResult out = guardrails.evaluateOutput(assistant, gctx);
            if (out.blocked()) {
                return new Result(out.blockMessage(), true, out.blockReason(), iterations, toolsUsed);
            }
            return new Result(out.finalText(), false, null, iterations, toolsUsed);
        }
        return new Result(assistant, false, null, iterations, toolsUsed);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String systemPrompt;
        private int maxIterations = 5;
        private ToolRegistry tools;
        private GuardrailChain guardrails;
        private ChatFunction chat;

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder tools(ToolRegistry tools) {
            this.tools = tools;
            return this;
        }

        public Builder guardrails(GuardrailChain guardrails) {
            this.guardrails = guardrails;
            return this;
        }

        public Builder chat(ChatFunction chat) {
            this.chat = chat;
            return this;
        }

        public ConversationalAgent build() {
            return new ConversationalAgent(this);
        }
    }
}
