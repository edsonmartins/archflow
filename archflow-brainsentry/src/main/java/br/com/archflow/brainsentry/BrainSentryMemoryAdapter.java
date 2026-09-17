package br.com.archflow.brainsentry;

import br.com.archflow.conversation.memory.Episode;
import br.com.archflow.conversation.memory.EpisodicMemory;
import br.com.archflow.conversation.memory.ScoredEpisode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Adapts Brain Sentry as a backend for archflow's EpisodicMemory interface.
 *
 * <p>Maps archflow Episodes to Brain Sentry Memories:
 * <ul>
 *   <li>{@code store()} → POST /v1/memories</li>
 *   <li>{@code recall()} → POST /v1/memories/search (hybrid search)</li>
 *   <li>{@code getByContext()} → POST /v1/memories/search, restricted to the context</li>
 *   <li>{@code getById()} → GET /v1/memories/{id}</li>
 * </ul>
 *
 * <h2>Isolamento — em duas camadas</h2>
 *
 * <p><b>1. No servidor, pela chave.</b> No Brain Sentry uma chave de serviço ({@code bs_…}) é
 * presa a um tenant: tudo que ela grava e lê fica naquele tenant, e um header pedindo outro é
 * recusado. O construtor com {@code clientePorTenant} usa uma chave por tenant do archflow — é o
 * modo em que o isolamento não depende deste código. Tenant sem chave não tem memória: nada é
 * gravado nem devolvido.</p>
 *
 * <p><b>2. Pelas tags, também no servidor.</b> Toda memória gravada leva {@code tenant:<id>} e
 * {@code context:<id>}, e toda busca manda essas tags como filtro. No Brain Sentry, tag é recorte
 * do conjunto (WHERE, antes do LIMIT), não peso. É o que isola no modo de chave única
 * (construtor com um {@link BrainSentryClient}), em que todos os tenants do archflow dividem o
 * mesmo tenant de lá — modo aceitável para instalação de um tenant só.</p>
 *
 * <p>Por cima das duas, o resultado ainda é conferido aqui: memória sem a tag do tenant pedido é
 * descartada. "Não dá para conferir" significa "não devolve".</p>
 *
 * <h2>O que mudou em 17/09/2026</h2>
 *
 * <p>A versão anterior (1) devolvia memória <b>sem tags</b> a qualquer tenant; (2) isolava só por
 * um prefixo de texto na consulta mais um filtro depois da busca, o que além de frágil devolvia
 * menos que o existente; (3) ignorava o contexto no recall e carimbava o pedido no resultado; e
 * (4) lia por id sem conferir o tenant. Ver design 0008, seção 4.</p>
 */
public class BrainSentryMemoryAdapter implements EpisodicMemory {

    private static final Logger log = LoggerFactory.getLogger(BrainSentryMemoryAdapter.class);

    /** Tenant dos métodos do contrato que não recebem um. */
    static final String TENANT_PADRAO = "SYSTEM";

    static final String TAG_ORIGEM = "archflow";
    static final String PREFIXO_TENANT = "tenant:";
    static final String PREFIXO_CONTEXTO = "context:";

    /** Teto do {@link #getByContext}: é uma listagem, não uma busca por relevância. */
    private static final int LIMITE_POR_CONTEXTO = 50;

    private final Function<String, Optional<BrainSentryClient>> clientePorTenant;
    private final AtomicLong falhasDeGravacao = new AtomicLong();
    private final AtomicLong gravacoesSemChave = new AtomicLong();

    /**
     * Modo de chave única: todos os tenants usam o mesmo client, e o isolamento entre eles fica
     * com as tags. Adequado a instalação de um tenant só.
     */
    public BrainSentryMemoryAdapter(BrainSentryClient client) {
        Objects.requireNonNull(client);
        this.clientePorTenant = tenantId -> Optional.of(client);
    }

    /**
     * Modo de chave por tenant: o isolamento é do servidor.
     *
     * @param clientePorTenant o client configurado com a chave do tenant, ou vazio se o tenant não
     *                         tem chave — e então não tem memória
     */
    public BrainSentryMemoryAdapter(Function<String, Optional<BrainSentryClient>> clientePorTenant) {
        this.clientePorTenant = Objects.requireNonNull(clientePorTenant);
    }

