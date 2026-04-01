package br.com.archflow.conversation.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * In-memory implementation of episodic memory.
 *
 * <p>Uses configurable scoring weights for retrieval:
 * <pre>score = α·similarity + β·recency + γ·importance</pre>
 *
 * <p>Similarity is computed via an optional function (defaults to keyword overlap).
 * Recency decays exponentially based on age.
 */
public class InMemoryEpisodicMemory implements EpisodicMemory {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEpisodicMemory.class);

    private final Map<String, List<Episode>> store;
    private final double alpha; // similarity weight
    private final double beta;  // recency weight
    private final double gamma; // importance weight
    private final Duration recencyHalfLife;
    private final BiFunction<String, String, Double> similarityFunction;

    public InMemoryEpisodicMemory() {
        this(0.4, 0.3, 0.3, Duration.ofHours(24), null);
    }

    public InMemoryEpisodicMemory(double alpha, double beta, double gamma,
                                   Duration recencyHalfLife,
                                   BiFunction<String, String, Double> similarityFunction) {
        this.store = new ConcurrentHashMap<>();
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
        this.recencyHalfLife = recencyHalfLife;
        this.similarityFunction = similarityFunction != null ? similarityFunction : this::keywordSimilarity;
    }

    @Override
    public void store(Episode episode) {
        store.computeIfAbsent(episode.contextId(), k -> new CopyOnWriteArrayList<>())
                .add(episode);
        log.debug("Stored episode {} for context {}", episode.id(), episode.contextId());
    }

    @Override
    public List<ScoredEpisode> recall(String query, String contextId, int maxResults) {
        List<Episode> episodes = store.getOrDefault(contextId, List.of());
        if (episodes.isEmpty()) {
            return List.of();
        }

        Instant now = Instant.now();

        return episodes.stream()
                .map(ep -> score(query, ep, now))
                .sorted()
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    @Override
    public List<Episode> getByContext(String contextId) {
        return store.getOrDefault(contextId, List.of()).stream()
                .sorted(Comparator.comparing(Episode::timestamp).reversed())
                .toList();
    }

    @Override
    public Optional<Episode> getById(String episodeId) {
        return store.values().stream()
                .flatMap(List::stream)
                .filter(ep -> ep.id().equals(episodeId))
                .findFirst();
    }

    @Override
    public int evict(String contextId, int maxEpisodes) {
        List<Episode> episodes = store.get(contextId);
        if (episodes == null || episodes.size() <= maxEpisodes) {
            return 0;
        }

        // Sort by timestamp descending, keep most recent
        List<Episode> sorted = new ArrayList<>(episodes);
        sorted.sort(Comparator.comparing(Episode::timestamp).reversed());

        int toRemove = sorted.size() - maxEpisodes;
        List<Episode> toKeep = sorted.subList(0, maxEpisodes);
        store.put(contextId, new CopyOnWriteArrayList<>(toKeep));

        log.debug("Evicted {} episodes from context {}", toRemove, contextId);
        return toRemove;
    }

    @Override
    public void clear(String contextId) {
        store.remove(contextId);
    }

    @Override
    public int size() {
        return store.values().stream().mapToInt(List::size).sum();
    }

    private ScoredEpisode score(String query, Episode episode, Instant now) {
        double sim = similarityFunction.apply(query, episode.content());
        double rec = computeRecency(episode.timestamp(), now);
        double imp = episode.importance();

        double composite = alpha * sim + beta * rec + gamma * imp;

        return new ScoredEpisode(episode, composite, sim, rec, imp);
    }

    private double computeRecency(Instant episodeTime, Instant now) {
        long ageMillis = Duration.between(episodeTime, now).toMillis();
        long halfLifeMillis = recencyHalfLife.toMillis();
        if (halfLifeMillis == 0) return 1.0;
        // Exponential decay: score = 0.5^(age/halfLife)
        return Math.pow(0.5, (double) ageMillis / halfLifeMillis);
    }

    /**
     * Simple keyword overlap similarity (fallback when no embedding function).
     */
    private double keywordSimilarity(String query, String content) {
        Set<String> queryTokens = tokenize(query);
        Set<String> contentTokens = tokenize(content);

        if (queryTokens.isEmpty() || contentTokens.isEmpty()) return 0;

        long overlap = queryTokens.stream().filter(contentTokens::contains).count();
        return (double) overlap / Math.max(queryTokens.size(), contentTokens.size());
    }

    private Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .filter(t -> t.length() > 2)
                .collect(Collectors.toSet());
    }
}
