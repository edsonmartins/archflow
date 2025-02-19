package br.com.archflow.agent.execution;

import br.com.archflow.engine.execution.ParallelExecutor;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * Executor para execução paralela de steps
 */
public class DefaultParallelExecutor implements ParallelExecutor {
    private static final Logger logger = LoggerFactory.getLogger(DefaultParallelExecutor.class.getName());

    private final ExecutorService executorService;
    private final int maxConcurrent;
    private final Semaphore semaphore;

    public DefaultParallelExecutor(ExecutorService executorService, int maxConcurrent) {
        this.executorService = executorService;
        this.maxConcurrent = maxConcurrent;
        this.semaphore = new Semaphore(maxConcurrent);
    }

    @Override
    public List<StepResult> executeParallel(List<FlowStep> steps) {
        try {
            // Cria tasks para cada step
            List<CompletableFuture<StepResult>> futures = steps.stream()
                .map(this::executeStepAsync)
                .toList();

            // Aguarda conclusão de todos
            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );

            // Retorna resultados
            return allOf.thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .toList()
            ).get();

        } catch (Exception e) {
            logger.error("Erro na execução paralela: " + e.getMessage());
            throw new RuntimeException("Erro na execução paralela", e);
        }
    }

    private CompletableFuture<StepResult> executeStepAsync(FlowStep step) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                semaphore.acquire();
                logger.info("Iniciando execução paralela do step: " + step.getId());
                return step.execute(null).get(); // TODO: Passar contexto apropriado
            } catch (Exception e) {
                throw new CompletionException(e);
            } finally {
                semaphore.release();
            }
        }, executorService);
    }

    @Override
    public void awaitCompletion() {
        // Nada a fazer, pois já aguardamos no executeParallel
    }
}