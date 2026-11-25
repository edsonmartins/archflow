package br.com.archflow.api.admin.observability;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.admin.observability.impl.ObservabilityService;
import br.com.archflow.api.flow.FlowEngineFactory;
import br.com.archflow.api.flow.InMemoryStateManager;
import br.com.archflow.api.flow.SimpleFlow;
import br.com.archflow.api.flow.SimpleStepResult;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RCHFLW-72: o Parar do Running (Admin › Observabilidade) com o motor de verdade.
 *
 * <p>Antes o serviço só tirava a entrada do {@link RunningFlowsRegistry}: a API respondia 200, a
 * linha sumia, e o fluxo seguia rodando — agora invisível, porque {@code stepStarted} não recria a
 * linha — até terminar COMPLETED com todos os efeitos.</p>
 */
@DisplayName("Parar no Running cancela no motor")
class PararNoRunningCancelaNoMotorTest {

    private static final String FLOW_ID = "exec-running-72";
    private static final String TENANT = "tenant-a";

    private RunningFlowsRegistry running;
    private StateManager stateManager;
    private FlowEngine engine;
    private ObservabilityService service;
    private CountDownLatch passoComecou;
    private CountDownLatch liberaPasso;
    private AtomicInteger seguinteRodou;

    @BeforeEach
    void monta() {
        running = new RunningFlowsRegistry();
        stateManager = new InMemoryStateManager();
        engine = FlowEngineFactory.create(new InMemoryFlowRepository(), new EventStreamRegistry(), running,
                stateManager);
        service = new ObservabilityService(null, null, null, null, running, engine);
        passoComecou = new CountDownLatch(1);
        liberaPasso = new CountDownLatch(1);
        seguinteRodou = new AtomicInteger();
    }

    @AfterEach
    void libera() {
        liberaPasso.countDown();
    }

    @Test
    @DisplayName("cancelar durante um passo longo: o seguinte não roda, termina CANCELLED e a linha sai ao parar")
    void cancelaDeVerdade() throws Exception {
        CompletableFuture<FlowResult> execucao = engine.execute(fluxo(), contexto());
        assertThat(passoComecou.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(running.find(FLOW_ID)).isPresent();

        service.cancelFlow(TENANT, FLOW_ID);
        // O passo em curso ainda não terminou: a linha continua, porque o fluxo ainda não parou.
        assertThat(running.find(FLOW_ID)).isPresent();
        liberaPasso.countDown();
        FlowResult result = execucao.get(15, TimeUnit.SECONDS);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(result.getOutput()).isEmpty();
        assertThat(seguinteRodou).hasValue(0);
        assertThat(stateManager.loadState(FLOW_ID).getStatus()).isEqualTo(FlowStatus.STOPPED);
        assertThat(running.find(FLOW_ID)).isEmpty();
    }

    @Test
    @DisplayName("execução que já terminou → não está rodando (404 no controller)")
    void terminadaNaoEstaRodando() throws Exception {
        liberaPasso.countDown();
        engine.execute(fluxo(), contexto()).get(15, TimeUnit.SECONDS);

        assertThatThrownBy(() -> service.cancelFlow(TENANT, FLOW_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(FLOW_ID);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private ExecutionContext contexto() {
        return new DefaultExecutionContext(TENANT, "u-1", FLOW_ID,
                MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    private Flow fluxo() {
        FlowStep lento = new FlowStep() {
            @Override public String getId() { return "lento"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(edge("lento", "seguinte")); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                passoComecou.countDown();
                try {
                    liberaPasso.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(SimpleStepResult.ok("lento", "resposta", 1));
            }
        };
        FlowStep seguinte = new FlowStep() {
            @Override public String getId() { return "seguinte"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                seguinteRodou.incrementAndGet();
                return CompletableFuture.completedFuture(SimpleStepResult.ok("seguinte", "efeito", 1));
            }
        };
        return new SimpleFlow(FLOW_ID, new FlowMetadata("Lento", "", "1.0.0", null, null, List.of()),
                List.of(lento, seguinte));
    }

    private static StepConnection edge(String from, String to) {
        return new StepConnection() {
            @Override public String getSourceId() { return from; }
            @Override public String getTargetId() { return to; }
            @Override public Optional<String> getCondition() { return Optional.empty(); }
            @Override public boolean isErrorPath() { return false; }
        };
    }
}
