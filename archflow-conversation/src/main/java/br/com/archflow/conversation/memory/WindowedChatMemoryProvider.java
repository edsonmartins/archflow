package br.com.archflow.conversation.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-(tenant, session) {@link ChatMemory} provider with sliding window,
 * LRU eviction and optional TTL.
 *
 * <p>Each conversation gets its own bounded chat memory that keeps only the
 * most recent N messages. The provider itself caches memories by composite
 * key {@code "tenantId:sessionId"} so subsequent calls within the same
 * conversation reuse the same memory.
 *
 * <p>To prevent unbounded growth in long-running processes, the provider
 * enforces:
 * <ul>
 *   <li><b>LRU eviction:</b> when the cache reaches {@code maxCachedSessions},
 *       the least recently accessed memory is evicted.</li>
 *   <li><b>TTL:</b> if set, a memory older than {@code idleTtl} since its last
 *       access is evicted on the next read.</li>
 * </ul>
 *
 * <p>Thread-safe — all state changes go through synchronized blocks.
 */
public class WindowedChatMemoryProvider {

    /**
     * Default cap on cached sessions (prevents memory leaks in production).
     */
    public static final int DEFAULT_MAX_CACHED_SESSIONS = 10_000;

    private final int maxMessages;
    private final int maxCachedSessions;
    private final Duration idleTtl;
    private final LinkedHashMap<String, Entry> cache;

    private static final class Entry {
        final ChatMemory memory;
        volatile Instant lastAccess;

        Entry(ChatMemory memory) {
            this.memory = memory;
            this.lastAccess = Instant.now();
        }
    }

    public WindowedChatMemoryProvider(int maxMessages) {
        this(maxMessages, DEFAULT_MAX_CACHED_SESSIONS, null);
    }

    /**
     * @param maxMessages       Maximum number of messages retained per session.
     * @param maxCachedSessions Maximum number of sessions cached in memory.
     *                          Must be >= 1. Oldest-accessed sessions are evicted first.
     * @param idleTtl           How long an idle session is kept before eviction.
     *                          {@code null} disables TTL (only LRU applies).
     */
    public WindowedChatMemoryProvider(int maxMessages, int maxCachedSessions, Duration idleTtl) {
        if (maxMessages < 1) throw new IllegalArgumentException("maxMessages must be >= 1");
        if (maxCachedSessions < 1) throw new IllegalArgumentException("maxCachedSessions must be >= 1");
        if (idleTtl != null && idleTtl.isNegative()) {
            throw new IllegalArgumentException("idleTtl must not be negative");
        }

        this.maxMessages = maxMessages;
        this.maxCachedSessions = maxCachedSessions;
        this.idleTtl = idleTtl;
        // access-order LinkedHashMap so that get() bumps recency for LRU eviction
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * Returns the chat memory for the (tenantId, sessionId), creating a new
     * one if none exists. Also evicts expired entries (by TTL) and enforces
     * the LRU cap.
     */
    public ChatMemory getOrCreate(String tenantId, String sessionId) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(sessionId, "sessionId is required");
        String key = key(tenantId, sessionId);

        synchronized (cache) {
            expireIdle();
            Entry entry = cache.get(key);
            if (entry == null) {
                entry = new Entry(MessageWindowChatMemory.builder().maxMessages(maxMessages).build());
                cache.put(key, entry);
                enforceLruCap();
            } else {
                entry.lastAccess = Instant.now();
            }
            return entry.memory;
        }
    }

    /**
     * Clears the memory for a specific session. Returns {@code true} if a
     * cached memory was removed.
     */
    public boolean clear(String tenantId, String sessionId) {
        synchronized (cache) {
            Entry removed = cache.remove(key(tenantId, sessionId));
            if (removed != null) {
                removed.memory.clear();
                return true;
            }
            return false;
        }
    }

    /**
     * Clears all memories for a tenant. Returns the number of sessions removed.
     */
    public int clearTenant(String tenantId) {
        String prefix = tenantId + ":";
        int removed = 0;
        synchronized (cache) {
            Iterator<Map.Entry<String, Entry>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Entry> e = it.next();
                if (e.getKey().startsWith(prefix)) {
                    e.getValue().memory.clear();
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Returns the number of cached memories.
     */
    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public int getMaxCachedSessions() {
        return maxCachedSessions;
    }

    public Duration getIdleTtl() {
        return idleTtl;
    }

    // ── Internal eviction ──────────────────────────────────────────

    private void enforceLruCap() {
        // LinkedHashMap in access-order: first entry is least-recently-used
        while (cache.size() > maxCachedSessions) {
            Iterator<Map.Entry<String, Entry>> it = cache.entrySet().iterator();
            if (!it.hasNext()) break;
            Map.Entry<String, Entry> eldest = it.next();
            eldest.getValue().memory.clear();
            it.remove();
        }
    }

    private void expireIdle() {
        if (idleTtl == null) return;
        Instant cutoff = Instant.now().minus(idleTtl);
        Iterator<Map.Entry<String, Entry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            if (e.getValue().lastAccess.isBefore(cutoff)) {
                e.getValue().memory.clear();
                it.remove();
            }
        }
    }

    private String key(String tenantId, String sessionId) {
        return tenantId + ":" + sessionId;
    }
}
