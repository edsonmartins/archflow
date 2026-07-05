package br.com.archflow.conversation.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * Documento de configuração de governança por tenant — o supertipo comum extraído
 * dos dois {@code AgentGovernanceService} (gestor-rq e integrall-commerce-api),
 * que haviam divergido (ver {@code docs/design/0002}).
 *
 * <p>Versionado ({@code settingsVersion}), JSON-friendly e tolerante a campos
 * desconhecidos ({@code @JsonIgnoreProperties}). Seções específicas de produto
 * (TenantIdentity, WhatsApp, etc.) vão em {@link #extensions()}, não poluem o núcleo.
 *
 * @since 1.0.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GovernanceSettings(
        boolean agentEnabled,
        String customSystemPrompt,
        LLMSettings llm,
        GuardrailSettings guardrails,
        RateLimitSettings rateLimit,
        long settingsVersion,
        Instant lastModified,
        String lastModifiedBy,
        Map<String, Object> extensions
) {
    public GovernanceSettings {
        if (llm == null) {
            llm = LLMSettings.defaults();
        }
        if (guardrails == null) {
            guardrails = GuardrailSettings.defaults();
        }
        if (rateLimit == null) {
            rateLimit = RateLimitSettings.defaults();
        }
        if (settingsVersion <= 0) {
            settingsVersion = 1L;
        }
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    /** Defaults da plataforma (sobreponíveis pelo produto). */
    public static GovernanceSettings defaults() {
        return new GovernanceSettings(
                true, null,
                LLMSettings.defaults(), GuardrailSettings.defaults(), RateLimitSettings.defaults(),
                1L, null, null, Map.of());
    }
}
