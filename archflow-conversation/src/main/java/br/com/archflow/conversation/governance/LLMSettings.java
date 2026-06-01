package br.com.archflow.conversation.governance;

import br.com.archflow.model.config.LLMConfigPatch;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configurações de LLM por tenant na governança. Reusa o {@link LLMConfigPatch}
 * (D2) — vira a fonte do tier {@code tenant} na cadeia de resolução de modelo.
 *
 * @param patch      override parcial de provider/model/temperature/maxTokens/…
 * @param apiKey     chave por tenant; {@code WRITE_ONLY} — aceita na desserialização
 *                   mas nunca serializa em respostas REST (padrão do gestor-rq)
 * @param maxRetries tentativas em caso de falha (default 3)
 * @since 1.0.0
 */
public record LLMSettings(
        LLMConfigPatch patch,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String apiKey,
        int maxRetries
) {
    public LLMSettings {
        if (patch == null) {
            patch = LLMConfigPatch.empty();
        }
        if (maxRetries <= 0) {
            maxRetries = 3;
        }
    }

    public static LLMSettings defaults() {
        return new LLMSettings(LLMConfigPatch.empty(), null, 3);
    }

    /** toString sem expor a apiKey. */
    @Override
    public String toString() {
        return "LLMSettings{patch=" + patch + ", maxRetries=" + maxRetries + ", apiKey=" +
                (apiKey != null && !apiKey.isBlank() ? "***" : "null") + "}";
    }
}
