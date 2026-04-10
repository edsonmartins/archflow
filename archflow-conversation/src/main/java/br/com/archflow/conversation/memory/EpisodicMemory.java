package br.com.archflow.conversation.memory;

import java.util.List;
import java.util.Optional;

/**
 * Interface for episodic memory — storing and retrieving past interaction episodes.
 *
 * <p>Episodic memory stores records of past interactions with contextual metadata,
 * enabling agents to recall relevant past experiences when handling new requests.
 *
 * <p>Retrieval uses a composite scoring function:
 * <pre>score = α·similarity + β·recency + γ·importance</pre>
 *
 * <p>Inspired by CrewAI's episodic memory with ChromaDB + RAG scoring.
 */
public interface EpisodicMemory {

    /**
     * Stores an episode in memory.
     *
     * @param episode The episode to store
     */
    void store(Episode episode);

    /**
     * Retrieves the most relevant episodes for a given query.
     *
     * @param query The search query (used for similarity matching)
     * @param contextId The context identifier (e.g., userId, sessionId)
     * @param maxResults Maximum number of episodes to return
     * @return List of episodes ranked by composite score (descending)
     */
    List<ScoredEpisode> recall(String query, String contextId, int maxResults);

    /**
     * Retrieves all episodes for a context.
     *
     * @param contextId The context identifier
     * @return List of episodes ordered by timestamp (descending)
     */
    List<Episode> getByContext(String contextId);

    /**
     * Gets a specific episode by ID.
     */
    Optional<Episode> getById(String episodeId);

    /**
     * Removes old episodes beyond the retention window.
     *
     * @param contextId The context identifier
     * @param maxEpisodes Maximum episodes to retain per context
     * @return Number of episodes removed
     */
    int evict(String contextId, int maxEpisodes);

    /**
     * Clears all episodes for a context.
     */
    void clear(String contextId);

    /**
     * Returns the total number of stored episodes.
     */
    int size();

    /**
     * Stores an episode with explicit tenant isolation.
     */
    default void store(String tenantId, Episode episode) {
        store(episode);
    }

    /**
     * Retrieves the most relevant episodes scoped by tenant.
     */
    default List<ScoredEpisode> recall(String tenantId, String query, String contextId, int maxResults) {
        return recall(query, contextId, maxResults);
    }

    /**
     * Retrieves all episodes for a tenant and context.
     */
    default List<Episode> getByContext(String tenantId, String contextId) {
        return getByContext(contextId);
    }

    /**
     * Clears all episodes for a tenant and context.
     */
    default void clear(String tenantId, String contextId) {
        clear(contextId);
    }
}
