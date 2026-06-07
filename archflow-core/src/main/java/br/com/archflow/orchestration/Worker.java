package br.com.archflow.orchestration;

/**
 * Processes one fan-out item. Implementations capture whatever context they need
 * (ExecutionContext, the resolved LLM, a tool) in their closure, keeping the
 * orchestration core free of any execution-engine or provider coupling.
 */
@FunctionalInterface
public interface Worker<I, O> {
    Result<O> apply(I item);
}
