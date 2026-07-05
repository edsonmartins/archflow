package br.com.archflow.conversation.guardrail.builtin;

import br.com.archflow.conversation.guardrail.AgentGuardrail;
import br.com.archflow.conversation.guardrail.GuardrailResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bloqueia a entrada quando contém um marcador de prompt-injection configurável
 * (ex.: "ignore previous", "system:"). Dirigido por lista — alimentado pela
 * governança ({@code GuardrailSettings.promptInjectionMarkers}).
 *
 * @since 1.0.0
 */
public class PromptInjectionGuardrail implements AgentGuardrail {

    private final List<String> markers;
    private final String blockMessage;

    public PromptInjectionGuardrail(List<String> markers) {
        this(markers, "Não posso atender a essa solicitação.");
    }

    public PromptInjectionGuardrail(List<String> markers, String blockMessage) {
        this.markers = markers == null ? List.of()
                : markers.stream().map(m -> m.toLowerCase(Locale.ROOT)).toList();
        this.blockMessage = blockMessage;
    }

    @Override
    public String getName() {
        return "prompt-injection";
    }

    @Override
    public GuardrailResult evaluateInput(String message, Map<String, Object> context) {
        if (message == null || message.isBlank()) {
            return GuardrailResult.ok();
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String marker : markers) {
            if (!marker.isBlank() && lower.contains(marker)) {
                return GuardrailResult.blocked("prompt-injection:" + marker, blockMessage);
            }
        }
        return GuardrailResult.ok();
    }
}
