package br.com.archflow.conversation.guardrail;

import java.util.Map;

/**
 * Guardrail that evaluates user input before LLM call and/or LLM output before
 * sending to the user.
 *
 * <p>Mirrors the SAC agent's {@code GuardrailService.evaluateInput()} and
 * {@code evaluateOutput()} methods. Implementations chain via {@link GuardrailChain}
 * — the first blocking result short-circuits the chain.
 *
 * <p>Examples of policies that can be implemented:
 * <ul>
 *   <li>PII detection (CPF/CNPJ/credit cards)</li>
 *   <li>Profanity filter</li>
 *   <li>Identification check (CNPJ required for business operations)</li>
 *   <li>Off-topic detection (out of scope queries)</li>
 *   <li>Toxicity classification</li>
 * </ul>
 */
public interface AgentGuardrail {

    /**
     * Stable name for logging and metrics.
     */
    String getName();

    /**
     * Evaluate user input before sending to LLM.
     *
     * @param message User message
     * @param context Conversation context (tenantId, sessionId, customerCnpj, etc.)
     * @return Result indicating allow or block + replacement
     */
    default GuardrailResult evaluateInput(String message, Map<String, Object> context) {
        return GuardrailResult.ok();
    }

    /**
     * Evaluate LLM output before sending to user.
     *
     * @param response LLM response
     * @param context  Conversation context
     */
    default GuardrailResult evaluateOutput(String response, Map<String, Object> context) {
        return GuardrailResult.ok();
    }
}
