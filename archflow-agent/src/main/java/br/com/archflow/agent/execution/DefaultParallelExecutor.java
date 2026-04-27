package br.com.archflow.agent.execution;

import br.com.archflow.engine.execution.ParallelExecutor;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executor para execução paralela de steps
 */
public class DefaultParallelExecutor implements ParallelExecutor {
    private static final Logger logger = LoggerFactory.getLogger(DefaultParallelExecutor.class.getName());

    /** Default per-step ceiling — chosen so a hung provider does not jam the executor pool indefinitely. */
    private static final long DEFAULT_STEP_TIMEOUT_MS = 5 * 60_000L;
    /** Default aggregate ceiling — covers the longest legitimate parallel batch we expect. */
    private static final long DEFAULT_TOTAL_TIMEOUT_MS = 10 * 60_000L;

    private final ExecutorService executorService;
    private final int maxConcurrent;
    private final Semaphore semaphore;
    private final long stepTimeoutMs;
    private final long totalTimeoutMs;

    public DefaultParallelExecutor(ExecutorService executorService, int maxConcurrent) {
        this(executorService, maxConcurrent, DEFAULT_STEP_TIMEOUT_MS, DEFAULT_TOTAL_TIMEOUT_MS);
    }

    public DefaultParallelExecutor(ExecutorService executorService, int maxConcurrent,
                                   long stepTimeoutMs, long totalTimeoutMs) {
        this.executorService = executorService;
        this.maxConcurrent = maxConcurrent;
        this.semaphore = new Semaphore(maxConcurrent);
        this.stepTimeoutMs = stepTimeoutMs;
        this.totalTimeoutMs = totalTimeoutMs;
    }

    @Override
    public List<StepResult> executeParallel(List<FlowStep> steps, ExecutionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("ExecutionContext cannot be null");
        }

        List<CompletableFuture<StepResult>> futures = steps.stream()
            .map(step -> executeStepAsync(step, context))
            .toList();

        CompletableFuture<Void> allOf = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );

        try {
            // Aggregate ceiling — without a timeout the calling thread can
            // hang forever if any step's underlying provider stalls.
            return allOf.thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .toList()
            ).get(totalTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Cancel any still-running step so its semaphore permit and
            // worker thread are released promptly instead of leaking.
            futures.forEach(f -> f.cancel(true));
            logger.error("Parallel execution exceeded {}ms aggregate timeout", totalTimeoutMs);
            throw new RuntimeException("Parallel execution timed out after " + totalTimeoutMs + "ms", e);
        } catch (InterruptedException e) {
            futures.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel execution interrupted", e);
        } catch (Exception e) {
            logger.error("Erro na execução paralela: {}", e.getMessage());
            throw new RuntimeException("Erro na execução paralela", e);
        }
    }

    private CompletableFuture<StepResult> executeStepAsync(FlowStep step, ExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            // Track whether we actually acquired a permit. If acquire()
            // throws (e.g. thread interrupted while blocked), we must NOT
            // call release() or we inflate the permit count past
            // maxConcurrent and break the concurrency cap permanently.
            boolean acquired = false;
            try {
                semaphore.acquire();
                acquired = true;
                logger.info("Iniciando execução paralela do step: {}", step.getId());
                // Per-step ceiling — without it a misbehaving step.execute()
                // future can pin a worker thread + permit indefinitely.
                return step.execute(context).get(stepTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new CompletionException("Step execution timed out after "
                        + stepTimeoutMs + "ms: " + step.getId(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException("Step execution interrupted: " + step.getId(), e);
            } catch (Exception e) {
                throw new CompletionException("Error executing step: " + step.getId(), e);
            } finally {
                if (acquired) {
                    semaphore.release();
                }
            }
        }, executorService);
    }

    @Override
    public void awaitCompletion() {
        // Nada a fazer, pois já aguardamos no executeParallel
    }
}