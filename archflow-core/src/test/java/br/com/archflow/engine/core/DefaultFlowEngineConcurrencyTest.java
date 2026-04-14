package br.com.archflow.engine.core;

import br.com.archflow.engine.exceptions.FlowEngineException;
import br.com.archflow.engine.exceptions.FlowNotFoundException;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.engine.validation.FlowValidator;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.*;
import br.com.archflow.model.engine.ExecutionMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for concurrent flow execution safety in DefaultFlowEngine.
 * Validates: duplicate prevention, global semaphore, timeout enforcement,
 * and active flow count gauge.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultFlowEngine — concurrency")
class DefaultFlowEngineConcurrencyTest {

    @Mock ExecutionManager executionManager;
    @Mock FlowRepository flowRepository;
    @Mock StateManager stateManager;
    @Mock FlowValidator flowValidator;

    private DefaultFlowEngine engine;

    private Flow createMockFlow(String id) {
        Flow flow = mock(Flow.class, withSettings().lenient());
        when(flow.getId()).thenReturn(id);
        when(flow.getSteps()).thenReturn(List.of());
        FlowMetadata meta = FlowMetadata.builder().name("Test").version("1.0").build();
        when(flow.getMetadata()).thenReturn(meta);
        return flow;
    }

    private FlowResult successResult() {
        return new FlowResult() {
            @Override public ExecutionStatus getStatus() { return ExecutionStatus.COMPLETED; }
            @Override public Optional<Object> getOutput() { return Optional.of("done"); }
            @Override public ExecutionMetrics getMetrics() { return null; }
            @Override public List<ExecutionError> getErrors() { return List.of(); }
        };
    }

    @Nested
    @DisplayName("duplicate prevention")
    class DuplicatePrevention {

        @BeforeEach
        void setUp() {
            engine = new DefaultFlowEngine(
                    executionManager, flowRepository, stateManager, flowValidator,
                    null, null, 10, 30_000);
        }

        @Test
        @DisplayName("rejects a second startFlow with the same flowId while the first is running")
        void rejectsDuplicate() throws Exception {
            Flow flow = createMockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));

            CountDownLatch firstRunning = new CountDownLatch(1);
            CountDownLatch allowFinish = new CountDownLatch(1);

            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                    .thenAnswer(inv -> {
                        firstRunning.countDown();
                        allowFinish.await();
                        return successResult();
                    });

            // Start first
            CompletableFuture<FlowResult> first = engine.startFlow("flow-1", Map.of());
            firstRunning.await(5, TimeUnit.SECONDS);

            // Second attempt should fail
            CompletableFuture<FlowResult> second = engine.startFlow("flow-1", Map.of());

