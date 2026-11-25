package br.com.archflow.api.agui;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.flow.FlowEngineFactory;
import br.com.archflow.api.flow.InMemoryStateManager;
import br.com.archflow.api.flow.SimpleFlow;
import br.com.archflow.api.flow.SimpleStepResult;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.model.tenant.DefaultTenant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * RCHFLW-54: o Parar do editor fecha a conexão do AG-UI no meio de um passo longo.
 *
 * <p>Antes o controller só cancelava em {@code onError}/{@code onTimeout} e engolia a falha de
 * escrita: durante um passo de LLM nada é escrito, então o servidor não sabia do Parar, o passo ia
 * até o fim, o seguinte rodava e a execução terminava COMPLETED. Aqui o motor é o de verdade
 * ({@link FlowEngineFactory}); só a conexão é trocada por uma que o teste derruba.</p>
 */
@DisplayName("Parar no AG-UI cancela a execução no servidor")
class PararNoAgUiCancelaAExecucaoTest {

    private static final String WORKFLOW_ID = "wf-lento";

    /** Um emitter que guarda os callbacks do container e pode simular o cliente indo embora. */
    static final class Conexao extends SseEmitter {
        final List<Runnable> aoCompletar = new CopyOnWriteArrayList<>();
        final List<String> enviados = new CopyOnWriteArrayList<>();
        volatile boolean caiu;

        Conexao() {
            super(0L);
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
            aoCompletar.add(callback);
            super.onCompletion(callback);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            if (caiu) {
                throw new IOException("Broken pipe");
            }
            StringBuilder sb = new StringBuilder();
            for (DataWithMediaType parte : builder.build()) {
                sb.append(parte.getData());
            }
            enviados.add(sb.toString());
        }

        /** O container avisa que a requisição assíncrona acabou — o cliente abortou o fetch. */
        void clienteFechou() {
            caiu = true;
            aoCompletar.forEach(Runnable::run);
        }
    }

    private InMemoryWorkflowRuntimeStore store;
    private StateManager stateManager;
    private FlowEngine engine;
    private AgUiController controller;
    private Conexao conexao;
    private CountDownLatch passoComecou;
    private CountDownLatch liberaPasso;
    private AtomicInteger seguinteRodou;

    @BeforeEach
    void monta() {
        var flowRepository = new InMemoryFlowRepository();
        stateManager = new InMemoryStateManager();
        engine = spy(FlowEngineFactory.create(flowRepository, new EventStreamRegistry(),
                new RunningFlowsRegistry(), stateManager));
        store = new InMemoryWorkflowRuntimeStore();
        store.putWorkflow(DefaultTenant.ID, WORKFLOW_ID,
                Map.of("id", WORKFLOW_ID, "metadata", Map.of("name", "Lento"), "steps", List.of()));
        passoComecou = new CountDownLatch(1);
        liberaPasso = new CountDownLatch(1);
        seguinteRodou = new AtomicInteger();
        conexao = new Conexao();
        controller = new AgUiController(store,
                json -> new SimpleFlow(String.valueOf(json.get("id")),
                        new FlowMetadata("Lento", "", "1.0.0", null, null, List.of()),
                        List.of(lento(), seguinte())),
                engine, new EventStreamRegistry(), new DefaultAgUiEventMapper(), stateManager,
                flowRepository, new ObjectMapper()) {
            @Override
            SseEmitter novoEmissor() {
                return conexao;
            }
        };
    }

    @AfterEach
    void fecha() {
        liberaPasso.countDown();
        controller.close();
    }

    @Test
    @DisplayName("cliente fecha a conexão durante o passo longo → CANCELLED e o passo seguinte não roda")
    void clienteFechaDuranteOPasso() throws Exception {
        controller.run(WORKFLOW_ID, null);
        assertThat(passoComecou.await(10, TimeUnit.SECONDS)).isTrue();
        String execucao = execucaoAtual();

        conexao.clienteFechou();
        liberaPasso.countDown();

        assertThat(statusFinal(execucao)).isEqualTo("CANCELLED");
        assertThat(seguinteRodou).hasValue(0);
        assertThat(stateManager.loadState(execucao).getStatus()).isEqualTo(FlowStatus.STOPPED);
        verify(engine).cancel(execucao);
    }

    @Test
    @DisplayName("batimento que falha durante o passo longo revela a desconexão e cancela")
    void batimentoQueFalhaCancela() throws Exception {
        controller.setBatimento(Duration.ofMillis(30));
        controller.run(WORKFLOW_ID, null);
        assertThat(passoComecou.await(10, TimeUnit.SECONDS)).isTrue();
        String execucao = execucaoAtual();

        // Só a escrita falha: nenhum callback do container — é o batimento que tem de notar.
        conexao.caiu = true;
        aguardaEstado(execucao, FlowStatus.STOPPED);
        liberaPasso.countDown();

        assertThat(statusFinal(execucao)).isEqualTo("CANCELLED");
        assertThat(seguinteRodou).hasValue(0);
    }

    @Test
    @DisplayName("o fim normal do run (o nosso complete) não é pedido de parar")
    void fimNormalNaoCancela() throws Exception {
        liberaPasso.countDown();
        controller.run(WORKFLOW_ID, null);
        String execucao = execucaoAtual();

        assertThat(statusFinal(execucao)).isEqualTo("COMPLETED");
        // O container chama o onCompletion depois do complete() do fim do run. Num portão de
        // aprovação a retomada roda sob o mesmo id: cancelar aqui a mataria.
        conexao.clienteFechou();

        assertThat(seguinteRodou).hasValue(1);
        verify(engine, never()).cancel(anyString());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private FlowStep lento() {
        return new FlowStep() {
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
                return CompletableFuture.completedFuture(SimpleStepResult.ok("lento", "resposta do LLM", 1));
            }
        };
    }

    private FlowStep seguinte() {
        return new FlowStep() {
            @Override public String getId() { return "seguinte"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                seguinteRodou.incrementAndGet();
                return CompletableFuture.completedFuture(SimpleStepResult.ok("seguinte", "efeito", 1));
            }
        };
    }

    private static StepConnection edge(String from, String to) {
        return new StepConnection() {
            @Override public String getSourceId() { return from; }
            @Override public String getTargetId() { return to; }
            @Override public Optional<String> getCondition() { return Optional.empty(); }
            @Override public boolean isErrorPath() { return false; }
        };
    }

    private String execucaoAtual() {
        return String.valueOf(store.executions(DefaultTenant.ID, WORKFLOW_ID, 1).get(0).get("id"));
    }

    private String statusFinal(String execucao) throws InterruptedException {
        long prazo = System.currentTimeMillis() + 10_000;
        String status = String.valueOf(store.getExecution(DefaultTenant.ID, execucao).get("status"));
        while ("RUNNING".equals(status) && System.currentTimeMillis() < prazo) {
            Thread.sleep(20);
            status = String.valueOf(store.getExecution(DefaultTenant.ID, execucao).get("status"));
        }
        return status;
    }

    private void aguardaEstado(String execucao, FlowStatus esperado) throws InterruptedException {
        long prazo = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < prazo) {
            FlowState state = stateManager.loadState(execucao);
            if (state != null && state.getStatus() == esperado) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("estado " + esperado + " não chegou para " + execucao);
    }
}
