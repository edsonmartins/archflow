package br.com.archflow.performance.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * High-performance parallel execution utilities.
 *
 * <p>Provides optimized parallel execution capabilities:
 * <ul>
 *   <li>Virtual thread-based executor (Java 21+)</li>
 *   <li>Traditional thread pool executor</li>
 *   <li>Work stealing fork-join pool</li>
 *   <li>Async/await pattern support</li>
 *   <li>Timeout and cancellation support</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * ParallelExecutor executor = ParallelExecutor.builder()
 *     .useVirtualThreads(true)
 *     .build();
 *
 * List<Future<String>> futures = executor.submitAll(List.of(
 *     () -> task1(),
 *     () -> task2(),
 *     () -> task3()
 * ));
 *
 * List<String> results = executor.getAll(futures);
 * }</pre>
 */
public class ParallelExecutor {

    private static final Logger log = LoggerFactory.getLogger(ParallelExecutor.class);

    private final ExecutorService executor;
    private final boolean useVirtualThreads;
    private final int poolSize;
    private final String name;

    private ParallelExecutor(Builder builder) {
        this.name = builder.name != null ? builder.name : "parallel-executor";
        this.useVirtualThreads = builder.useVirtualThreads && isVirtualThreadsAvailable();
        this.poolSize = builder.poolSize;

        if (this.useVirtualThreads) {
            this.executor = createVirtualThreadExecutor();
            log.info("Created virtual thread executor: {}", name);
        } else {
            this.executor = createThreadPoolExecutor(builder.poolSize, builder.keepAliveTime);
            log.info("Created thread pool executor: {} (size: {})", name, builder.poolSize);
        }
    }

    /**
     * Creates a new builder for ParallelExecutor.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the default shared executor.
     */
    public static ParallelExecutor defaultExecutor() {
        return builder()
                .useVirtualThreads(isVirtualThreadsAvailable())
                .build();
    }

    /**
     * Checks if virtual threads are available (Java 21+).
     */
    public static boolean isVirtualThreadsAvailable() {
        try {
            // Try to find the method directly
            Class<?> executorsClass = Class.forName("java.util.concurrent.Executors");
            executorsClass.getMethod("newVirtualThreadPerTaskExecutor");
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Submits a task for execution.
     */
    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    /**
     * Submits a runnable task for execution.
     */
    public Future<?> submit(Runnable task) {
        return executor.submit(task);
    }

    /**
     * Submits multiple tasks and returns their futures.
     */
    public <T> List<Future<T>> submitAll(List<Callable<T>> tasks) {
        return tasks.stream()
                .map(this::submit)
                .collect(Collectors.toList());
    }

    /**
     * Executes multiple tasks in parallel and returns all results.
     */
    public <T> List<T> executeAll(List<Callable<T>> tasks) throws InterruptedException {
        List<Future<T>> futures = submitAll(tasks);

        List<T> results = new java.util.ArrayList<>(tasks.size());
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException e) {
                log.error("Task execution failed", e);
                throw new RuntimeException(e.getCause());
            }
        }
        return results;
    }

