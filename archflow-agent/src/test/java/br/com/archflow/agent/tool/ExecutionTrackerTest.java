package br.com.archflow.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExecutionTracker")
class ExecutionTrackerTest {

    private ExecutionTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ExecutionTracker();
    }

    @Test
    void startRootAndComplete() {
        ExecutionId id = tracker.startRoot(ExecutionId.ExecutionType.FLOW);
        assertThat(tracker.getActiveExecutions()).contains(id.getId());
        assertThat(tracker.getRecord(id.getId()).getStatus())
                .isEqualTo(ExecutionTracker.ExecutionStatus.RUNNING);

        tracker.complete(id.getId(), ToolResult.success("ok"));

        assertThat(tracker.getRecord(id.getId()).getStatus())
                .isEqualTo(ExecutionTracker.ExecutionStatus.COMPLETED);
        assertThat(tracker.getActiveExecutions()).doesNotContain(id.getId());
    }

    @Test
    void startChildTracksHierarchy() {
        ExecutionId root = tracker.startRoot(ExecutionId.ExecutionType.FLOW);
        ExecutionId child = tracker.startChild(root.getId(), ExecutionId.ExecutionType.TOOL);

        assertThat(tracker.getChildren(root.getId())).contains(child.getId());

        var hierarchy = tracker.getHierarchy(root.getId());
        assertThat(hierarchy).hasSize(2);
    }

    @Test
    void failSetsErrorStatus() {
        ExecutionId id = tracker.startRoot(ExecutionId.ExecutionType.TOOL);
        tracker.fail(id.getId(), new RuntimeException("boom"));

        var record = tracker.getRecord(id.getId());
        assertThat(record.getStatus()).isEqualTo(ExecutionTracker.ExecutionStatus.FAILED);
        assertThat(record.getError()).isNotNull();
    }

    @Test
    void removeDeletesRecord() {
        ExecutionId id = tracker.startRoot(ExecutionId.ExecutionType.TOOL);
        tracker.complete(id.getId(), ToolResult.success("done"));
        tracker.remove(id.getId());
        assertThat(tracker.getRecord(id.getId())).isNull();
    }

    @Test
    void cleanupRemovesOldCompleted() {
        ExecutionId id = tracker.startRoot(ExecutionId.ExecutionType.TOOL);
        tracker.complete(id.getId(), ToolResult.success("done"));
        tracker.cleanup(Instant.now().plusSeconds(3600));
        assertThat(tracker.getRecord(id.getId())).isNull();
    }

    @Test
    void statsAreAccurate() {
        ExecutionId r1 = tracker.startRoot(ExecutionId.ExecutionType.FLOW);
        ExecutionId r2 = tracker.startRoot(ExecutionId.ExecutionType.TOOL);
        tracker.complete(r1.getId(), ToolResult.success("ok"));
        tracker.fail(r2.getId(), new RuntimeException("err"));

        var stats = tracker.getStats();
        assertThat(stats.totalExecutions()).isEqualTo(2);
        assertThat(stats.completed()).isEqualTo(1);
        assertThat(stats.failed()).isEqualTo(1);
        assertThat(stats.running()).isZero();
    }

    @Test
    void durationIsPositive() throws InterruptedException {
        ExecutionId id = tracker.startRoot(ExecutionId.ExecutionType.TOOL);
        Thread.sleep(10);
        tracker.complete(id.getId(), ToolResult.success("ok"));
        assertThat(tracker.getRecord(id.getId()).getDurationMillis()).isGreaterThan(0);
    }
}
