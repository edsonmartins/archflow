package br.com.archflow.conversation.guardrail.builtin;

import br.com.archflow.conversation.guardrail.AgentGuardrail;
import br.com.archflow.conversation.guardrail.GuardrailResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bloqueia a saída quando contém um padrão proibido configurável (ex.: "select *",
 * "api_key", "bearer "). Dirigido por lista — alimentado pela governança
 * ({@code GuardrailSettings.forbiddenOutputs}).
 *
 * @since 1.0.0
 */
public class ForbiddenOutputGuardrail implements AgentGuardrail {

    private final List<String> patterns;
    private final String blockMessage;

    public ForbiddenOutputGuardrail(List<String> patterns) {
        this(patterns, "Desculpe, não consegui gerar uma resposta adequada.");
    }

    public ForbiddenOutputGuardrail(List<String> patterns, String blockMessage) {
        this.patterns = patterns == null ? List.of()
                : patterns.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
        this.blockMessage = blockMessage;
    }

    @Override
    public String getName() {
        return "forbidden-output";
    }

    @Override
    public GuardrailResult evaluateOutput(String response, Map<String, Object> context) {
        if (response == null || response.isBlank()) {
            return GuardrailResult.ok();
        }
        String lower = response.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (!pattern.isBlank() && lower.contains(pattern)) {
                return GuardrailResult.blocked("forbidden-output:" + pattern, blockMessage);
            }
        }
        return GuardrailResult.ok();
    }
}
