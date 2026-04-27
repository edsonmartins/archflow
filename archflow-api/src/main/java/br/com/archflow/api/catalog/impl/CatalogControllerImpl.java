package br.com.archflow.api.catalog.impl;

import br.com.archflow.api.catalog.CatalogController;
import br.com.archflow.api.catalog.dto.CatalogItemDto;
import br.com.archflow.api.catalog.dto.CatalogItemDto.ConfigKeyDto;
import br.com.archflow.api.catalog.dto.CatalogItemDto.OperationDto;
import br.com.archflow.api.catalog.dto.CatalogItemDto.ParameterDto;
import br.com.archflow.langchain4j.core.spi.LangChainRegistry;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of {@link CatalogController}.
 *
 * <p>Two sources of truth:
 * <ul>
 *   <li>{@link ComponentCatalog} — supplies AGENT/ASSISTANT/TOOL entries
 *       registered by plugins (or seeded for dev).</li>
 *   <li>{@link LangChainRegistry} — supplies adapter provider IDs
 *       discovered via SPI for chat/embedding/memory/vectorstore/chain.</li>
 * </ul>
 *
 * <p>For adapter kinds we keep a small display-metadata table: the SPI
 * only exposes provider IDs and {@code supports(type)}, not config
 * schemas, so schema hints are curated per-provider here. It is a
 * pragmatic compromise — the alternative (instantiate each factory to
 * introspect) would pull in optional runtime dependencies just to list.
 */
public class CatalogControllerImpl implements CatalogController {

    private final ComponentCatalog componentCatalog;

    public CatalogControllerImpl(ComponentCatalog componentCatalog) {
        this.componentCatalog = componentCatalog;
    }

    // ── Plugin-backed ────────────────────────────────────────────────

    @Override
    public List<CatalogItemDto> listAgents() {
        return listPluginsByType(ComponentType.AGENT, "agent");
    }

    @Override
    public List<CatalogItemDto> listAssistants() {
        return listPluginsByType(ComponentType.ASSISTANT, "assistant");
    }

    @Override
    public List<CatalogItemDto> listTools() {
        return listPluginsByType(ComponentType.TOOL, "tool");
    }

    private List<CatalogItemDto> listPluginsByType(ComponentType type, String kind) {
        if (componentCatalog == null) return List.of();
        return componentCatalog.listComponents().stream()
                .filter(m -> m.type() == type)
                .map(m -> fromMetadata(m, kind))
                .toList();
    }

    private CatalogItemDto fromMetadata(ComponentMetadata m, String kind) {
        List<OperationDto> ops = m.operations() == null ? List.of() :
                m.operations().stream().map(op -> new OperationDto(
                        op.id(), op.name(), op.description(),
                        mapParams(op.inputs()), mapParams(op.outputs())
                )).toList();
        List<String> capabilities = m.capabilities() == null
                ? List.of() : new ArrayList<>(m.capabilities());
        List<String> tags = m.tags() == null ? List.of() : new ArrayList<>(m.tags());
        return new CatalogItemDto(
                m.id(),
                m.name() != null ? m.name() : m.id(),
                m.description(),
                kind,
                capabilities,
                ops,
                List.of(),
                tags);
    }

    private List<ParameterDto> mapParams(List<ComponentMetadata.ParameterMetadata> params) {
        if (params == null) return List.of();
        return params.stream()
                .map(p -> new ParameterDto(p.name(), p.type(), p.description(), p.required()))
                .toList();
    }

    // ── Adapter-backed ───────────────────────────────────────────────

    @Override
    public List<CatalogItemDto> listChatProviders() {
        return listAdapters("chat", "provider", CHAT_PROVIDERS);
    }

    @Override
    public List<CatalogItemDto> listEmbeddings() {
        return listAdapters("embedding", "embedding", EMBEDDING_PROVIDERS);
    }

    @Override
    public List<CatalogItemDto> listMemories() {
        return listAdapters("memory", "memory", MEMORY_PROVIDERS);
    }

    @Override
    public List<CatalogItemDto> listVectorStores() {
        return listAdapters("vectorstore", "vectorstore", VECTORSTORE_PROVIDERS);
    }

    @Override
    public List<CatalogItemDto> listChains() {
        return listAdapters("chain", "chain", CHAIN_PROVIDERS);
    }

