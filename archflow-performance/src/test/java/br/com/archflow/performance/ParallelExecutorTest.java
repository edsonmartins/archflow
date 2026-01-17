package br.com.archflow.performance;

import br.com.archflow.performance.concurrent.ParallelExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for ParallelExecutor.
 */
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
        executor.shutdown();
    }

    @Test
    void testSubmitTask() throws Exception {
        var future = executor.submit(() -> "result");

        assertThat(future.get()).isEqualTo("result");
    }

    @Test
    void testSubmitRunnable() throws Exception {
        var future = executor.submit(() -> {
            // Do nothing
        });

        future.get(); // Should complete without exception
    }

    @Test
    void testSubmitAll() throws Exception {
        List<Callable<String>> tasks = Arrays.asList(
                () -> "result1",
                () -> "result2",
                () -> "result3"
        );

        List<String> results = executor.executeAll(tasks);

        assertThat(results).containsExactly("result1", "result2", "result3");
    }

    @Test
    void testExecuteAllWithTimeout() throws Exception {
        List<Callable<String>> tasks = Arrays.asList(
                () -> "result1",
                () -> {
                    Thread.sleep(100);
                    return "result2";
                },
                () -> "result3"
        );

        List<String> results = executor.executeAll(tasks, 5, TimeUnit.SECONDS);

        assertThat(results).containsExactly("result1", "result2", "result3");
    }

    @Test
    void testExecuteAllTimeout() {
        List<Callable<String>> tasks = Arrays.asList(
                () -> "result1",
                () -> {
                    Thread.sleep(10000);
                    return "result2";
                }
        );

        assertThatThrownBy(() -> executor.executeAll(tasks, 100, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    @Test
    void testExecuteAsync() throws Exception {
        CompletableFuture<String> future = executor.executeAsync(() -> "async-result");

        assertThat(future.get()).isEqualTo("async-result");
    }

    @Test
    void testExecuteAsyncWithDelay() throws Exception {
        CompletableFuture<String> future = executor.executeAsync(
                () -> "delayed-result",
                100,
                TimeUnit.MILLISECONDS
        );

        assertThat(future.get()).isEqualTo("delayed-result");
    }

    @Test
    void testExecuteAny() throws Exception {
        List<Callable<String>> tasks = Arrays.asList(
                () -> {
                    throw new RuntimeException("Failed");
                },
                () -> {
                    Thread.sleep(100);
                    return "success";
                },
                () -> {
                    throw new RuntimeException("Also failed");
                }
        );

        String result = executor.executeAny(tasks);
        assertThat(result).isEqualTo("success");
    }

    @Test
    void testGetStats() {
        executor.submit(() -> "test");
        ParallelExecutor.ExecutorStats stats = executor.getStats();

        assertThat(stats).isNotNull();
    }

    @Test
    void testDefaultExecutor() {
        ParallelExecutor defaultExec = ParallelExecutor.defaultExecutor();

        assertThat(defaultExec).isNotNull();
        defaultExec.shutdown();
    }

    @Test
    void testIsVirtualThreadsAvailable() {
        boolean available = ParallelExecutor.isVirtualThreadsAvailable();

        // Should return false on Java 17 (our target)
        // Would return true on Java 21+
        assertThat(available).isNotNull();
    }
}
