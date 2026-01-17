package br.com.archflow.agent.tool.interceptor;

import br.com.archflow.agent.tool.ToolContext;
import br.com.archflow.agent.tool.ToolInterceptor;
import br.com.archflow.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Interceptor que implementa caching de resultados de tools.
 *
 * <p>Características:
 * <ul>
 *   <li>Cache em memória com TTL configurável</li>
 *   <li>Chave composta por: toolName + input hash</li>
 *   <li>Expurgação automática de entradas expiradas</li>
 *   <li>Configuração de TTL global ou por tool</li>
 * </ul>
 *
 * <p>Exemplo de configuração de TTL por tool:
 * <pre>{@code
 * CachingInterceptor interceptor = new CachingInterceptor();
 * interceptor.setToolTtl("expensive_api", Duration.ofHours(1));  // Cache de 1 hora
 * interceptor.setToolTtl("fast_operation", Duration.ofSeconds(30));  // Cache de 30 segundos
 * }</pre>
 */
public class CachingInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CachingInterceptor.class);

    private final ConcurrentHashMap<String, CacheEntry> cache;
    private final Duration defaultTtl;
    private final int maxCacheSize;
    private final ScheduledExecutorService cleanupExecutor;
    private final ConcurrentHashMap<String, Duration> toolTtls;

    public CachingInterceptor() {
        this(Duration.ofMinutes(5), 1000);
    }

    public CachingInterceptor(Duration defaultTtl, int maxCacheSize) {
        this.cache = new ConcurrentHashMap<>();
        this.defaultTtl = defaultTtl;
        this.maxCacheSize = maxCacheSize;
        this.toolTtls = new ConcurrentHashMap<>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "cache-cleanup");
            thread.setDaemon(true);
            return thread;
        });

        // Executa limpeza a cada minuto
        this.cleanupExecutor.scheduleAtFixedRate(
                this::cleanupExpiredEntries,
                1, 1, TimeUnit.MINUTES
        );
    }

    @Override
    public void beforeExecute(ToolContext context) {
        String cacheKey = buildCacheKey(context);
        CacheEntry entry = cache.get(cacheKey);

        if (entry != null && !entry.isExpired()) {
            log.debug("[{}] Cache HIT para tool {} com chave {}",
                    context.getExecutionId(), context.getToolName(), cacheKey);

            context.setCached(true);
            context.setAttribute("_cacheHit", true);
            // O resultado será usado pelo executor
        } else {
            log.trace("[{}] Cache MISS para tool {} com chave {}",
                    context.getExecutionId(), context.getToolName(), cacheKey);
        }
    }

    @Override
    public ToolResult afterExecute(ToolContext context, ToolResult result) {
        // Se foi cache hit, não armazena novamente
        if (Boolean.TRUE.equals(context.getAttribute("_cacheHit"))) {
            return result;
        }

        // Só cacheia resultados de sucesso
        if (result.isSuccess()) {
            String cacheKey = buildCacheKey(context);
            Duration ttl = getTtlForTool(context.getToolName());
            Instant expiryTime = Instant.now().plus(ttl);

            CacheEntry entry = new CacheEntry(result, expiryTime);

            // Verifica tamanho do cache antes de adicionar
            if (cache.size() >= maxCacheSize) {
                evictOldestEntry();
            }

            cache.put(cacheKey, entry);

            log.trace("[{}] Resultado cacheado com chave {}, expira em {}",
                    context.getExecutionId(), cacheKey, ttl);
        }

        return result;
    }

    @Override
    public void onError(ToolContext context, Throwable error) {
        // Em caso de erro, remove entrada do cache se existir
        String cacheKey = buildCacheKey(context);
        cache.remove(cacheKey);
        log.debug("[{}] Entrada removida do cache devido a erro: {}",
                context.getExecutionId(), cacheKey);
    }

    @Override
    public int order() {
        // Caching deve executar antes da tool (para checar)
        // e depois (para armazenar)
        return 50;
    }

    @Override
    public String getName() {
        return "CachingInterceptor";
    }

    /**
     * Verifica se há um resultado em cache para o contexto.
     *
     * @param context Contexto da execução
     * @return Resultado em cache, ou null se não existir ou expirou
     */
    public ToolResult<?> getCachedResult(ToolContext context) {
        String cacheKey = buildCacheKey(context);
        CacheEntry entry = cache.get(cacheKey);

        if (entry != null && !entry.isExpired()) {
            return entry.result();
        }

        return null;
    }

    /**
     * Limpa o cache.
     */
    public void clearCache() {
        cache.clear();
        log.info("Cache limpo");
    }

    /**
     * Limpa o cache para uma tool específica.
     *
     * @param toolName Nome da tool
     */
    public void clearCacheForTool(String toolName) {
        String prefix = toolName + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
        log.info("Cache limpo para tool {}", toolName);
    }

    /**
     * Retorna estatísticas do cache.
     */
    public CacheStats getStats() {
        int totalEntries = cache.size();
        int expiredEntries = (int) cache.values().stream()
                .filter(CacheEntry::isExpired)
                .count();

        return new CacheStats(totalEntries, totalEntries - expiredEntries, expiredEntries);
    }

    /**
     * Configura o TTL para uma tool específica.
     *
     * @param toolName Nome da tool
     * @param ttl Time-to-live para cache desta tool
     */
    public void setToolTtl(String toolName, Duration ttl) {
        if (toolName == null || toolName.isEmpty()) {
            throw new IllegalArgumentException("Tool name cannot be null or empty");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            toolTtls.remove(toolName);
            log.info("TTL removido para tool {}", toolName);
        } else {
            toolTtls.put(toolName, ttl);
            log.info("TTL configurado para tool {}: {}", toolName, ttl);
        }
    }

    /**
     * Retorna o TTL configurado para uma tool específica.
     *
     * @param toolName Nome da tool
     * @return TTL configurado, ou null se não houver configuração específica
     */
    public Duration getToolTtl(String toolName) {
        return toolTtls.get(toolName);
    }

    /**
     * Retorna todos os TTLs configurados por tool.
     *
     * @return Mapa imutável de toolName -> TTL
     */
    public Map<String, Duration> getAllToolTtls() {
        return Map.copyOf(toolTtls);
    }

    private String buildCacheKey(ToolContext context) {
        // chave = toolName:inputHash
        int inputHash = context.getInput() != null
                ? context.getInput().hashCode()
                : 0;
        return context.getToolName() + ":" + inputHash;
    }

    private Duration getTtlForTool(String toolName) {
        // Primeiro verifica se há TTL específico para a tool
        Duration toolTtl = toolTtls.get(toolName);
        if (toolTtl != null) {
            log.trace("Usando TTL específico para tool {}: {}", toolName, toolTtl);
            return toolTtl;
        }
        // Caso contrário, usa TTL global
        return defaultTtl;
    }

    private void cleanupExpiredEntries() {
        int beforeSize = cache.size();
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        int afterSize = cache.size();

        if (beforeSize - afterSize > 0) {
            log.debug("Limpeza de cache: {} entradas expiradas removidas", beforeSize - afterSize);
        }
    }

    private void evictOldestEntry() {
        // Encontra e remove a entrada mais antiga
        cache.entrySet().stream()
                .min((e1, e2) -> e1.getValue().createdAt().compareTo(e2.getValue().createdAt()))
                .ifPresent(entry -> {
                    cache.remove(entry.getKey());
                    log.trace("Entrada evictada do cache: {}", entry.getKey());
                });
    }

    /**
     * Encerra o interceptor e o executor de limpeza.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        cache.clear();
    }

    private record CacheEntry(
            ToolResult<?> result,
            Instant expiryTime,
            Instant createdAt
    ) {
        public CacheEntry(ToolResult<?> result, Instant expiryTime) {
            this(result, expiryTime, Instant.now());
        }

        public boolean isExpired() {
            return Instant.now().isAfter(expiryTime);
        }
    }

    public record CacheStats(
            int totalEntries,
            int activeEntries,
            int expiredEntries
    ) {
        public double getHitRate() {
            return totalEntries > 0 ? (double) activeEntries / totalEntries : 0;
        }
    }

    public static CachingInterceptor create() {
        return new CachingInterceptor();
    }

    public static CachingInterceptor create(Duration ttl, int maxSize) {
        return new CachingInterceptor(ttl, maxSize);
    }
}
