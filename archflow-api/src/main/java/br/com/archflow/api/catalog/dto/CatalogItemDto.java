package br.com.archflow.api.catalog.dto;

import java.util.List;
import java.util.Map;

/**
 * Representação uniforme de um item de catálogo para consumo pelo
 * frontend. Cobre tanto componentes do {@code ComponentCatalog}
 * (agents/assistants/tools) quanto adapters LangChain4j (embedding,
 * memory, vectorstore, chain, realtime, chat provider).
 *
 * @param id           identificador estável (ex.: {@code "conversational-agent"},
 *                     {@code "openai"}, {@code "pgvector"})
 * @param displayName  nome legível
 * @param description  breve descrição
 * @param kind         categoria: "agent", "assistant", "tool",
 *                     "provider", "embedding", "memory", "vectorstore",
 *                     "chain", "realtime"
 * @param capabilities tags curtas (ex.: "streaming", "multi-tenant")
 * @param operations   operações expostas (para componentes do catálogo)
 * @param configSchema chaves de configuração aceitas — formato livre
 *                     com {@code name}, {@code type}, {@code required},
 *                     {@code description}
 * @param tags         tags adicionais (ex.: provider group)
 */
public record CatalogItemDto(
        String id,
        String displayName,
        String description,
        String kind,
        List<String> capabilities,
        List<OperationDto> operations,
        List<ConfigKeyDto> configSchema,
        List<String> tags) {

    public record OperationDto(
            String id,
            String name,
            String description,
            List<ParameterDto> inputs,
            List<ParameterDto> outputs) {}

    public record ParameterDto(
            String name,
            String type,
            String description,
            boolean required) {}

    public record ConfigKeyDto(
            String name,
            String type,
            boolean required,
            String description,
            Object defaultValue) {}
}