            assertThatThrownBy(second::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(FlowEngineException.class)
                    .hasMessageContaining("already running");

            // Let first finish
            allowFinish.countDown();
            first.get(5, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("allows re-starting a flow after the first completes")
        void allowsRestart() throws Exception {
            Flow flow = createMockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                    .thenReturn(successResult());

            engine.startFlow("flow-1", Map.of()).get(5, TimeUnit.SECONDS);
            engine.startFlow("flow-1", Map.of()).get(5, TimeUnit.SECONDS);

            verify(executionManager, times(2)).executeFlow(eq(flow), any());
        }
    }

    @Nested
    @DisplayName("global concurrency limit")
    class ConcurrencyLimit {

        @Test
        @DisplayName("semaphore limits concurrent flows to maxConcurrentFlows")
        void semaphoreLimitsConcurrency() throws Exception {
            int maxConcurrent = 2;
            engine = new DefaultFlowEngine(
                    executionManager, flowRepository, stateManager, flowValidator,
                    null, null, maxConcurrent, 30_000);

            AtomicInteger peakConcurrency = new AtomicInteger(0);
            AtomicInteger currentConcurrency = new AtomicInteger(0);

            for (int i = 0; i < 5; i++) {
                Flow flow = createMockFlow("flow-" + i);
                when(flowRepository.findById("flow-" + i)).thenReturn(Optional.of(flow));
            }

            when(executionManager.executeFlow(any(Flow.class), any(ExecutionContext.class)))
                    .thenAnswer(inv -> {
                        int c = currentConcurrency.incrementAndGet();
                        peakConcurrency.updateAndGet(old -> Math.max(old, c));
                        Thread.sleep(50);
                        currentConcurrency.decrementAndGet();
                        return successResult();
                    });

            List<CompletableFuture<FlowResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(engine.startFlow("flow-" + i, Map.of()));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);

            assertThat(peakConcurrency.get())
                    .as("peak concurrent flows should not exceed semaphore limit")
                    .isLessThanOrEqualTo(maxConcurrent);
        }

        @Test
        @DisplayName("getActiveFlowCount reflects current concurrency")
        void activeFlowCountGauge() throws Exception {
            engine = new DefaultFlowEngine(
                    executionManager, flowRepository, stateManager, flowValidator,
                    null, null, 10, 30_000);

            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch allowFinish = new CountDownLatch(1);

            Flow flow = createMockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                    .thenAnswer(inv -> {
                        running.countDown();
                        allowFinish.await();
                        return successResult();
                    });

            assertThat(engine.getActiveFlowCount()).isZero();

            CompletableFuture<FlowResult> future = engine.startFlow("flow-1", Map.of());
            running.await(5, TimeUnit.SECONDS);

            assertThat(engine.getActiveFlowCount()).isEqualTo(1);

            allowFinish.countDown();
            future.get(5, TimeUnit.SECONDS);

            // Give the virtual thread a moment to release the permit
            Thread.sleep(50);
            assertThat(engine.getActiveFlowCount()).isZero();
        }

        @Test
        @DisplayName("getAvailablePermits decreases under load")
        void availablePermitsDecrease() throws Exception {
            engine = new DefaultFlowEngine(
                    executionManager, flowRepository, stateManager, flowValidator,
                    null, null, 3, 30_000);

            assertThat(engine.getAvailablePermits()).isEqualTo(3);

            CountDownLatch running = new CountDownLatch(2);
            CountDownLatch allowFinish = new CountDownLatch(1);

            for (int i = 0; i < 2; i++) {
                Flow f = createMockFlow("flow-" + i);
                when(flowRepository.findById("flow-" + i)).thenReturn(Optional.of(f));
            }

            when(executionManager.executeFlow(any(), any()))
                    .thenAnswer(inv -> {
                        running.countDown();
                        allowFinish.await();
                        return successResult();
                    });

            engine.startFlow("flow-0", Map.of());
            engine.startFlow("flow-1", Map.of());
            running.await(5, TimeUnit.SECONDS);

            assertThat(engine.getAvailablePermits()).isEqualTo(1);

            allowFinish.countDown();
            Thread.sleep(100);
            assertThat(engine.getAvailablePermits()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("timeout enforcement")
    class TimeoutEnforcement {

        @Test
        @DisplayName("flow that exceeds timeout is completed exceptionally")
        void timeoutTriggered() {
            long shortTimeout = 200; // 200ms
            engine = new DefaultFlowEngine(
                    executionManager, flowRepository, stateManager, flowValidator,
                    null, null, 10, shortTimeout);

            Flow flow = createMockFlow("flow-slow");
            when(flowRepository.findById("flow-slow")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                    .thenAnswer(inv -> {
                        Thread.sleep(5000); // much longer than timeout
                        return successResult();
                    });

            CompletableFuture<FlowResult> future = engine.startFlow("flow-slow", Map.of());

            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
        }
    }

    @Nested
    @DisplayName("cleanup guarantees")
    class CleanupGuarantees {

        @BeforeEach
        void setUp() {
            engine = new DefaultFlowEngine(
                    executionManager, flowRepository, stateManager, flowValidator,
                    null, null, 10, 30_000);
        }

        @Test
        @DisplayName("activeExecutions is cleaned up after successful flow")
        void cleanedUpAfterSuccess() throws Exception {
            Flow flow = createMockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any())).thenReturn(successResult());

            engine.startFlow("flow-1", Map.of()).get(5, TimeUnit.SECONDS);

            assertThat(engine.getActiveFlows()).doesNotContain("flow-1");
        }

        @Test
        @DisplayName("activeExecutions is cleaned up after failed flow")
        void cleanedUpAfterFailure() {
            Flow flow = createMockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any()))
                    .thenThrow(new RuntimeException("boom"));

            CompletableFuture<FlowResult> future = engine.startFlow("flow-1", Map.of());

            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(engine.getActiveFlows()).doesNotContain("flow-1");
        }

        @Test
        @DisplayName("semaphore permit is released even when flow fails")
        void permitReleasedOnFailure() throws Exception {
            engine = new DefaultFlowEngine(
                    executionManager, flowRepository, stateManager, flowValidator,
                    null, null, 1, 30_000);

            Flow flow = createMockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any()))
                    .thenThrow(new RuntimeException("boom"));

            CompletableFuture<FlowResult> future = engine.startFlow("flow-1", Map.of());
            try { future.get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}

            Thread.sleep(50);
            assertThat(engine.getAvailablePermits())
                    .as("permit should be released after failure")
                    .isEqualTo(1);

            // Should be able to start another flow
            Flow flow2 = createMockFlow("flow-2");
            when(flowRepository.findById("flow-2")).thenReturn(Optional.of(flow2));
            when(executionManager.executeFlow(eq(flow2), any())).thenReturn(successResult());

            engine.startFlow("flow-2", Map.of()).get(5, TimeUnit.SECONDS);
        }
    }
}
