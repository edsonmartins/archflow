package br.com.archflow.orchestration;

import java.util.List;

/**
 * Model-driven decomposition of a {@link Goal} into subtasks. The LLM-backed
 * implementation (which may route a worker agent via the component catalog)
 * lives in the wiring layer; the orchestration core only sees this function.
 */
@FunctionalInterface
public interface Planner<T> {
    List<T> decompose(Goal goal, PlanSpec spec);
}
