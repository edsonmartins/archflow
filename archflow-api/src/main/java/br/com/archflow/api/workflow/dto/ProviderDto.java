package br.com.archflow.api.workflow.dto;

import java.util.List;

/**
 * Public metadata of an LLM provider exposed to the workflow editor so
 * the frontend can populate its provider/model dropdowns dynamically
 * instead of hardcoding options.
 *
 * <p>This is a projection of {@link br.com.archflow.langchain4j.provider.LLMProvider}
 * — it intentionally drops internals like base URLs and keeps only what
 * the editor needs (id, label, capability flags, model catalog).
 */
public record ProviderDto(
        String id,
        String displayName,
        boolean requiresApiKey,
        boolean supportsStreaming,
        String group,
        List<ModelDto> models
) {

    /**
     * @param id             model id (e.g. {@code gpt-4o})
     * @param name           human-readable label
     * @param contextWindow  maximum context tokens
     * @param maxTemperature upper bound the frontend uses to clamp the
     *                       temperature slider
     */
    public record ModelDto(
            String id,
            String name,
            int contextWindow,
            double maxTemperature
    ) {}
}
