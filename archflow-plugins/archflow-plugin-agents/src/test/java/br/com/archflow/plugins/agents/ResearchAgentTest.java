package br.com.archflow.plugins.agents;

import br.com.archflow.model.ai.domain.*;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ResearchAgent")
class ResearchAgentTest {

    private ResearchAgent agent;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        agent = new ResearchAgent();
        context = mock(ExecutionContext.class);
        agent.initialize(Map.of());
    }

    @Nested
    @DisplayName("metadata")
    class MetadataTest {

        @Test
        @DisplayName("should return correct metadata")
        void shouldReturnMetadata() {
            ComponentMetadata meta = agent.getMetadata();

            assertThat(meta.id()).isEqualTo("research-agent");
            assertThat(meta.type()).isEqualTo(ComponentType.AGENT);
            assertThat(meta.version()).isEqualTo("1.0.0");
            assertThat(meta.capabilities()).contains("research", "analysis", "planning");
        }
    }

    @Nested
    @DisplayName("executeTask")
    class ExecuteTaskTest {

        @Test
        @DisplayName("should execute research task")
        void shouldExecuteResearch() {
            var task = Task.of("research", Map.of("topic", "Java frameworks"));

            Result result = agent.executeTask(task, context);

            assertThat(result.success()).isTrue();
            assertThat(result.output()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            var output = (Map<String, Object>) result.output();
            assertThat(output.get("task_type")).isEqualTo("research");
            assertThat(output.get("status")).isEqualTo("completed");
            assertThat((List<?>) output.get("steps")).hasSize(5);
        }

        @Test
        @DisplayName("should execute summarize task")
        void shouldExecuteSummarize() {
            var task = Task.of("summarize", Map.of("content", "long text"));

            Result result = agent.executeTask(task, context);

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            var output = (Map<String, Object>) result.output();
            assertThat((List<?>) output.get("steps")).hasSize(3);
        }

        @Test
        @DisplayName("should execute compare task")
        void shouldExecuteCompare() {
            var task = Task.of("compare", Map.of("items", List.of("A", "B")));

            Result result = agent.executeTask(task, context);

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            var output = (Map<String, Object>) result.output();
            assertThat((List<?>) output.get("steps")).hasSize(4);
        }

        @Test
        @DisplayName("should execute analyze task")
        void shouldExecuteAnalyze() {
            var task = Task.of("analyze", Map.of("data", "input"));

            Result result = agent.executeTask(task, context);

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("should fail for unsupported task type")
        void shouldFailUnsupported() {
            var task = Task.of("translate", Map.of());

            Result result = agent.executeTask(task, context);

            assertThat(result.success()).isFalse();
            assertThat(result.messages()).anyMatch(m -> m.contains("Unsupported task type"));
        }

        @Test
        @DisplayName("should fail for null task")
        void shouldFailForNull() {
            Result result = agent.executeTask(null, context);

            assertThat(result.success()).isFalse();
            assertThat(result.messages()).anyMatch(m -> m.contains("null"));
        }
    }

    @Nested
    @DisplayName("makeDecision")
    class MakeDecisionTest {

        @Test
        @DisplayName("should decide to gather data when no data available")
        void shouldGatherData() {
            when(context.get("research_data")).thenReturn(java.util.Optional.empty());

            Decision decision = agent.makeDecision(context);

            assertThat(decision.action()).isEqualTo("gather_data");
            assertThat(decision.confidence()).isGreaterThan(0.5);
            assertThat(decision.reasoning()).contains("No research data");
        }

        @Test
        @DisplayName("should decide to analyze when data available")
        void shouldAnalyze() {
            when(context.get("research_data")).thenReturn(java.util.Optional.of("some data"));

            Decision decision = agent.makeDecision(context);

            assertThat(decision.action()).isEqualTo("analyze");
            assertThat(decision.reasoning()).contains("available");
        }

        @Test
        @DisplayName("should provide alternatives")
        void shouldProvideAlternatives() {
            Decision decision = agent.makeDecision(context);

            assertThat(decision.alternatives()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("planActions")
    class PlanActionsTest {

        @Test
        @DisplayName("should plan 5 actions for a goal")
        void shouldPlan5Actions() {
            var goal = Goal.of("Research Java frameworks", "Find top 3 frameworks", "Compare features");

            List<Action> actions = agent.planActions(goal, context);

            assertThat(actions).hasSize(5);
            assertThat(actions.get(0).type()).isEqualTo("define_scope");
            assertThat(actions.get(1).type()).isEqualTo("gather_info");
            assertThat(actions.get(2).type()).isEqualTo("analyze");
            assertThat(actions.get(3).type()).isEqualTo("synthesize");
            assertThat(actions.get(4).type()).isEqualTo("validate");
        }

        @Test
        @DisplayName("should include goal description in scope action")
        void shouldIncludeGoalInScope() {
            var goal = Goal.of("Study AI trends");

            List<Action> actions = agent.planActions(goal, context);

            assertThat(actions.get(0).parameters()).containsEntry("goal", "Study AI trends");
        }

        @Test
        @DisplayName("should handle null goal")
        void shouldHandleNullGoal() {
            List<Action> actions = agent.planActions(null, context);

            assertThat(actions).hasSize(1);
            assertThat(actions.get(0).type()).isEqualTo("error");
        }
    }

    @Nested
    @DisplayName("execute via operation")
    class ExecuteOperationTest {

        @Test
        @DisplayName("should execute via executeTask operation")
        void shouldExecuteViaOperation() throws Exception {
            var task = Task.of("research", Map.of());

            var result = agent.execute("executeTask", task, context);

            assertThat(result).isInstanceOf(Result.class);
            assertThat(((Result) result).success()).isTrue();
        }

        @Test
        @DisplayName("should reject unsupported operation")
        void shouldRejectUnsupported() {
            assertThatThrownBy(() -> agent.execute("fly", "nowhere", context))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should throw when not initialized")
        void shouldThrowNotInitialized() {
            var uninit = new ResearchAgent();

            assertThatThrownBy(() -> uninit.execute("executeTask", null, context))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class LifecycleTest {

        @Test
        @DisplayName("should shutdown cleanly")
        void shouldShutdown() {
            agent.shutdown();

            assertThatThrownBy(() -> agent.execute("executeTask", null, context))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