    private List<CatalogItemDto> listAdapters(String type, String kind,
            Map<String, ProviderMeta> metaTable) {
        Set<String> providers = LangChainRegistry.getProvidersOfType(type);
        List<CatalogItemDto> out = new ArrayList<>(providers.size());
        for (String providerId : providers) {
            ProviderMeta meta = metaTable.getOrDefault(providerId,
                    ProviderMeta.fallback(providerId));
            out.add(new CatalogItemDto(
                    providerId,
                    meta.displayName,
                    meta.description,
                    kind,
                    meta.capabilities,
                    List.of(),
                    meta.configKeys,
                    meta.tags));
        }
        // Ensure deterministic order for UI
        out.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return out;
    }

    @Override
    public List<CatalogItemDto> listAll() {
        List<CatalogItemDto> all = new ArrayList<>();
        all.addAll(listAgents());
        all.addAll(listAssistants());
        all.addAll(listTools());
        all.addAll(listChatProviders());
        all.addAll(listEmbeddings());
        all.addAll(listMemories());
        all.addAll(listVectorStores());
        all.addAll(listChains());
        return all;
    }

    // ── Display metadata tables ──────────────────────────────────────

    private record ProviderMeta(
            String displayName,
            String description,
            List<String> capabilities,
            List<String> tags,
            List<ConfigKeyDto> configKeys) {
        static ProviderMeta fallback(String id) {
            return new ProviderMeta(id, "Provider registered via SPI",
                    List.of(), List.of(), List.of());
        }
    }

    private static final ConfigKeyDto API_KEY =
            new ConfigKeyDto("api.key", "string", true, "API key of the provider", null);
    private static final ConfigKeyDto MODEL_NAME =
            new ConfigKeyDto("model.name", "string", false, "Model identifier", null);
    private static final ConfigKeyDto TEMPERATURE =
            new ConfigKeyDto("temperature", "number", false, "Sampling temperature", 0.7);
    private static final ConfigKeyDto MAX_TOKENS =
            new ConfigKeyDto("maxTokens", "integer", false, "Maximum tokens per response", 2048);

    private static final Map<String, ProviderMeta> CHAT_PROVIDERS = Map.of(
            "openai", new ProviderMeta("OpenAI",
                    "GPT-4o / GPT-4 Turbo / o-series via api.openai.com",
                    List.of("streaming", "function-calling"), List.of("cloud"),
                    List.of(API_KEY, MODEL_NAME, TEMPERATURE, MAX_TOKENS)),
            "anthropic", new ProviderMeta("Anthropic",
                    "Claude 3.x / 4.x family via api.anthropic.com",
                    List.of("streaming", "tool-use", "vision"), List.of("cloud"),
                    List.of(API_KEY, MODEL_NAME, TEMPERATURE, MAX_TOKENS)),
            "openrouter", new ProviderMeta("OpenRouter",
                    "Unified gateway to 50+ providers; supports fallback to a local model",
                    List.of("streaming", "multi-provider", "fallback"), List.of("cloud"),
                    List.of(
                            API_KEY, MODEL_NAME, TEMPERATURE,
                            new ConfigKeyDto("base.url", "string", false,
                                    "Override OpenRouter base URL", "https://openrouter.ai/api/v1"),
                            new ConfigKeyDto("fallback.base.url", "string", false,
                                    "Local fallback base URL (e.g. http://localhost:11434/v1)", null),
                            new ConfigKeyDto("fallback.model.name", "string", false,
                                    "Local fallback model", null),
                            new ConfigKeyDto("fallback.api.key", "string", false,
                                    "Local fallback API key", "ollama")))
    );

    private static final Map<String, ProviderMeta> EMBEDDING_PROVIDERS = Map.of(
            "openai", new ProviderMeta("OpenAI Embeddings",
                    "text-embedding-3-small/large, ada-002",
                    List.of("1536d", "remote"), List.of("cloud"),
                    List.of(
                            new ConfigKeyDto("openai.api.key", "string", true, "OpenAI API key", null),
                            new ConfigKeyDto("openai.model", "string", false,
                                    "Embedding model", "text-embedding-3-small"),
                            new ConfigKeyDto("openai.timeout", "integer", false, "Timeout (s)", 30),
                            new ConfigKeyDto("openai.maxRetries", "integer", false, "Max retries", 3))),
            "local", new ProviderMeta("Local ONNX Embeddings",
                    "Runs a SentencePiece tokenizer + ONNX model locally (CPU or GPU)",
                    List.of("offline", "configurable-dim"), List.of("local"),
                    List.of(
                            new ConfigKeyDto("local.model.path", "string", true, "ONNX model path", null),
                            new ConfigKeyDto("local.vocab.path", "string", true,
                                    "SentencePiece vocabulary path", null),
                            new ConfigKeyDto("local.dimension", "integer", true,
                                    "Embedding dimension", null),
                            new ConfigKeyDto("local.maxLength", "integer", false, "Max token length", 128),
                            new ConfigKeyDto("local.batchSize", "integer", false, "Batch size", 32),
                            new ConfigKeyDto("local.useGpu", "boolean", false, "Use CUDA", false),
                            new ConfigKeyDto("local.gpuDeviceId", "integer", false, "CUDA device id", 0),
                            new ConfigKeyDto("local.useCache", "boolean", false, "LRU cache tokens", true),
                            new ConfigKeyDto("local.tokenCacheMax", "integer", false,
                                    "Max cache entries", 10_000)))
    );