    /** Gravações que falharam desde a criação — a gravação é tolerante, mas não invisível. */
    public long falhasDeGravacao() {
        return falhasDeGravacao.get();
    }

    /** Gravações descartadas porque o tenant não tem chave configurada. */
    public long gravacoesSemChave() {
        return gravacoesSemChave.get();
    }

    @Override
    public void store(Episode episode) {
        store(episode.tenantId(), episode);
    }

    @Override
    public void store(String tenantId, Episode episode) {
        String tenant = tenantOuPadrao(tenantId);
        Optional<BrainSentryClient> client = clientDe(tenant);
        if (client.isEmpty()) {
            gravacoesSemChave.incrementAndGet();
            log.warn("Episódio {} descartado: o tenant {} não tem chave do Brain Sentry",
                    episode.id(), tenant);
            return;
        }
        try {
            String category = mapEpisodeType(episode.type());
            String importance = episode.importance() >= 0.7 ? "CRITICAL"
                    : episode.importance() >= 0.4 ? "IMPORTANT" : "MINOR";
            List<String> tags = List.of(TAG_ORIGEM,
                    PREFIXO_TENANT + tenant,
                    PREFIXO_CONTEXTO + episode.contextId());

            client.get().createMemory(episode.content(), category, importance, "EPISODIC", tags);
            log.debug("Stored episode {} to Brain Sentry for tenant {}", episode.id(), tenant);
        } catch (Exception e) {
            falhasDeGravacao.incrementAndGet();
            log.error("Failed to store episode to Brain Sentry (tenant={}): {}", tenant, e.getMessage());
        }
    }

    @Override
    public List<ScoredEpisode> recall(String query, String contextId, int maxResults) {
        return recall(TENANT_PADRAO, query, contextId, maxResults);
    }

