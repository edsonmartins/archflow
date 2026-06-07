package br.com.archflow.agent.orchestration;

import java.util.List;

/** Outcome of a {@link DynamicSupervisor} run: the confirmed findings and how many rounds ran. */
public record SupervisorResult(List<Object> confirmed, int rounds) {

    public SupervisorResult {
        confirmed = List.copyOf(confirmed);
    }

    public int confirmedCount() {
        return confirmed.size();
    }
}
