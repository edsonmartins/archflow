package br.com.archflow.conversation.guardrail.builtin;

import br.com.archflow.conversation.guardrail.AgentGuardrail;
import br.com.archflow.conversation.guardrail.GuardrailResult;

import java.util.List;
import java.util.Map;

/**
 * Built-in profanity guardrail. Blocks input/output containing words from
 * a configurable blacklist.
 *
 * <p>Default blacklist is intentionally minimal — products should provide their
 * own list with appropriate localization.
 */
public class ProfanityGuardrail implements AgentGuardrail {

    private static final List<String> DEFAULT_PROFANITY = List.of(
            "idiota", "burro", "lixo", "merda", "imbecil"
    );
    private static final String DEFAULT_REPLACEMENT_INPUT  = "Por favor, mantenha um diálogo respeitoso.";
    private static final String DEFAULT_REPLACEMENT_OUTPUT = "Desculpe, não posso processar essa solicitação.";

    private final List<String> blacklist;

    public ProfanityGuardrail() {
        this(DEFAULT_PROFANITY);
    }

    public ProfanityGuardrail(List<String> blacklist) {
        this.blacklist = blacklist.stream().map(String::toLowerCase).toList();
    }

    @Override
    public String getName() {
        return "profanity";
    }

    @Override
    public GuardrailResult evaluateInput(String message, Map<String, Object> context) {
        return check(message, DEFAULT_REPLACEMENT_INPUT);
    }

    @Override
    public GuardrailResult evaluateOutput(String response, Map<String, Object> context) {
        return check(response, DEFAULT_REPLACEMENT_OUTPUT);
    }

    private GuardrailResult check(String text, String replacement) {
        if (text == null) return GuardrailResult.ok();
        String lower = text.toLowerCase();
        for (String bad : blacklist) {
            if (lower.contains(bad)) {
                return GuardrailResult.blocked("profanity", replacement);
            }
        }
        return GuardrailResult.ok();
    }
}