    @Override
    public List<ScoredEpisode> recall(String tenantId, String query, String contextId, int maxResults) {
        String tenant = tenantOuPadrao(tenantId);
        Optional<BrainSentryClient> client = clientDe(tenant);
        if (client.isEmpty()) {
            return List.of();
        }
        try {
            // A consulta vai limpa: o escopo é filtro, não texto. O prefixo "tenant:X" que ia na
            // consulta só poluía a busca semântica.
            List<Memory> memories = client.get().searchMemories(query, maxResults,
                    recorte(tenant, contextId));
            return memories.stream()
                    .filter(m -> pertence(m, tenant, contextId))
                    .map(m -> toScoredEpisode(m, tenant))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to recall from Brain Sentry (tenant={}): {}", tenant, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Episode> getByContext(String contextId) {
        return getByContext(TENANT_PADRAO, contextId);
    }

    @Override
    public List<Episode> getByContext(String tenantId, String contextId) {
        String tenant = tenantOuPadrao(tenantId);
        Optional<BrainSentryClient> client = clientDe(tenant);
        if (client.isEmpty() || contextId == null || contextId.isBlank()) {
            return List.of();
        }
        try {
            // A busca semântica exige uma consulta; o recorte é quem garante o escopo, e o próprio
            // contexto serve de consulta.
            List<Memory> memories = client.get().searchMemories(contextId, LIMITE_POR_CONTEXTO,
                    recorte(tenant, contextId));
            return memories.stream()
                    .filter(m -> pertence(m, tenant, contextId))
                    .map(m -> toEpisode(m, tenant))
                    .sorted(Comparator.comparing(Episode::timestamp).reversed())
                    .toList();
        } catch (Exception e) {
            log.error("Failed to get by context from Brain Sentry (tenant={}): {}", tenant, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Optional<Episode> getById(String episodeId) {
        return getById(TENANT_PADRAO, episodeId);
    }

    /**
     * Só devolve a memória se ela for do tenant pedido. Um id não é credencial: quem descobrir um
     * não pode ler, com ele, a memória de outro tenant.
     */
    @Override
    public Optional<Episode> getById(String tenantId, String episodeId) {
        String tenant = tenantOuPadrao(tenantId);
        Optional<BrainSentryClient> client = clientDe(tenant);
        if (client.isEmpty()) {
            return Optional.empty();
        }
        try {
            return client.get().getMemory(episodeId)
                    .filter(m -> pertence(m, tenant, null))
                    .map(m -> toEpisode(m, tenant));
        } catch (Exception e) {
            log.error("Failed to get by ID from Brain Sentry (tenant={}): {}", tenant, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public int evict(String contextId, int maxEpisodes) {
        // Brain Sentry handles decay/eviction internally
        log.debug("Eviction delegated to Brain Sentry's internal decay mechanism");
        return 0;
    }

    @Override
    public void clear(String contextId) {
        log.warn("Clear not supported via Brain Sentry REST API — use Brain Sentry dashboard");
    }

    @Override
    public int size() {
        // Brain Sentry doesn't expose a count endpoint; return -1 to indicate unknown
        return -1;
    }

    // --- Escopo ---

    private Optional<BrainSentryClient> clientDe(String tenant) {
        try {
            Optional<BrainSentryClient> client = clientePorTenant.apply(tenant);
            return client == null ? Optional.empty() : client;
        } catch (RuntimeException e) {
            log.error("Falha ao resolver o client do Brain Sentry para o tenant {}: {}", tenant, e.getMessage());
            return Optional.empty();
        }
    }

    private static String tenantOuPadrao(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? TENANT_PADRAO : tenantId;
    }

    /** As tags que o servidor usa como recorte: o tenant sempre, o contexto quando pedido. */
    private static List<String> recorte(String tenant, String contextId) {
        if (contextId == null || contextId.isBlank()) {
            return List.of(PREFIXO_TENANT + tenant);
        }
        return List.of(PREFIXO_TENANT + tenant, PREFIXO_CONTEXTO + contextId);
    }

    /**
     * A memória carrega a tag do tenant — e a do contexto, quando ele foi pedido.
     *
     * <p>Sem tags, não pertence a ninguém. A versão anterior devolvia memória sem tags a qualquer
     * tenant, com o comentário "no tags = no filtering possible".</p>
     */
    static boolean pertence(Memory memory, String tenant, String contextId) {
        List<String> tags = memory.tags();
        if (tags == null || !tags.contains(PREFIXO_TENANT + tenant)) {
            return false;
        }
        return contextId == null || contextId.isBlank()
                || tags.contains(PREFIXO_CONTEXTO + contextId);
    }

    /** O contexto que a memória declara, não o que foi pedido. */
    private static String contextoDe(Memory memory) {
        if (memory.tags() == null) {
            return "unknown";
        }
        return memory.tags().stream()
                .filter(t -> t.startsWith(PREFIXO_CONTEXTO))
                .map(t -> t.substring(PREFIXO_CONTEXTO.length()))
                .filter(c -> !c.isBlank())
                .findFirst()
                .orElse("unknown");
    }

    // --- Mapping helpers ---

    private String mapEpisodeType(Episode.EpisodeType type) {
        return switch (type) {
            case INTERACTION -> "CONTEXT";
            case ACTION -> "ACTION";
            case OUTCOME -> "INSIGHT";
            case ERROR -> "WARNING";
            case FEEDBACK -> "KNOWLEDGE";
        };
    }

    private ScoredEpisode toScoredEpisode(Memory memory, String tenant) {
        Episode episode = toEpisode(memory, tenant);
        double score = mapImportance(memory.importance());
        return new ScoredEpisode(episode, score, score, 1.0, score);
    }

    /** O tenant só chega aqui depois de {@link #pertence}, então é o da memória. */
    private Episode toEpisode(Memory memory, String tenant) {
        double importance = mapImportance(memory.importance());
        Map<String, String> metadata = new HashMap<>();
        metadata.put("brainsentry.id", memory.id());
        metadata.put("brainsentry.category", memory.category() != null ? memory.category() : "");
        metadata.put("brainsentry.memoryType", memory.memoryType() != null ? memory.memoryType() : "");

        return new Episode(
                memory.id(),
                tenant,
                contextoDe(memory),
                memory.content(),
                memory.summary(),
                Episode.EpisodeType.INTERACTION,
                importance,
                metadata,
                memory.createdAt() != null ? memory.createdAt() : Instant.now()
        );
    }

    private double mapImportance(String importance) {
        if (importance == null) return 0.5;
        return switch (importance.toUpperCase()) {
            case "CRITICAL" -> 0.9;
            case "IMPORTANT" -> 0.6;
            case "MINOR" -> 0.3;
            default -> 0.5;
        };
    }
}
