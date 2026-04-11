package br.com.archflow.agent.e2e.sac;

import java.util.ArrayList;
import java.util.List;

/**
 * Mock guardrail for SAC agent input/output validation.
 *
 * <p>Mirrors the SAC agent's {@code GuardrailService.evaluateInput()} and
 * {@code evaluateOutput()} methods. Implements three checks:
 * <ul>
 *   <li>PII detection (CPF, CNPJ, credit card numbers)</li>
 *   <li>Profanity filter (small word list)</li>
 *   <li>Identification check (CNPJ required for business operations)</li>
 * </ul>
 */
public class MockGuardrail {

    private static final List<String> PROFANITY = List.of("idiota", "burro", "lixo");
    private static final List<String> BUSINESS_KEYWORDS = List.of(
        "rastrear", "consultar", "pedido", "nota fiscal", "boleto", "entrega"
    );

    private final List<String> blockedInputs = new ArrayList<>();
    private final List<String> blockedOutputs = new ArrayList<>();

    public record GuardrailResult(boolean allowed, String reason, String replacement) {
        public static GuardrailResult ok() {
            return new GuardrailResult(true, null, null);
        }
        public static GuardrailResult blocked(String reason, String replacement) {
            return new GuardrailResult(false, reason, replacement);
        }
    }

    /**
     * Evaluate user input before sending to LLM.
     */
    public GuardrailResult evaluateInput(String message, boolean hasCustomerCnpj) {
        String lower = message.toLowerCase();

        // Profanity check
        for (String bad : PROFANITY) {
            if (lower.contains(bad)) {
                blockedInputs.add(message);
                return GuardrailResult.blocked(
                    "profanity",
                    "Por favor, mantenha um diálogo respeitoso."
                );
            }
        }

        // Identification guardrail — business operations require CNPJ
        boolean isBusinessRequest = BUSINESS_KEYWORDS.stream().anyMatch(lower::contains);
        boolean isGreeting = lower.matches("^(oi|olá|ola|bom dia|boa tarde|boa noite|ok|obrigado|tchau)$");

        if (isBusinessRequest && !hasCustomerCnpj && !isGreeting) {
            blockedInputs.add(message);
            return GuardrailResult.blocked(
                "missing_identification",
                "Para te ajudar, por favor informe seu CNPJ."
            );
        }

        return GuardrailResult.ok();
    }

    /**
     * Evaluate LLM output before sending to user.
     */
    public GuardrailResult evaluateOutput(String response) {
        // PII redaction
        if (response.matches(".*\\b\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}\\b.*")) {
            blockedOutputs.add(response);
            return GuardrailResult.blocked(
                "pii_detected",
                response.replaceAll("\\b\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}\\b", "***.***.***-**")
            );
        }

        // Profanity in output
        String lower = response.toLowerCase();
        for (String bad : PROFANITY) {
            if (lower.contains(bad)) {
                blockedOutputs.add(response);
                return GuardrailResult.blocked(
                    "profanity_in_output",
                    "Desculpe, não posso processar essa solicitação."
                );
            }
        }

        return GuardrailResult.ok();
    }

    public List<String> getBlockedInputs() {
        return List.copyOf(blockedInputs);
    }

    public List<String> getBlockedOutputs() {
        return List.copyOf(blockedOutputs);
    }

    public void reset() {
        blockedInputs.clear();
        blockedOutputs.clear();
    }
}
