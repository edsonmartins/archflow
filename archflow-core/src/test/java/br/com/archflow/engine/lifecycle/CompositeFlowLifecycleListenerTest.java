package br.com.archflow.engine.lifecycle;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CompositeFlowLifecycleListener")
class CompositeFlowLifecycleListenerTest {

    @Test
    @DisplayName("fans out callbacks to every delegate in order")
    void fansOut() {
        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();

        FlowLifecycleListener a = new FlowLifecycleListener() {
            @Override public void onFlowStarted(Flow f, ExecutionContext c, int n) { hitsA.incrementAndGet(); }
        };
        FlowLifecycleListener b = new FlowLifecycleListener() {
            @Override public void onFlowStarted(Flow f, ExecutionContext c, int n) { hitsB.incrementAndGet(); }
        };

        CompositeFlowLifecycleListener composite = new CompositeFlowLifecycleListener();
        composite.add(a); composite.add(b);
        composite.onFlowStarted(null, null, 3);

        assertThat(hitsA.get()).isEqualTo(1);
        assertThat(hitsB.get()).isEqualTo(1);
        assertThat(composite.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("isolates failures: one listener throwing does not stop the next")
    void isolatesFailures() {
        AtomicInteger survivor = new AtomicInteger();
        FlowLifecycleListener boom = new FlowLifecycleListener() {
            @Override public void onFlowCompleted(Flow f, ExecutionContext c, FlowResult r, long d) {
                throw new RuntimeException("boom");
            }
        };
        FlowLifecycleListener ok = new FlowLifecycleListener() {
            @Override public void onFlowCompleted(Flow f, ExecutionContext c, FlowResult r, long d) {
                survivor.incrementAndGet();
            }
        };

        CompositeFlowLifecycleListener composite = new CompositeFlowLifecycleListener();
        composite.add(boom); composite.add(ok);
        composite.onFlowCompleted(null, null, Mockito.mock(FlowResult.class), 100);

        assertThat(survivor.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("remove drops a listener from the chain")
    void removeDrops() {
        FlowLifecycleListener a = new FlowLifecycleListener() {};
        CompositeFlowLifecycleListener composite = new CompositeFlowLifecycleListener();
        composite.add(a);
        assertThat(composite.size()).isEqualTo(1);
        composite.remove(a);
        assertThat(composite.size()).isEqualTo(0);
    }
}
