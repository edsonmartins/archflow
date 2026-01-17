package br.com.archflow.performance.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Generic connection pool with health checking and automatic reconnection.
 *
 * <p>This pool manages connections with the following features:
 * <ul>
 *   <li>Connection validation before use</li>
 *   <li>Automatic reconnection on failure</li>
 *   <li>Idle connection eviction</li>
 *   <li>Max lifetime enforcement</li>
 *   <li>Background health checking</li>
 * </ul>
 *
 * @param <C> The connection type
 */
public class ConnectionPool<C> {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPool.class);
    private static final ConcurrentHashMap<String, ScheduledExecutorService> SCHEDULERS = new ConcurrentHashMap<>();

    private final Supplier<C> connectionFactory;
    private final ConnectionValidator<C> validator;
    private final ConnectionCloser<C> closer;
    private final int minSize;
    private final int maxSize;
    private final long maxLifetimeMs;
    private final long maxIdleTimeMs;
    private final long validationIntervalMs;
    private final String name;

    private final Deque<PooledConnection> idleConnections;
    private final ConcurrentHashMap<PooledConnection, C> activeConnections;
    private final AtomicInteger createdCount;
    private final AtomicInteger acquiredCount;
    private final AtomicBoolean running;
    private ScheduledExecutorService scheduler;

    private ConnectionPool(Builder<C> builder) {
        this.connectionFactory = builder.connectionFactory;
        this.validator = builder.validator;
        this.closer = builder.closer;
        this.minSize = builder.minSize;
        this.maxSize = builder.maxSize;
        this.maxLifetimeMs = builder.maxLifetimeMs != null ? builder.maxLifetimeMs.toMillis() : Long.MAX_VALUE;
        this.maxIdleTimeMs = builder.maxIdleTimeMs != null ? builder.maxIdleTimeMs.toMillis() : Long.MAX_VALUE;
        this.validationIntervalMs = builder.validationIntervalMs != null
                ? builder.validationIntervalMs.toMillis()
                : TimeUnit.SECONDS.toMillis(30);
        this.name = builder.name != null ? builder.name : "connection-pool";

        this.idleConnections = new ArrayDeque<>(maxSize);
        this.activeConnections = new ConcurrentHashMap<>(maxSize);
        this.createdCount = new AtomicInteger(0);
        this.acquiredCount = new AtomicInteger(0);
        this.running = new AtomicBoolean(false);

        start();
    }

    /**
     * Creates a new builder for ConnectionPool.
     */
    public static <C> Builder<C> builder() {
        return new Builder<>();
    }

    /**
     * Starts the pool and initializes minimum connections.
     */
    private void start() {
        if (running.compareAndSet(false, true)) {
            // Initialize minimum connections
            for (int i = 0; i < minSize; i++) {
                try {
                    PooledConnection pc = createConnection();
                    idleConnections.offerLast(pc);
                } catch (Exception e) {
                    log.warn("Failed to create initial connection", e);
                }
            }

            // Start background health checker
            scheduler = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, name + "-health-checker");
                t.setDaemon(true);
                return t;
            });

            scheduler.scheduleAtFixedRate(
                    this::healthCheck,
                    validationIntervalMs,
                    validationIntervalMs,
                    TimeUnit.MILLISECONDS
            );

            log.info("Started connection pool: {} (min={}, max={})", name, minSize, maxSize);
        }
    }

    /**
     * Acquires a connection from the pool.
     */
    public C acquire() {
        PooledConnection pooledConnection;

        synchronized (this) {
            while (true) {
                // Try to get an idle connection
                pooledConnection = idleConnections.pollFirst();

                if (pooledConnection == null) {
                    // No idle connections, create new if under max size
                    if (createdCount.get() < maxSize) {
                        pooledConnection = createConnection();
                        break;
                    } else {
                        throw new PoolExhaustedException(
                                "Connection pool exhausted: max size " + maxSize + " reached"
                        );
                    }
                }

                // Check if connection is valid and not expired
                if (pooledConnection.isExpired()) {
                    closeConnection(pooledConnection);
                    continue; // Try next connection
                }

                if (validator != null && !validator.isValid(pooledConnection.connection)) {
                    closeConnection(pooledConnection);
                    continue; // Try next connection
                }

                break; // Found valid connection
            }
        }

        acquiredCount.incrementAndGet();
        activeConnections.put(pooledConnection, pooledConnection.connection);
        return pooledConnection.connection;
    }

    /**
     * Releases a connection back to the pool.
     */
    public void release(C connection) {
        if (connection == null) {
            return;
        }

        PooledConnection pooledConnection = findAndRemoveActive(connection);
        if (pooledConnection == null) {
            log.warn("Attempted to release unknown connection");
            return;
        }

        acquiredCount.decrementAndGet();

        synchronized (this) {
            if (idleConnections.size() >= maxSize) {
                // Pool is full, close the connection
                closeConnection(pooledConnection);
            } else {
                // Return to pool
                pooledConnection.lastUsed = Instant.now();
                idleConnections.offerLast(pooledConnection);
            }
        }
    }

    /**
     * Invalidates a connection, removing it from the pool.
     */
    public void invalidate(C connection) {
        if (connection == null) {
            return;
        }

        PooledConnection pooledConnection = findAndRemoveActive(connection);
        if (pooledConnection != null) {
            acquiredCount.decrementAndGet();
            closeConnection(pooledConnection);
        }
    }

    /**
     * Shuts down the pool and closes all connections.
     */
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            if (scheduler != null) {
                scheduler.shutdown();
            }

            synchronized (this) {
                // Close idle connections
                PooledConnection pc;
                while ((pc = idleConnections.pollFirst()) != null) {
                    closeConnection(pc);
                }

                // Close active connections
                activeConnections.keySet().forEach(this::closeConnection);
                activeConnections.clear();
            }

            log.info("Shutdown connection pool: {}", name);
        }
    }

    /**
     * Gets the number of idle connections.
     */
    public synchronized int getIdleSize() {
        return idleConnections.size();
    }

    /**
     * Gets the number of active (acquired) connections.
     */
    public int getActiveSize() {
        return acquiredCount.get();
    }

    /**
     * Gets the total number of created connections.
     */
    public int getCreatedSize() {
        return createdCount.get();
    }

    /**
     * Gets pool statistics.
     */
    public PoolStats getStats() {
        return new PoolStats(
                getActiveSize(),
                getIdleSize(),
                createdCount.get(),
                maxSize,
                getActiveSize() + getIdleSize() >= maxSize
        );
    }

    private PooledConnection createConnection() {
        C connection = connectionFactory.get();
        PooledConnection pc = new PooledConnection(connection);
        createdCount.incrementAndGet();
        return pc;
    }

    private void closeConnection(PooledConnection pc) {
        try {
            if (closer != null) {
                closer.close(pc.connection);
            }
            createdCount.decrementAndGet();
        } catch (Exception e) {
            log.error("Error closing connection", e);
        }
    }

    private PooledConnection findAndRemoveActive(C connection) {
        for (PooledConnection pc : activeConnections.keySet()) {
            if (pc.connection == connection || pc.connection.equals(connection)) {
                activeConnections.remove(pc);
                return pc;
            }
        }
        return null;
    }

    private void healthCheck() {
        synchronized (this) {
            // Remove expired or invalid idle connections
            idleConnections.removeIf(pc -> {
                if (pc.isExpired()) {
                    closeConnection(pc);
                    return true;
                }
                if (validator != null && !validator.isValid(pc.connection)) {
                    closeConnection(pc);
                    return true;
                }
                return false;
            });

            // Ensure minimum pool size
            while (idleConnections.size() < minSize && createdCount.get() < maxSize) {
                try {
                    idleConnections.offerLast(createConnection());
                } catch (Exception e) {
                    log.warn("Failed to create connection during health check", e);
                    break;
                }
            }
        }
    }

    private class PooledConnection {
        final C connection;
        final Instant created;
        Instant lastUsed;

        PooledConnection(C connection) {
            this.connection = connection;
            this.created = Instant.now();
            this.lastUsed = Instant.now();
        }

        boolean isExpired() {
            Instant now = Instant.now();
            long age = now.toEpochMilli() - created.toEpochMilli();
            long idleTime = now.toEpochMilli() - lastUsed.toEpochMilli();

            return age >= maxLifetimeMs || idleTime >= maxIdleTimeMs;
        }
    }

    /**
     * Validator for checking if a connection is valid.
     */
    @FunctionalInterface
    public interface ConnectionValidator<C> {
        boolean isValid(C connection);
    }

    /**
     * Closer for closing a connection.
     */
    @FunctionalInterface
    public interface ConnectionCloser<C> {
        void close(C connection);
    }

    /**
     * Exception thrown when the pool is exhausted.
     */
    public static class PoolExhaustedException extends RuntimeException {
        public PoolExhaustedException(String message) {
            super(message);
        }
    }

    /**
     * Pool statistics.
     */
    public record PoolStats(
            int activeConnections,
            int idleConnections,
            int totalCreated,
            int maxSize,
            boolean atCapacity
    ) {}

    /**
     * Builder for ConnectionPool.
     */
    public static class Builder<C> {
        private Supplier<C> connectionFactory;
        private ConnectionValidator<C> validator;
        private ConnectionCloser<C> closer;
        private int minSize = 2;
        private int maxSize = 10;
        private Duration maxLifetimeMs;
        private Duration maxIdleTimeMs;
        private Duration validationIntervalMs;
        private String name;

        public Builder<C> connectionFactory(Supplier<C> connectionFactory) {
            this.connectionFactory = connectionFactory;
            return this;
        }

        public Builder<C> validator(ConnectionValidator<C> validator) {
            this.validator = validator;
            return this;
        }

        public Builder<C> closer(ConnectionCloser<C> closer) {
            this.closer = closer;
            return this;
        }

        public Builder<C> minSize(int minSize) {
            this.minSize = minSize;
            return this;
        }

        public Builder<C> maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder<C> maxLifetime(Duration duration) {
            this.maxLifetimeMs = duration;
            return this;
        }

        public Builder<C> maxIdleTime(Duration duration) {
            this.maxIdleTimeMs = duration;
            return this;
        }

        public Builder<C> validationInterval(Duration duration) {
            this.validationIntervalMs = duration;
            return this;
        }

        public Builder<C> name(String name) {
            this.name = name;
            return this;
        }

        public ConnectionPool<C> build() {
            if (connectionFactory == null) {
                throw new IllegalStateException("connectionFactory is required");
            }
            return new ConnectionPool<>(this);
        }
    }
}