    private static final Map<String, ProviderMeta> MEMORY_PROVIDERS = Map.of(
            "jdbc", new ProviderMeta("JDBC Chat Memory",
                    "Stores chat history in a relational DB (tenant+session keyed)",
                    List.of("multi-tenant", "persistent"), List.of("relational"),
                    List.of(
                            new ConfigKeyDto("datasource", "object", true,
                                    "javax.sql.DataSource to use", null),
                            new ConfigKeyDto("memory.maxMessages", "integer", false,
                                    "Max messages per session", 100))),
            "redis", new ProviderMeta("Redis Chat Memory",
                    "Stores chat history in Redis lists/hashes (tenant+session keyed)",
                    List.of("multi-tenant", "fast"), List.of("key-value"),
                    List.of(
                            new ConfigKeyDto("redis.host", "string", true, "Redis host", "localhost"),
                            new ConfigKeyDto("redis.port", "integer", false, "Redis port", 6379),
                            new ConfigKeyDto("redis.prefix", "string", false, "Key prefix", "archflow:chat:"),
                            new ConfigKeyDto("memory.maxMessages", "integer", false,
                                    "Max messages per session", 100)))
    );

    private static final Map<String, ProviderMeta> VECTORSTORE_PROVIDERS = Map.of(
            "pgvector", new ProviderMeta("PostgreSQL pgvector",
                    "Vector search via the pgvector extension",
                    List.of("filter", "acid", "remove"), List.of("relational"),
                    List.of(
                            new ConfigKeyDto("pgvector.jdbcUrl", "string", true,
                                    "JDBC URL (postgresql)", null),
                            new ConfigKeyDto("pgvector.username", "string", true, "DB user", null),
                            new ConfigKeyDto("pgvector.password", "string", true, "DB password", null),
                            new ConfigKeyDto("pgvector.dimension", "integer", true,
                                    "Vector dimension", null),
                            new ConfigKeyDto("pgvector.table", "string", false,
                                    "Table name (identifier)", "embeddings"))),
            "redis", new ProviderMeta("Redis Vector Store",
                    "Cosine similarity over Redis hashes",
                    List.of("prefix-isolation", "remove", "remove-all"), List.of("key-value"),
                    List.of(
                            new ConfigKeyDto("redis.host", "string", true, "Redis host", null),
                            new ConfigKeyDto("redis.port", "integer", false, "Redis port", 6379),
                            new ConfigKeyDto("redis.prefix", "string", false, "Key prefix", "embedding:"),
                            new ConfigKeyDto("redis.dimension", "integer", true,
                                    "Vector dimension", null))),
            "pinecone", new ProviderMeta("Pinecone",
                    "Managed vector search; supports metadata filters and namespaces",
                    List.of("filter", "remove", "remove-all", "cloud"), List.of("cloud"),
                    List.of(
                            new ConfigKeyDto("pinecone.apiKey", "string", true, "API key", null),
                            new ConfigKeyDto("pinecone.apiUrl", "string", true,
                                    "Index URL (https://your-index.pinecone.io)", null),
                            new ConfigKeyDto("pinecone.indexName", "string", false,
                                    "Namespace", "embeddings"),
                            new ConfigKeyDto("pinecone.dimension", "integer", true,
                                    "Vector dimension", null)))
    );

    private static final Map<String, ProviderMeta> CHAIN_PROVIDERS = Map.of(
            "rag", new ProviderMeta("Retrieval Augmented Generation",
                    "Embedding + vector search + LLM generation pipeline",
                    List.of("retrieval", "augmentation"), List.of("composite"),
                    List.of(
                            new ConfigKeyDto("embedding.provider", "string", true,
                                    "Embedding provider id", "openai"),
                            new ConfigKeyDto("vectorstore.provider", "string", true,
                                    "Vector store provider id", "pgvector"),
                            new ConfigKeyDto("languagemodel.provider", "string", true,
                                    "LLM provider id", "openai"),
                            new ConfigKeyDto("retriever.maxResults", "integer", false,
                                    "Top-K", 5),
                            new ConfigKeyDto("retriever.minScore", "number", false,
                                    "Minimum similarity score", 0.7)))
    );
}
