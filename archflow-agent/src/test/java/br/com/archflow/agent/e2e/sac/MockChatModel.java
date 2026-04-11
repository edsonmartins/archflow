package br.com.archflow.agent.e2e.sac;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock LLM model for SAC agent E2E tests.
 *
 * <p>Returns predefined responses based on simple pattern matching on the input.
 * Records all calls for assertion. Supports tool call simulation: if the input
 * contains certain keywords, the response includes a fake tool_call payload.
 */
public class MockChatModel {

    private final AtomicInteger callCount = new AtomicInteger(0);
    private final List<String> capturedPrompts = new ArrayList<>();

    /** Simulated LLM response. */
    public record LlmResponse(String text, List<ToolCall> toolCalls, double confidence) {
        public static LlmResponse text(String text) {
            return new LlmResponse(text, List.of(), 0.95);
        }
        public static LlmResponse withTool(String text, ToolCall tool) {
            return new LlmResponse(text, List.of(tool), 0.95);
        }
        public static LlmResponse lowConfidence(String text) {
            return new LlmResponse(text, List.of(), 0.35);
        }
    }

    public record ToolCall(String name, Map<String, Object> arguments) {}

    /**
     * Generate a response based on the input message and conversation history.
     */
    public LlmResponse chat(String userMessage, List<String> history) {
        callCount.incrementAndGet();
        capturedPrompts.add(userMessage);

        String lower = userMessage.toLowerCase().trim();

        // Greeting bypass — should NOT be called by agent (handled before LLM)
        if (lower.matches("^(oi|olá|ola|bom dia|boa tarde|boa noite)$")) {
            return LlmResponse.text("Olá! Como posso ajudar?");
        }

        // Tool call patterns
        if (lower.contains("rastrear") && lower.matches(".*\\d+.*")) {
            String orderNum = userMessage.replaceAll(".*?(\\d+).*", "$1");
            return LlmResponse.withTool(
                "Vou rastrear o pedido para você.",
                new ToolCall("tracking_pedido", Map.of("numero_pedido", orderNum))
            );
        }

        if (lower.contains("reclamação") || lower.contains("reclamar")) {
            return LlmResponse.withTool(
                "Vou abrir um ticket de reclamação.",
                new ToolCall("criar_ticket_reclamacao", Map.of("motivo", userMessage))
            );
        }

        if (lower.contains("consultar") && lower.contains("pedido")) {
            return LlmResponse.withTool(
                "Consultando seus pedidos...",
                new ToolCall("consultar_pedidos_cliente", Map.of("cnpj", "12345678000190"))
            );
        }

        // Low confidence — triggers escalation
        if (lower.contains("complicado") || lower.contains("não entendi")) {
            return LlmResponse.lowConfidence("Não tenho certeza de como ajudar.");
        }

        // Generic response
        return LlmResponse.text("Entendi sua mensagem: " + userMessage);
    }

    public int getCallCount() {
        return callCount.get();
    }

    public List<String> getCapturedPrompts() {
        return List.copyOf(capturedPrompts);
    }

    public void reset() {
        callCount.set(0);
        capturedPrompts.clear();
    }
}
