package br.com.archflow.performance.pool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ObjectPool")
class ObjectPoolTest {

    @Nested
    @DisplayName("acquire and release")
    class AcquireAndRelease {

        @Test
        @DisplayName("should acquire an object from the pool")
        void shouldAcquireObject() {
            // Arrange
            AtomicInteger counter = new AtomicInteger(0);
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj-" + counter.incrementAndGet())
                    .maxSize(5)
                    .build();

            // Act
            String obj = pool.acquire();

            // Assert
            assertThat(obj).startsWith("obj-");
            assertThat(pool.getAcquiredSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("should release object back to the pool")
        void shouldReleaseObject() {
            // Arrange
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj")
                    .maxSize(5)
                    .build();

            String obj = pool.acquire();

            // Act
            pool.release(obj);

            // Assert
            assertThat(pool.getAcquiredSize()).isEqualTo(0);
            assertThat(pool.getIdleSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("should reuse released objects")
        void shouldReuseReleasedObjects() {
            // Arrange
            AtomicInteger counter = new AtomicInteger(0);
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj-" + counter.incrementAndGet())
                    .maxSize(5)
                    .build();

            String first = pool.acquire();
            pool.release(first);

            // Act
            String second = pool.acquire();

            // Assert
            assertThat(second).isSameAs(first);
            assertThat(pool.getCreatedSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("should handle release of null gracefully")
        void shouldHandleNullRelease() {
            // Arrange
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj")
                    .maxSize(5)
                    .build();

            // Act & Assert - should not throw
            pool.release(null);
        }
    }

    @Nested
    @DisplayName("pool exhaustion")
    class PoolExhaustion {

        @Test
        @DisplayName("should throw PoolExhaustedException when max size reached")
        void shouldThrowWhenExhausted() {
            // Arrange
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj")
                    .maxSize(2)
                    .build();

            pool.acquire();
            pool.acquire();

            // Act & Assert
            assertThatThrownBy(pool::acquire)
                    .isInstanceOf(ObjectPool.PoolExhaustedException.class)
                    .hasMessageContaining("Pool exhausted");
        }

        @Test
        @DisplayName("should allow acquire after release frees capacity")
        void shouldAllowAcquireAfterRelease() {
            // Arrange
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj")
                    .maxSize(1)
                    .build();

            String obj = pool.acquire();
            pool.release(obj);

            // Act
            String obj2 = pool.acquire();

            // Assert
            assertThat(obj2).isNotNull();
        }
    }

    @Nested
    @DisplayName("reset action")
    class ResetActionTest {

        @Test
        @DisplayName("should invoke reset action when object is released")
        void shouldInvokeResetAction() {
            // Arrange
            AtomicInteger resetCount = new AtomicInteger(0);
            ObjectPool<StringBuilder> pool = ObjectPool.<StringBuilder>builder()
                    .supplier(StringBuilder::new)
                    .reset(sb -> {
                        sb.setLength(0);
                        resetCount.incrementAndGet();
                    })
                    .maxSize(5)
                    .build();

            StringBuilder sb = pool.acquire();
            sb.append("data");

            // Act
            pool.release(sb);

            // Assert
            assertThat(resetCount.get()).isEqualTo(1);
            // Acquire the same object and verify it was reset
            StringBuilder reused = pool.acquire();
            assertThat(reused.length()).isEqualTo(0);
        }

        @Test
        @DisplayName("should work without a reset action")
        void shouldWorkWithoutResetAction() {
            // Arrange
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj")
                    .maxSize(5)
                    .build();

            String obj = pool.acquire();

            // Act & Assert - should not throw
            pool.release(obj);
            assertThat(pool.getIdleSize()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("min and max size")
    class MinMaxSize {

        @Test
        @DisplayName("should pre-warm pool to minimum size")
        void shouldPreWarmToMinSize() {
            // Act
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj")
                    .minSize(3)
                    .maxSize(10)
                    .build();

            // Assert
            assertThat(pool.getIdleSize()).isEqualTo(3);
            assertThat(pool.getCreatedSize()).isEqualTo(3);
        }

        @Test
        @DisplayName("should report min and max size correctly")
        void shouldReportMinMaxSize() {
            // Act
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj")
                    .minSize(2)
                    .maxSize(8)
                    .build();

            // Assert
            assertThat(pool.getMinSize()).isEqualTo(2);
            assertThat(pool.getMaxSize()).isEqualTo(8);
        }

        @Test
        @DisplayName("should reject negative minSize")
        void shouldRejectNegativeMinSize() {
            assertThatThrownBy(() -> ObjectPool.<String>builder()
                    .supplier(() -> "obj")
                    .minSize(-1)
                    .maxSize(5)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("minSize must be >= 0");
        }

        @Test
        @DisplayName("should reject zero maxSize")
        void shouldRejectZeroMaxSize() {
            assertThatThrownBy(() -> ObjectPool.<String>builder()
                    .supplier(() -> "obj")
                    .maxSize(0)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxSize must be > 0");
        }

        @Test
        @DisplayName("should reject minSize greater than maxSize")
        void shouldRejectMinSizeGreaterThanMaxSize() {
            assertThatThrownBy(() -> ObjectPool.<String>builder()
                    .supplier(() -> "obj")
                    .minSize(10)
                    .maxSize(5)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("minSize cannot be greater than maxSize");
        }

        @Test
        @DisplayName("should require a supplier")
        void shouldRequireSupplier() {
            assertThatThrownBy(() -> ObjectPool.<String>builder()
                    .maxSize(5)
                    .build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("supplier is required");
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("should remove all idle objects from the pool")
        void shouldClearIdleObjects() {
            // Arrange
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj")
                    .minSize(3)
                    .maxSize(10)
                    .build();
            assertThat(pool.getIdleSize()).isEqualTo(3);

            // Act
            pool.clear();

            // Assert
            assertThat(pool.getIdleSize()).isEqualTo(0);
        }

        @Test
        @DisplayName("should invoke destroy action on cleared objects")
        void shouldInvokeDestroyAction() {
            // Arrange
            AtomicInteger destroyCount = new AtomicInteger(0);
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj")
                    .destroy(obj -> destroyCount.incrementAndGet())
                    .minSize(3)
                    .maxSize(10)
                    .build();

            // Act
            pool.clear();

            // Assert
            assertThat(destroyCount.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("should allow acquiring new objects after clear")
        void shouldAllowAcquireAfterClear() {
            // Arrange
            AtomicInteger counter = new AtomicInteger(0);
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj-" + counter.incrementAndGet())
                    .minSize(2)
                    .maxSize(5)
                    .build();
            pool.clear();

            // Act
            String obj = pool.acquire();

            // Assert
            assertThat(obj).isNotNull();
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("should handle concurrent acquire and release")
        void shouldHandleConcurrentAccess() throws InterruptedException {
            // Arrange
            AtomicInteger counter = new AtomicInteger(0);
            ObjectPool<String> pool = ObjectPool.<String>builder()
                    .supplier(() -> "obj-" + counter.incrementAndGet())
                    .maxSize(10)
                    .build();

            int threadCount = 5;
            int iterationsPerThread = 20;
            List<Thread> threads = new ArrayList<>();

            // Act
            for (int i = 0; i < threadCount; i++) {
                Thread t = new Thread(() -> {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        String obj = pool.acquire();
                        // Simulate some work
                        Thread.yield();
                        pool.release(obj);
                    }
                });
                threads.add(t);
                t.start();
            }

            for (Thread t : threads) {
                t.join(5000);
            }

            // Assert
            assertThat(pool.getAcquiredSize()).isEqualTo(0);
            assertThat(pool.getCreatedSize()).isGreaterThan(0);
        }
    }
}
