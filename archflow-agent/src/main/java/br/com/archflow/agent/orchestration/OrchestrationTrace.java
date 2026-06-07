package br.com.archflow.agent.orchestration;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link OrchestrationListener} that accumulates a flat, serializable timeline of
 * a dynamic workflow run — suitable for returning in an API response or feeding a
 * live stream (design-0004 step 3).
 */
public final class OrchestrationTrace implements OrchestrationListener {

    public record Entry(String type, int round, String detail, Boolean confirmed) {}

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void onPlanned(int round, List<?> items) {
        entries.add(new Entry("planned", round, items.size() + " subtask(s): " + items, null));
    }

    @Override
    public void onVerified(int round, Object finding, boolean confirmed) {
        entries.add(new Entry("verified", round, String.valueOf(finding), confirmed));
    }

    @Override
    public void onRoundCompleted(int round, boolean producedNew, int confirmedSoFar) {
        entries.add(new Entry("round", round,
                "producedNew=" + producedNew + ", confirmed=" + confirmedSoFar, null));
    }

    @Override
    public void onConverged(int rounds, int confirmedTotal) {
        entries.add(new Entry("converged", rounds, "confirmed=" + confirmedTotal, null));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }
}
