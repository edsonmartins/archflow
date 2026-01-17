package br.com.archflow.performance;

import br.com.archflow.performance.pool.ConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ConnectionPool.
 */
class ConnectionPoolTest {

    private ConnectionPool<TestConnection> pool;
    private final AtomicInteger connectionCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        connectionCount.set(0);
        pool = ConnectionPool.<TestConnection>builder()
                .connectionFactory(() -> new TestConnection(connectionCount.incrementAndGet()))
                .validator(TestConnection::isValid)
                .closer(TestConnection::close)
                .minSize(2)
                .maxSize(10)
                .maxLifetime(Duration.ofHours(1))
                .maxIdleTime(Duration.ofMinutes(30))
                .name("test-pool")
                .build();
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    @Test
    void testAcquireAndRelease() {
        TestConnection conn = pool.acquire();
        assertThat(conn).isNotNull();
        assertThat(conn.id).isPositive();

        pool.release(conn);
        assertThat(pool.getActiveSize()).isEqualTo(0);
        assertThat(pool.getIdleSize()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void testInvalidate() {
        TestConnection conn = pool.acquire();
        assertThat(pool.getActiveSize()).isEqualTo(1);

        pool.invalidate(conn);
        assertThat(pool.getActiveSize()).isEqualTo(0);
        assertThat(conn.closed).isTrue();
    }

    @Test
    void testPoolStats() {
        ConnectionPool.PoolStats stats = pool.getStats();

        assertThat(stats.activeConnections()).isEqualTo(0);
        assertThat(stats.idleConnections()).isGreaterThan(0);
        assertThat(stats.totalCreated()).isGreaterThan(0);
        assertThat(stats.maxSize()).isEqualTo(10);
    }

    @Test
    void testConcurrentAcquire() throws InterruptedException {
        int threadCount = 5;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                try {
                    TestConnection conn = pool.acquire();
                    Thread.sleep(50);
                    pool.release(conn);
                } catch (Exception e) {
                    // Ignore
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }

        for (Thread t : threads) {
            t.join();
        }

        // All connections should be released
        assertThat(pool.getActiveSize()).isEqualTo(0);
    }

    @Test
    void testMinSizePreWarm() {
        // Pool should have at least minSize connections
        assertThat(pool.getCreatedSize()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void testShutdown() {
        pool.shutdown();
        assertThat(pool.getActiveSize()).isEqualTo(0);
        assertThat(pool.getIdleSize()).isEqualTo(0);
    }

    static class TestConnection {
        final int id;
        boolean closed;
        boolean valid = true;

        TestConnection(int id) {
            this.id = id;
        }

        boolean isValid() {
            return !closed && valid;
        }

        void close() {
            this.closed = true;
        }
    }
}
