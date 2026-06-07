package br.com.archflow.conversation.governance;

import br.com.archflow.conversation.guardrail.AgentGuardrail;
import br.com.archflow.conversation.guardrail.GuardrailChain;
import br.com.archflow.conversation.guardrail.builtin.ForbiddenOutputGuardrail;
import br.com.archflow.conversation.guardrail.builtin.PiiRedactionGuardrail;
import br.com.archflow.conversation.guardrail.builtin.PromptInjectionGuardrail;

import java.util.ArrayList;
import java.util.List;

/**
 * Ponte governança → guardrails: monta um {@link GuardrailChain} a partir das
 * {@link GuardrailSettings} de um tenant. Liga o D3 (governança por tenant) ao D1
 * (o {@code ConversationalAgent} recebe o chain resultante).
 *
 * @since 1.0.0
 */
public final class GovernanceGuardrails {

    private GovernanceGuardrails() {
    }

    /** Constrói o chain efetivo a partir das settings (vazio → sem guardrails). */
    public static GuardrailChain from(GuardrailSettings settings) {
        GuardrailSettings s = settings != null ? settings : GuardrailSettings.defaults();
        List<AgentGuardrail> chain = new ArrayList<>();
        if (s.lgpdEnabled()) {
            chain.add(new PiiRedactionGuardrail());
        }
        if (!s.promptInjectionMarkers().isEmpty()) {
            chain.add(new PromptInjectionGuardrail(s.promptInjectionMarkers()));
        }
        if (!s.forbiddenOutputs().isEmpty()) {
            chain.add(new ForbiddenOutputGuardrail(s.forbiddenOutputs()));
        }
        return new GuardrailChain(chain);
    }
}
