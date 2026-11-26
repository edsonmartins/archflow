package br.com.archflow.api.triggers;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.agent.invocation.AgentInvocationResult;
import br.com.archflow.api.agent.invocation.InMemoryAgentInvocationResultStore;
import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import br.com.archflow.api.triggers.impl.ScheduledTriggerControllerImpl;
import br.com.archflow.api.triggers.store.InMemoryScheduledTriggerStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.impl.StdSchedulerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * O gatilho registra como terminou o último disparo (RCHFLW-104).
 *
 * <p>Na homologação, o agente de um gatilho falhou nas duas vezes ("Disparar agora" e o cron) e o card
 * "Com falha" seguiu em 0: o disparo só enfileira, e a falha do agente ficava no resultado da invocação,
 * que o gatilho nunca lia. Os testes gravam o resultado como o consumidor grava e afirmam o que a lista
 * devolve — é ela que a tela conta.</p>
 */
@DisplayName("Desfecho do último disparo do gatilho")
class DesfechoDoDisparoTest {

    private Scheduler scheduler;
    private InMemoryAgentInvocationQueue queue;
    private InMemoryAgentInvocationResultStore results;
    private InMemoryScheduledTriggerStore store;
    private ScheduledTriggerController controller;
    private final List<String> criados = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "desfecho-do-disparo");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        StdSchedulerFactory factory = new StdSchedulerFactory();
        factory.initialize(props);
        scheduler = factory.getScheduler();
        scheduler.start();
        queue = new InMemoryAgentInvocationQueue();
        results = new InMemoryAgentInvocationResultStore();
        store = new InMemoryScheduledTriggerStore();
        controller = new ScheduledTriggerControllerImpl(scheduler, queue, results, store, agentId -> true);
    }

    @AfterEach
    void tearDown() throws Exception {
        // O registro é deste teste, mas o job sai do Quartz junto: o scheduler é desligado logo abaixo.
        for (String id : criados) {
            try {
                controller.delete(id);
            } catch (IllegalArgumentException jaRemovido) {
                // nada
            }
        }
        scheduler.shutdown(false);
    }

    private ScheduledTriggerDto criar(String cron, boolean ativo) {
        ScheduledTriggerDto t = controller.create(new ScheduledTriggerDto(
                null, "noturno", cron, "tenant-a", "agente-x", Map.of(), ativo, null, null, null, null));
        criados.add(t.id());
        return t;
    }

    private void gravar(String requestId, AgentInvocationResult.Status status, String erro) {
        InvocationRequest req = new InvocationRequest(requestId, "tenant-a", "agente-x", Map.of(), null, 0,
                Instant.now());
        AgentInvocationResult running = AgentInvocationResult.running(req, 1, Instant.now());
        results.save(switch (status) {
            case FAILED -> running.failed(erro, Instant.now());
            case COMPLETED -> running.completed("ok", Instant.now());
            default -> running;
        });
    }

    private ScheduledTriggerDto naLista(String id) {
        return controller.list().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("o agente falhou: a lista traz lastError e status FAILED")
    void falhaDoAgenteViraLastError() {
        ScheduledTriggerDto trigger = criar("0 0 2 * * ?", true);
        String requestId = controller.fireNow(trigger.id()).lastRequestId();
        assertThat(naLista(trigger.id()).lastStatus()).isEqualTo("QUEUED");

        gravar(requestId, AgentInvocationResult.Status.FAILED, "mcp-agent: 'systemPrompt' é obrigatório");

        ScheduledTriggerDto lido = naLista(trigger.id());
        assertThat(lido.lastStatus()).isEqualTo("FAILED");
        assertThat(lido.lastError()).isEqualTo("mcp-agent: 'systemPrompt' é obrigatório");
        assertThat(controller.get(trigger.id()).lastError()).isEqualTo(lido.lastError());
    }

    @Test
    @DisplayName("um disparo seguinte que dá certo limpa o erro")
    void sucessoSeguinteLimpaOErro() {
        ScheduledTriggerDto trigger = criar("0 0 2 * * ?", true);
        String primeiro = controller.fireNow(trigger.id()).lastRequestId();
        gravar(primeiro, AgentInvocationResult.Status.FAILED, "boom");
        assertThat(naLista(trigger.id()).lastError()).isEqualTo("boom");

        String segundo = controller.fireNow(trigger.id()).lastRequestId();
        gravar(segundo, AgentInvocationResult.Status.COMPLETED, null);

        ScheduledTriggerDto lido = naLista(trigger.id());
        assertThat(lido.lastRequestId()).isEqualTo(segundo);
        assertThat(lido.lastStatus()).isEqualTo("COMPLETED");
        assertThat(lido.lastError()).isNull();
    }

    @Test
    @DisplayName("o desfecho final fica no gatilho depois que a retenção apaga o resultado")
    void desfechoSobreviveARetencao() {
        ScheduledTriggerDto trigger = criar("0 0 2 * * ?", true);
        String requestId = controller.fireNow(trigger.id()).lastRequestId();
        gravar(requestId, AgentInvocationResult.Status.FAILED, "boom");
        naLista(trigger.id());

        results.purgeFinishedBefore(Instant.now().plusSeconds(60));

        assertThat(naLista(trigger.id()).lastError()).isEqualTo("boom");
    }

    @Test
    @DisplayName("disparo automático (cron) que falha também aparece no gatilho")
    void disparoAutomaticoFalho() throws Exception {
        ScheduledTriggerDto trigger = criar("* * * * * ?", true);

        InvocationRequest disparada = null;
        long prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (disparada == null && System.nanoTime() < prazo) {
            disparada = queue.poll().orElse(null);
            if (disparada == null) {
                Thread.sleep(20);
            }
        }
        assertThat(disparada).as("o cron não disparou").isNotNull();
        controller.update(trigger.id(), new ScheduledTriggerDto(trigger.id(), trigger.name(),
                trigger.cronExpression(), trigger.tenantId(), trigger.agentId(), trigger.payload(), false,
                null, null, null, null));
        String requestId = controller.get(trigger.id()).lastRequestId();
        gravar(requestId, AgentInvocationResult.Status.FAILED, "agente quebrou");

        ScheduledTriggerDto lido = naLista(trigger.id());
        assertThat(lido.lastStatus()).isEqualTo("FAILED");
        assertThat(lido.lastError()).isEqualTo("agente quebrou");
    }

    /**
     * Sem o status final na falha de enfileirar, a leitura consultaria o resultado do requestId
     * anterior — e um disparo antigo bem-sucedido apagaria a falha deste.
     */
    @Test
    @DisplayName("a falha de enfileirar não é coberta pelo sucesso do disparo anterior")
    void falhaDeEnfileirarNaoECoberta() {
        ScheduledTriggerDto trigger = criar("0 0 2 * * ?", true);
        String anterior = controller.fireNow(trigger.id()).lastRequestId();
        gravar(anterior, AgentInvocationResult.Status.COMPLETED, null);
        assertThat(naLista(trigger.id()).lastStatus()).isEqualTo("COMPLETED");

        AgentInvocationQueue fora = mock(AgentInvocationQueue.class);
        doThrow(new IllegalStateException("fila fora")).when(fora).submit(org.mockito.ArgumentMatchers.any());
        // Outra instância sobre o mesmo registro: a fila é dela, o gatilho é o mesmo.
        ScheduledTriggerController comFilaFora = new ScheduledTriggerControllerImpl(scheduler, fora, results, store,
                agentId -> true);
        assertThatThrownBy(() -> comFilaFora.fireNow(trigger.id())).isInstanceOf(IllegalStateException.class);

        ScheduledTriggerDto lido = naLista(trigger.id());
        assertThat(lido.lastStatus()).isEqualTo("FAILED");
        assertThat(lido.lastError()).isEqualTo("fila fora");
    }

    /**
     * O disparo que não enfileirou não tem invocação. Com o {@code lastRequestId} do anterior, o "Ver resposta"
     * da tela abria a execução anterior — concluída, com a resposta dela — ao lado de um gatilho "com falha".
     */
    @Test
    @DisplayName("a falha de enfileirar não deixa a resposta do disparo anterior como a do último")
    void falhaDeEnfileirarNaoApontaParaOAnterior() {
        ScheduledTriggerDto trigger = criar("0 0 2 * * ?", true);
        String anterior = controller.fireNow(trigger.id()).lastRequestId();
        gravar(anterior, AgentInvocationResult.Status.COMPLETED, null);
        assertThat(naLista(trigger.id()).lastStatus()).isEqualTo("COMPLETED");

        AgentInvocationQueue fora = mock(AgentInvocationQueue.class);
        doThrow(new IllegalStateException("fila fora")).when(fora).submit(org.mockito.ArgumentMatchers.any());
        ScheduledTriggerController comFilaFora = new ScheduledTriggerControllerImpl(scheduler, fora, results, store,
                agentId -> true);
        assertThatThrownBy(() -> comFilaFora.fireNow(trigger.id())).isInstanceOf(IllegalStateException.class);

        ScheduledTriggerDto lido = naLista(trigger.id());
        assertThat(lido.lastStatus()).isEqualTo("FAILED");
        assertThat(lido.lastRequestId()).as("a tela abriria a execução anterior como a última").isNull();
    }
}