    /**
     * Executes multiple tasks with a timeout.
     */
    public <T> List<T> executeAll(List<Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException {

        List<Future<T>> futures = submitAll(tasks);

        List<T> results = new java.util.ArrayList<>(tasks.size());
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        for (Future<T> future : futures) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Timeout executing tasks");
            }
            try {
                results.add(future.get(remaining, TimeUnit.NANOSECONDS));
            } catch (ExecutionException e) {
                log.error("Task execution failed", e);
                throw new RuntimeException(e.getCause());
            }
        }
        return results;
    }

    /**
     * Executes tasks in parallel and processes results asynchronously.
     */
    public <T> void executeAndProcess(List<Callable<T>> tasks, Consumer<T> resultProcessor) {
        executor.execute(() -> {
            try {
                List<T> results = executeAll(tasks);
                for (T result : results) {
                    try {
                        resultProcessor.accept(result);
                    } catch (Exception e) {
                        log.error("Result processor failed", e);
                    }
                }
            } catch (Exception e) {
                log.error("Task execution failed", e);
            }
        });
    }

    /**
     * Executes a task asynchronously.
     */
    public <T> CompletableFuture<T> executeAsync(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Executes a task asynchronously with a delay.
     */
    public <T> CompletableFuture<T> executeAsync(Callable<T> task, long delay, TimeUnit unit) {
        CompletableFuture<T> future = new CompletableFuture<>();
        String schedulerName = name + "-delay-scheduler";
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, schedulerName);
                    t.setDaemon(true);
                    return t;
                }
        );

        scheduler.schedule(() -> {
            try {
                executor.execute(() -> {
                    try {
                        future.complete(task.call());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    } finally {
                        scheduler.shutdown();
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
                scheduler.shutdown();
            }
        }, delay, unit);

        return future;
    }

    /**
     * Executes multiple tasks and returns the first successful result.
     */
    public <T> T executeAny(List<Callable<T>> tasks)
            throws InterruptedException, ExecutionException {

        ExecutorService executorService = Executors.newFixedThreadPool(
                Math.min(tasks.size(), poolSize > 0 ? poolSize : Runtime.getRuntime().availableProcessors())
        );

        try {
            List<Future<T>> futures = executorService.invokeAll(tasks);
            for (Future<T> future : futures) {
                try {
                    return future.get();
                } catch (ExecutionException e) {
                    // Try next task
                    log.debug("Task failed, trying next", e);
                }
            }
            throw new ExecutionException("All tasks failed", null);
        } finally {
            executorService.shutdownNow();
        }
    }

    /**
     * Creates a CompletableFuture that completes when all futures complete.
     */
    @SafeVarargs
    public static CompletableFuture<Void> allOf(CompletableFuture<?>... futures) {
        return CompletableFuture.allOf(futures);
    }

    /**
     * Creates a CompletableFuture that completes when any future completes.
     */
    @SafeVarargs
    public static CompletableFuture<Object> anyOf(CompletableFuture<?>... futures) {
        return CompletableFuture.anyOf(futures);
    }

    /**
     * Shuts down the executor gracefully.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shuts down the executor immediately.
     */
    public void shutdownNow() {
        executor.shutdownNow();
    }

    /**
     * Checks if the executor is shut down.
     */
    public boolean isShutdown() {
        return executor.isShutdown();
    }

    /**
     * Gets the underlying executor service.
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    private ExecutorService createVirtualThreadExecutor() {
        try {
            // Use reflection for Java 21+ compatibility
            // The method is in Executors class, not ExecutorService
            Class<?> executorsClass = Class.forName("java.util.concurrent.Executors");
            return (ExecutorService) executorsClass
                    .getMethod("newVirtualThreadPerTaskExecutor")
                    .invoke(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create virtual thread executor", e);
        }
    }

    private ExecutorService createThreadPoolExecutor(int poolSize, long keepAliveTime) {
        int actualPoolSize = poolSize > 0 ? poolSize : Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
                actualPoolSize,
                actualPoolSize * 2,
                keepAliveTime,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, name + "-" + counter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Builder for ParallelExecutor.
     */
    public static class Builder {
        private boolean useVirtualThreads = true;
        private int poolSize = 0; // 0 means use CPU count
        private long keepAliveTime = 60;
        private String name;

        public Builder useVirtualThreads(boolean useVirtualThreads) {
            this.useVirtualThreads = useVirtualThreads;
            return this;
        }

        public Builder poolSize(int poolSize) {
            this.poolSize = poolSize;
            return this;
        }

        public Builder keepAliveTime(long keepAliveTime) {
            this.keepAliveTime = keepAliveTime;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public ParallelExecutor build() {
            return new ParallelExecutor(this);
        }
    }

    /**
     * Statistics about executor performance.
     */
    public record ExecutorStats(
            int activeThreads,
            long completedTasks,
            int queueSize,
            boolean isVirtualThreads
    ) {
        public static ExecutorStats from(ExecutorService executor, boolean isVirtualThreads) {
            if (executor instanceof ThreadPoolExecutor tpe) {
                return new ExecutorStats(
                        tpe.getActiveCount(),
                        tpe.getCompletedTaskCount(),
                        tpe.getQueue().size(),
                        false
                );
            }
            // For virtual threads, we can't get detailed stats
            return new ExecutorStats(0, 0, 0, true);
        }
    }

    /**
     * Gets statistics about this executor.
     */
    public ExecutorStats getStats() {
        return ExecutorStats.from(executor, useVirtualThreads);
    }
}
