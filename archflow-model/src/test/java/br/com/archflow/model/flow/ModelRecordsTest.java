package br.com.archflow.model.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Model records and enums")
class ModelRecordsTest {

    @Nested
    @DisplayName("StepType")
    class StepTypeTests {
        @Test
        void allValuesPresent() {
            assertThat(StepType.values()).containsExactly(
                    StepType.ASSISTANT, StepType.AGENT, StepType.TOOL, StepType.CHAIN, StepType.CUSTOM);
        }

        @Test
        void valueOfRoundTrip() {
            for (StepType t : StepType.values()) {
                assertThat(StepType.valueOf(t.name())).isEqualTo(t);
            }
        }
    }

    @Nested
    @DisplayName("PathStatus")
    class PathStatusTests {
        @Test
        void allValuesPresent() {
            assertThat(PathStatus.values()).containsExactly(
                    PathStatus.STARTED, PathStatus.RUNNING, PathStatus.PAUSED,
                    PathStatus.COMPLETED, PathStatus.FAILED, PathStatus.MERGED);
        }

        @Test
        void isActiveForStartedAndRunning() {
            assertThat(PathStatus.STARTED.isActive()).isTrue();
            assertThat(PathStatus.RUNNING.isActive()).isTrue();
            assertThat(PathStatus.PAUSED.isActive()).isFalse();
            assertThat(PathStatus.COMPLETED.isActive()).isFalse();
            assertThat(PathStatus.FAILED.isActive()).isFalse();
            assertThat(PathStatus.MERGED.isActive()).isFalse();
        }

        @Test
        void isTerminalForCompletedFailedMerged() {
            assertThat(PathStatus.COMPLETED.isTerminal()).isTrue();
            assertThat(PathStatus.FAILED.isTerminal()).isTrue();
            assertThat(PathStatus.MERGED.isTerminal()).isTrue();
            assertThat(PathStatus.STARTED.isTerminal()).isFalse();
            assertThat(PathStatus.RUNNING.isTerminal()).isFalse();
            assertThat(PathStatus.PAUSED.isTerminal()).isFalse();
        }
    }

    @Nested
    @DisplayName("ExecutionPath")
    class ExecutionPathTests {
        @Test
        void builderCreatesFields() {
            ExecutionPath path = ExecutionPath.builder()
                    .pathId("p1")
                    .status(PathStatus.RUNNING)
                    .completedSteps(List.of("s1", "s2"))
                    .parallelBranches(List.of())
                    .build();

            assertThat(path.getPathId()).isEqualTo("p1");
            assertThat(path.getStatus()).isEqualTo(PathStatus.RUNNING);
            assertThat(path.getCompletedSteps()).containsExactly("s1", "s2");
            assertThat(path.getParallelBranches()).isEmpty();
        }

        @Test
        void nestedParallelBranches() {
            ExecutionPath branch1 = ExecutionPath.builder()
                    .pathId("b1")
                    .status(PathStatus.COMPLETED)
                    .completedSteps(List.of("s3"))
                    .build();
            ExecutionPath branch2 = ExecutionPath.builder()
                    .pathId("b2")
                    .status(PathStatus.FAILED)
                    .completedSteps(List.of())
                    .build();
            ExecutionPath main = ExecutionPath.builder()
                    .pathId("main")
                    .status(PathStatus.RUNNING)
                    .completedSteps(List.of("s1"))
                    .parallelBranches(List.of(branch1, branch2))
                    .build();

            assertThat(main.getParallelBranches()).hasSize(2);
            assertThat(main.getParallelBranches().get(0).getStatus()).isEqualTo(PathStatus.COMPLETED);
            assertThat(main.getParallelBranches().get(1).getStatus()).isEqualTo(PathStatus.FAILED);
        }

        @Test
        void settersMutate() {
            ExecutionPath path = ExecutionPath.builder().pathId("p").status(PathStatus.STARTED).build();
            path.setStatus(PathStatus.COMPLETED);
            assertThat(path.getStatus()).isEqualTo(PathStatus.COMPLETED);
        }
    }

    @Nested
    @DisplayName("AuditLog")
    class AuditLogTests {
        @Test
        void builderCreatesFields() {
            Instant now = Instant.now();
            AuditLog log = AuditLog.builder()
                    .flowId("f1")
                    .timestamp(now)
                    .stepId("s1")
                    .build();

            assertThat(log.getFlowId()).isEqualTo("f1");
            assertThat(log.getTimestamp()).isEqualTo(now);
            assertThat(log.getStepId()).isEqualTo("s1");
            assertThat(log.getState()).isNull();
            assertThat(log.getStepResult()).isNull();
        }

        @Test
        void withFlowState() {
            FlowState state = new FlowState();
            state.setFlowId("f1");
            state.setTenantId("t1");

            AuditLog log = AuditLog.builder()
                    .flowId("f1")
                    .timestamp(Instant.now())
                    .state(state)
                    .build();

            assertThat(log.getState()).isNotNull();
            assertThat(log.getState().getFlowId()).isEqualTo("f1");
        }
    }
}
