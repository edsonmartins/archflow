package br.com.archflow.orchestration;

import java.util.List;

/** The subtasks produced by {@link Orchestrator#plan}, plus the model's rationale. */
public record Plan<T>(List<T> items, String rationale) {

    public Plan {
        items = List.copyOf(items);
    }

    public int size() {
        return items.size();
    }
}
