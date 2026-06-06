package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FlowEngineFactoryTest {

    @Test
    void engineExecutesATrivialFlowEndToEnd() throws Exception {
        FlowEngine engine = FlowEngineFactory.create(new InMemoryFlowRepository());

        FlowStep step = new FlowStep() {
            @Override public String getId() { return "s1"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                return CompletableFuture.completedFuture(SimpleStepResult.ok("s1", "out", 1));
            }
        };

        Flow flow = new SimpleFlow("t1",
                new FlowMetadata("T", "", "1.0.0", null, null, List.of()), List.of(step));
        ExecutionContext ctx = new DefaultExecutionContext(null, "u", "t1",
                MessageWindowChatMemory.builder().maxMessages(5).build());

        FlowResult result = engine.execute(flow, ctx).get(15, TimeUnit.SECONDS);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }
}
