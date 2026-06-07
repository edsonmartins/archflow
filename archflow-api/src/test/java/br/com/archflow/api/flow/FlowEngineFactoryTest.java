package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FlowEngineFactoryTest {

    private static FlowStep okStep(String id) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                return CompletableFuture.completedFuture(SimpleStepResult.ok(id, "out", 1));
            }
        };
    }

    private static Flow flow(String id, List<FlowStep> steps) {
        return new SimpleFlow(id, new FlowMetadata("T", "", "1.0.0", null, null, List.of()), steps);
    }

    private static ExecutionContext ctx(String id) {
        return new DefaultExecutionContext(null, "u", id,
                MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    @Test
    void engineExecutesATrivialFlowEndToEnd() throws Exception {
        FlowEngine engine = FlowEngineFactory.create(new InMemoryFlowRepository());

        FlowResult result = engine.execute(flow("t1", List.of(okStep("s1"))), ctx("t1"))
                .get(15, TimeUnit.SECONDS);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void streamsFlowAndStepLifecycleToTheEventRegistry() throws Exception {
        EventStreamRegistry registry = new EventStreamRegistry();
        List<ArchflowEvent> events = new CopyOnWriteArrayList<>();
        registry.addGlobalListener(events::add);

        FlowEngine engine = FlowEngineFactory.create(
                new InMemoryFlowRepository(), registry, new RunningFlowsRegistry());

        engine.execute(flow("exec-x", List.of(okStep("s1"))), ctx("exec-x")).get(15, TimeUnit.SECONDS);

        List<String> types = events.stream().map(e -> e.getType().name()).toList();
        assertThat(types).contains("FLOW_STARTED", "STEP_STARTED", "STEP_COMPLETED", "FLOW_COMPLETED");
    }
}
