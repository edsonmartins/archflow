package br.com.archflow.performance;

import br.com.archflow.performance.pool.ObjectPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for ObjectPool.
 */
class ObjectPoolTest {

    private ObjectPool<StringBuilder> pool;

    @BeforeEach
    void setUp() {
        pool = ObjectPool.<StringBuilder>builder()
                .supplier(StringBuilder::new)
                .reset(sb -> sb.setLength(0))
                .minSize(2)
                .maxSize(10)
                .build();
    }

    @AfterEach
    void tearDown() {
        pool.clear();
    }

    @Test
    void testAcquireAndRelease() {
        StringBuilder obj = pool.acquire();
        assertThat(obj).isNotNull();

        obj.append("test");
        pool.release(obj);

        // Acquire again - should get the same or reset object
        StringBuilder obj2 = pool.acquire();
        assertThat(obj2).isNotNull();
        assertThat(obj2.length()).isEqualTo(0); // Should be reset

        pool.release(obj2);
    }

    @Test
    void testPoolExhausted() {
        ObjectPool<Object> tinyPool = ObjectPool.<Object>builder()
                .supplier(Object::new)
                .minSize(0)
                .maxSize(2)
                .build();

        Object obj1 = tinyPool.acquire();
        Object obj2 = tinyPool.acquire();

        // Pool is exhausted
        assertThatThrownBy(() -> tinyPool.acquire())
                .isInstanceOf(ObjectPool.PoolExhaustedException.class);

        tinyPool.release(obj1);
        // Now should be able to acquire
        Object obj3 = tinyPool.acquire();
        assertThat(obj3).isNotNull();
    }

    @Test
    void testPoolSizes() {
        assertThat(pool.getMinSize()).isEqualTo(2);
        assertThat(pool.getMaxSize()).isEqualTo(10);
        assertThat(pool.getIdleSize()).isGreaterThanOrEqualTo(2); // Pre-warmed
    }

    @Test
    void testClear() {
        pool.acquire();
        assertThat(pool.getIdleSize()).isGreaterThan(0);

        pool.clear();
        assertThat(pool.getIdleSize()).isEqualTo(0);
    }

    @Test
    void testConcurrentAcquire() throws InterruptedException {
        ObjectPool<StringBuilder> pool = ObjectPool.<StringBuilder>builder()
                .supplier(StringBuilder::new)
                .reset(sb -> sb.setLength(0))
                .minSize(0)
                .maxSize(5)
                .build();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                try {
                    StringBuilder obj = pool.acquire();
                    obj.append("test");
                    Thread.sleep(10);
                    pool.release(obj);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Pool may be exhausted
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // At least the pool size should succeed
        assertThat(successCount.get()).isGreaterThan(0);
    }

    @Test
    void testAcquireWithTimeout() throws InterruptedException {
        StringBuilder obj1 = pool.acquire();
        StringBuilder obj2 = pool.acquire();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);

        executor.execute(() -> {
            try {
                // Wait a bit then release
                Thread.sleep(100);
                pool.release(obj2);
            } catch (Exception e) {
                // Ignore
            } finally {
                latch.countDown();
            }
        });

        // Should eventually acquire after timeout
        StringBuilder obj3 = pool.acquire(200, TimeUnit.MILLISECONDS);
        assertThat(obj3).isNotNull();

        pool.release(obj1);
        pool.release(obj3);
        executor.shutdown();
    }

    @Test
    void testResetAction() {
        StringBuilder obj = pool.acquire();
        obj.append("hello world");
        pool.release(obj);

        StringBuilder obj2 = pool.acquire();
        assertThat(obj2.toString()).isEmpty(); // Should be reset
        pool.release(obj2);
    }
}
