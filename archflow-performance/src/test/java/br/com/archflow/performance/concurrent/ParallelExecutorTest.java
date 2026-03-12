package br.com.archflow.performance.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ParallelExecutor")
class ParallelExecutorTest {

    private ParallelExecutor executor;

    @BeforeEach
    void setUp() {
        executor = ParallelExecutor.builder()
                .useVirtualThreads(false)
                .poolSize(4)
                .name("test-executor")
                .build();
    }

    @AfterEach
    void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    @Nested
    @DisplayName("executeAll")
    class ExecuteAll {

        @Test
        @DisplayName("should execute all tasks and return results")
        void shouldExecuteAllTasks() throws InterruptedException {
            // Arrange
            List<Callable<String>> tasks = List.of(
                    () -> "result-1",
                    () -> "result-2",
                    () -> "result-3"
            );

            // Act
            List<String> results = executor.executeAll(tasks);

            // Assert
            assertThat(results).containsExactly("result-1", "result-2", "result-3");
        }

        @Test
        @DisplayName("should execute tasks in parallel")
        void shouldExecuteInParallel() throws InterruptedException {
            // Arrange
            AtomicInteger concurrentCount = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);

            List<Callable<Integer>> tasks = List.of(
                    () -> {
                        int current = concurrentCount.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, current));
                        Thread.sleep(100);
                        concurrentCount.decrementAndGet();
                        return current;
                    },
                    () -> {
                        int current = concurrentCount.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, current));
                        Thread.sleep(100);
                        concurrentCount.decrementAndGet();
                        return current;
                    },
                    () -> {
                        int current = concurrentCount.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, current));
                        Thread.sleep(100);
                        concurrentCount.decrementAndGet();
                        return current;
                    }
            );

            // Act
            executor.executeAll(tasks);

            // Assert - at least 2 tasks should have run concurrently
            assertThat(maxConcurrent.get()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("should propagate exception from failing task")
        void shouldPropagateException() {
            // Arrange
            List<Callable<String>> tasks = List.of(
                    () -> "ok",
                    () -> { throw new IllegalStateException("task failed"); }
            );

            // Act & Assert
            assertThatThrownBy(() -> executor.executeAll(tasks))
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("should respect timeout for executeAll with timeout")
        void shouldRespectTimeout() {
            // Arrange
            List<Callable<String>> tasks = List.of(
                    () -> {
                        Thread.sleep(5000);
                        return "slow";
                    }
            );

            // Act & Assert
            assertThatThrownBy(() -> executor.executeAll(tasks, 200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
        }

        @Test
        @DisplayName("should complete within timeout when tasks are fast")
        void shouldCompleteWithinTimeout() throws InterruptedException, TimeoutException {
            // Arrange
            List<Callable<String>> tasks = List.of(
                    () -> "fast-1",
                    () -> "fast-2"
            );

            // Act
            List<String> results = executor.executeAll(tasks, 5, TimeUnit.SECONDS);

            // Assert
            assertThat(results).containsExactly("fast-1", "fast-2");
        }
    }

    @Nested
    @DisplayName("submit")
    class Submit {

        @Test
        @DisplayName("should submit callable and return future with result")
        void shouldSubmitCallable() throws Exception {
            // Act
            Future<String> future = executor.submit(() -> "hello");

            // Assert
            assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo("hello");
        }

        @Test
        @DisplayName("should submit runnable")
        void shouldSubmitRunnable() throws Exception {
            // Arrange
            AtomicInteger counter = new AtomicInteger(0);

            // Act
            Future<?> future = executor.submit((Runnable) counter::incrementAndGet);
            future.get(2, TimeUnit.SECONDS);

            // Assert
            assertThat(counter.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should submit multiple tasks via submitAll")
        void shouldSubmitAll() throws Exception {
            // Arrange
            List<Callable<Integer>> tasks = List.of(
                    () -> 1,
                    () -> 2,
                    () -> 3
            );

            // Act
            List<Future<Integer>> futures = executor.submitAll(tasks);

            // Assert
            assertThat(futures).hasSize(3);
            assertThat(futures.get(0).get(2, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(futures.get(1).get(2, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(futures.get(2).get(2, TimeUnit.SECONDS)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("executeAsync")
    class ExecuteAsync {

        @Test
        @DisplayName("should execute async and return CompletableFuture")
        void shouldExecuteAsync() throws Exception {
            // Act
            var future = executor.executeAsync(() -> "async-result");

            // Assert
            assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo("async-result");
        }

        @Test
        @DisplayName("should complete exceptionally on task failure")
        void shouldCompleteExceptionallyOnFailure() {
            // Act
            var future = executor.executeAsync(() -> {
                throw new RuntimeException("async fail");
            });

            // Assert
            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("should execute async with delay")
        void shouldExecuteAsyncWithDelay() throws Exception {
            // Arrange
            long start = System.currentTimeMillis();

            // Act
            var future = executor.executeAsync(() -> "delayed", 200, TimeUnit.MILLISECONDS);
            String result = future.get(5, TimeUnit.SECONDS);

            // Assert
            long elapsed = System.currentTimeMillis() - start;
            assertThat(result).isEqualTo("delayed");
            assertThat(elapsed).isGreaterThanOrEqualTo(150); // allow some timing slack
        }
    }

    @Nested
    @DisplayName("executeAny")
    class ExecuteAny {

        @Test
        @DisplayName("should return first successful result")
        void shouldReturnFirstSuccessfulResult() throws Exception {
            // Arrange
            List<Callable<String>> tasks = List.of(
                    () -> "first",
                    () -> "second",
                    () -> "third"
            );

            // Act
            String result = executor.executeAny(tasks);

            // Assert
            assertThat(result).isIn("first", "second", "third");
        }

        @Test
        @DisplayName("should skip failed tasks and return successful one")
        void shouldSkipFailedTasks() throws Exception {
            // Arrange
            List<Callable<String>> tasks = List.of(
                    () -> { throw new RuntimeException("fail-1"); },
                    () -> "success",
                    () -> { throw new RuntimeException("fail-2"); }
            );

            // Act
            String result = executor.executeAny(tasks);

            // Assert
            assertThat(result).isEqualTo("success");
        }

        @Test
        @DisplayName("should throw ExecutionException when all tasks fail")
        void shouldThrowWhenAllFail() {
            // Arrange
            List<Callable<String>> tasks = List.of(
                    () -> { throw new RuntimeException("fail-1"); },
                    () -> { throw new RuntimeException("fail-2"); }
            );

            // Act & Assert
            assertThatThrownBy(() -> executor.executeAny(tasks))
                    .isInstanceOf(ExecutionException.class);
        }
    }

    @Nested
    @DisplayName("shutdown")
    class Shutdown {

        @Test
        @DisplayName("should shut down executor gracefully")
        void shouldShutdownGracefully() {
            // Act
            executor.shutdown();

            // Assert
            assertThat(executor.isShutdown()).isTrue();
        }

        @Test
        @DisplayName("should shut down executor immediately with shutdownNow")
        void shouldShutdownNow() {
            // Act
            executor.shutdownNow();

            // Assert
            assertThat(executor.isShutdown()).isTrue();
        }

        @Test
        @DisplayName("should report isShutdown correctly before shutdown")
        void shouldReportNotShutdownInitially() {
            // Assert
            assertThat(executor.isShutdown()).isFalse();
        }
    }

    @Nested
    @DisplayName("stats")
    class Stats {

        @Test
        @DisplayName("should return executor stats for thread pool")
        void shouldReturnStats() throws Exception {
            // Arrange - submit a task and wait for it
            executor.submit(() -> "done").get(2, TimeUnit.SECONDS);

            // Act
            ParallelExecutor.ExecutorStats stats = executor.getStats();

            // Assert
            assertThat(stats).isNotNull();
            assertThat(stats.isVirtualThreads()).isFalse();
            assertThat(stats.completedTasks()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("should expose underlying executor")
        void shouldExposeUnderlyingExecutor() {
            // Act & Assert
            assertThat(executor.getExecutor()).isNotNull();
        }
    }

    @Nested
    @DisplayName("builder")
    class BuilderTest {

        @Test
        @DisplayName("should create executor with custom name")
        void shouldCreateWithCustomName() {
            // Act
            ParallelExecutor namedExecutor = ParallelExecutor.builder()
                    .useVirtualThreads(false)
                    .name("custom-executor")
                    .poolSize(2)
                    .build();

            // Assert
            assertThat(namedExecutor).isNotNull();
            assertThat(namedExecutor.isShutdown()).isFalse();
            namedExecutor.shutdownNow();
        }

        @Test
        @DisplayName("should create default executor")
        void shouldCreateDefaultExecutor() {
            // Act
            ParallelExecutor defaultExec = ParallelExecutor.defaultExecutor();

            // Assert
            assertThat(defaultExec).isNotNull();
            defaultExec.shutdownNow();
        }
    }

    @Nested
    @DisplayName("allOf and anyOf")
    class CompletableFutureHelpers {

        @Test
        @DisplayName("allOf should complete when all futures complete")
        void allOfShouldComplete() throws Exception {
            // Arrange
            var f1 = executor.executeAsync(() -> "a");
            var f2 = executor.executeAsync(() -> "b");

            // Act
            ParallelExecutor.allOf(f1, f2).get(2, TimeUnit.SECONDS);

            // Assert
            assertThat(f1.isDone()).isTrue();
            assertThat(f2.isDone()).isTrue();
        }

        @Test
        @DisplayName("anyOf should complete when any future completes")
        void anyOfShouldComplete() throws Exception {
            // Arrange
            var f1 = executor.executeAsync(() -> "fast");
            var f2 = executor.executeAsync(() -> {
                Thread.sleep(5000);
                return "slow";
            });

            // Act
            Object result = ParallelExecutor.anyOf(f1, f2).get(2, TimeUnit.SECONDS);

            // Assert
            assertThat(result).isNotNull();
        }
    }
}
