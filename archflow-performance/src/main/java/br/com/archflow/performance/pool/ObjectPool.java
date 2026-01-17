package br.com.archflow.performance.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Generic object pool for reusing expensive objects.
 *
 * <p>This pool manages a set of reusable objects, reducing the overhead
 * of creating and destroying expensive resources like connections,
 * buffers, or complex objects.</p>
 *
 * <p>Example usage:
 * <pre>{@code
 * ObjectPool<ByteBuffer> bufferPool = ObjectPool.builder()
 *     .supplier(() -> ByteBuffer.allocateDirect(8192))
 *     .reset(ByteBuffer::clear)
 *     .minSize(5)
 *     .maxSize(20)
 *     .build();
 *
 * ByteBuffer buffer = bufferPool.acquire();
 * try {
 *     // Use buffer
 * } finally {
 *     bufferPool.release(buffer);
 * }
 * }</pre>
 *
 * @param <T> The type of object in the pool
 */
public class ObjectPool<T> {

    private static final Logger log = LoggerFactory.getLogger(ObjectPool.class);

    private final Supplier<T> supplier;
    private final ResetAction<T> resetAction;
    private final DestroyAction<T> destroyAction;
    private final int minSize;
    private final int maxSize;
    private final long maxIdleTimeMs;
    private final Deque<PooledObject<T>> idleObjects;
    private final AtomicInteger createdCount;
    private final AtomicInteger acquiredCount;

    private ObjectPool(Builder<T> builder) {
        this.supplier = builder.supplier;
        this.resetAction = builder.resetAction;
        this.destroyAction = builder.destroyAction;
        this.minSize = builder.minSize;
        this.maxSize = builder.maxSize;
        this.maxIdleTimeMs = builder.maxIdleTimeMs;
        this.idleObjects = new ArrayDeque<>(maxSize);
        this.createdCount = new AtomicInteger(0);
        this.acquiredCount = new AtomicInteger(0);

        // Pre-warm the pool to minimum size
        for (int i = 0; i < minSize; i++) {
            idleObjects.offer(createPooledObject());
        }
    }

    /**
     * Creates a new builder for the object pool.
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Acquires an object from the pool.
     *
     * @return The acquired object
     * @throws PoolExhaustedException if the pool is exhausted
     */
    public T acquire() {
        PooledObject<T> pooledObject;

        synchronized (this) {
            // Try to get an idle object
            pooledObject = idleObjects.pollFirst();

            if (pooledObject == null) {
                // No idle objects, create a new one if under max size
                if (createdCount.get() < maxSize) {
                    pooledObject = createPooledObject();
                } else {
                    throw new PoolExhaustedException(
                            "Pool exhausted: max size " + maxSize + " reached"
                    );
                }
            }
        }

        acquiredCount.incrementAndGet();
        return pooledObject.object();
    }

    /**
     * Acquires an object with a timeout.
     *
     * @param timeout The timeout duration
     * @param unit The time unit
     * @return The acquired object
     * @throws PoolExhaustedException if the pool is exhausted
     * @throws InterruptedException if interrupted while waiting
     */
    public T acquire(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);

        while (true) {
            try {
                return acquire();
            } catch (PoolExhaustedException e) {
                if (System.currentTimeMillis() >= deadline) {
                    throw e;
                }
                Thread.sleep(50); // Wait a bit before retrying
            }
        }
    }

    /**
     * Releases an object back to the pool.
     *
     * @param obj The object to release
     */
    public void release(T obj) {
        if (obj == null) {
            return;
        }

        synchronized (this) {
            if (idleObjects.size() >= maxSize) {
                // Pool is full, destroy the object
                if (destroyAction != null) {
                    destroyAction.destroy(obj);
                }
                createdCount.decrementAndGet();
            } else {
                // Reset and return to pool
                if (resetAction != null) {
                    resetAction.reset(obj);
                }
                idleObjects.offerLast(new PooledObject<>(obj));
            }
        }

        acquiredCount.decrementAndGet();
    }

    /**
     * Clears all idle objects from the pool.
     */
    public synchronized void clear() {
        PooledObject<T> pooledObject;
        while ((pooledObject = idleObjects.pollFirst()) != null) {
            if (destroyAction != null) {
                destroyAction.destroy(pooledObject.object());
            }
            createdCount.decrementAndGet();
        }
    }

    /**
     * Gets the number of idle objects in the pool.
     */
    public synchronized int getIdleSize() {
        return idleObjects.size();
    }

    /**
     * Gets the number of acquired objects.
     */
    public int getAcquiredSize() {
        return acquiredCount.get();
    }

    /**
     * Gets the total number of created objects.
     */
    public int getCreatedSize() {
        return createdCount.get();
    }

    /**
     * Gets the minimum pool size.
     */
    public int getMinSize() {
        return minSize;
    }

    /**
     * Gets the maximum pool size.
     */
    public int getMaxSize() {
        return maxSize;
    }

    private PooledObject<T> createPooledObject() {
        T obj = supplier.get();
        createdCount.incrementAndGet();
        return new PooledObject<>(obj);
    }

    /**
     * A pooled object wrapper.
     */
    private record PooledObject<T>(T object) {}

    /**
     * Action to reset an object when returned to the pool.
     */
    @FunctionalInterface
    public interface ResetAction<T> {
        void reset(T object);
    }

    /**
     * Action to destroy an object when removed from the pool.
     */
    @FunctionalInterface
    public interface DestroyAction<T> {
        void destroy(T object);
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
     * Builder for ObjectPool.
     */
    public static class Builder<T> {
        private Supplier<T> supplier;
        private ResetAction<T> resetAction;
        private DestroyAction<T> destroyAction;
        private int minSize = 0;
        private int maxSize = 10;
        private long maxIdleTimeMs = TimeUnit.MINUTES.toMillis(30);

        public Builder<T> supplier(Supplier<T> supplier) {
            this.supplier = supplier;
            return this;
        }

        public Builder<T> reset(ResetAction<T> resetAction) {
            this.resetAction = resetAction;
            return this;
        }

        public Builder<T> destroy(DestroyAction<T> destroyAction) {
            this.destroyAction = destroyAction;
            return this;
        }

        public Builder<T> minSize(int minSize) {
            this.minSize = minSize;
            return this;
        }

        public Builder<T> maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder<T> maxIdleTime(long duration, TimeUnit unit) {
            this.maxIdleTimeMs = unit.toMillis(duration);
            return this;
        }

        public ObjectPool<T> build() {
            if (supplier == null) {
                throw new IllegalStateException("supplier is required");
            }
            if (minSize < 0) {
                throw new IllegalArgumentException("minSize must be >= 0");
            }
            if (maxSize <= 0) {
                throw new IllegalArgumentException("maxSize must be > 0");
            }
            if (minSize > maxSize) {
                throw new IllegalArgumentException("minSize cannot be greater than maxSize");
            }
            return new ObjectPool<>(this);
        }
    }
}
