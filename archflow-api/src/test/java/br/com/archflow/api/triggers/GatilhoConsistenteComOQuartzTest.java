package br.com.archflow.api.triggers;

import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import br.com.archflow.api.triggers.impl.ScheduledTriggerControllerImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.CronTrigger;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RCHFLW-140 e RCHFLW-175: o registro de gatilhos e o Quartz não podem divergir numa falha.
 *
 * <p>Na homologação, um cron que nunca dispara ({@code 0 0 9 31 2 ? 2027}) respondia 409 e ficava gravado como
 * ativo, sem job; salvar de novo duplicava o gatilho. Na edição era pior: o job que funcionava era apagado antes
 * de o novo ser recusado, e o gatilho parava de disparar enquanto a lista mostrava o cron recusado como ativo.
 * Os testes afirmam o que fica no registro e no próprio Quartz depois da recusa.</p>
 */
@DisplayName("Gatilho agendado: registro e Quartz não divergem numa falha (RCHFLW-140, RCHFLW-175)")
class GatilhoConsistenteComOQuartzTest {

    private static final String GROUP = "archflow-triggers";
    private static final String CRON_OK = "0 0 9 ? * MON-FRI";

    private Scheduler real;
    /** Liga a falha do {@code scheduleJob} — o Quartz recusando por outro motivo (banco fora, por exemplo). */
    private final AtomicBoolean quartzRecusa = new AtomicBoolean();
    private ScheduledTriggerController controller;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "gatilho-consistente");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        StdSchedulerFactory factory = new StdSchedulerFactory();
        factory.initialize(props);
        real = factory.getScheduler();
        real.start();
        Scheduler comFalha = (Scheduler) Proxy.newProxyInstance(Scheduler.class.getClassLoader(),
                new Class<?>[] {Scheduler.class}, (proxy, method, args) -> {
                    if (method.getName().equals("scheduleJob") && quartzRecusa.get()) {
                        throw new SchedulerException("JobStore indisponível");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        controller = new ScheduledTriggerControllerImpl(comFalha, new InMemoryAgentInvocationQueue());
        for (ScheduledTriggerDto t : controller.list()) {
            controller.delete(t.id());
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        quartzRecusa.set(false);
        for (ScheduledTriggerDto t : controller.list()) {
            controller.delete(t.id());
        }
        real.shutdown(false);
    }

    private ScheduledTriggerDto gatilho(String id, String cron, boolean ativo) {
        return new ScheduledTriggerDto(id, "QA2 gatilhos triagem reembolso", cron, "tenant-a", "agente-x",
                Map.of("pedido", "reembolso"), ativo, null, null, null, null);
    }

    private String cronNoQuartz(String id) throws SchedulerException {
        CronTrigger t = (CronTrigger) real.getTrigger(new TriggerKey(id, GROUP));
        return t == null ? null : t.getCronExpression();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0 0 9 31 2 ? 2027", "0 0 9 * * ? 2020", "0 0 0 30 2 ?"})
    @DisplayName("criar com cron que nunca dispara: 400 e nada gravado, nem registro nem job")
    void criarComCronQueNuncaDispara(String cron) throws Exception {
        assertThatThrownBy(() -> controller.create(gatilho(null, cron, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never fires");

        assertThat(controller.list()).isEmpty();
        assertThat(real.getJobKeys(GroupMatcher.jobGroupEquals(GROUP))).isEmpty();
    }

    @Test
    @DisplayName("salvar de novo, com o cron corrigido, cria exatamente um gatilho")
    void salvarDeNovoNaoDuplica() {
        assertThatThrownBy(() -> controller.create(gatilho(null, "0 0 9 31 2 ? 2027", true)))
                .isInstanceOf(IllegalArgumentException.class);

        controller.create(gatilho(null, CRON_OK, true));

        assertThat(controller.list()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0 0 9 31 2 ? 2027", "0 0 9 * * ? 2020"})
    @DisplayName("editar para um cron que nunca dispara: recusa, e o agendamento anterior continua valendo")
    void editarParaCronQueNuncaDispara(String cron) throws Exception {
        ScheduledTriggerDto t = controller.create(gatilho(null, CRON_OK, true));

        assertThatThrownBy(() -> controller.update(t.id(), gatilho(t.id(), cron, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never fires");

        assertThat(controller.get(t.id()).cronExpression()).isEqualTo(CRON_OK);
        assertThat(cronNoQuartz(t.id())).as("o job que funcionava sumiu do Quartz").isEqualTo(CRON_OK);
    }

    @Test
    @DisplayName("o Quartz recusa a criação: o registro não fica com um gatilho sem job")
    void quartzRecusaACriacao() throws Exception {
        quartzRecusa.set(true);

        assertThatThrownBy(() -> controller.create(gatilho(null, CRON_OK, true)))
                .isInstanceOf(IllegalStateException.class);

        quartzRecusa.set(false);
        assertThat(controller.list()).isEmpty();
    }

    @Test
    @DisplayName("o Quartz recusa a edição: registro e job ficam com a configuração anterior")
    void quartzRecusaAEdicao() throws Exception {
        ScheduledTriggerDto t = controller.create(gatilho(null, CRON_OK, true));
        quartzRecusa.set(true);

        assertThatThrownBy(() -> controller.update(t.id(), gatilho(t.id(), "0 30 7 * * ?", true)))
                .isInstanceOf(IllegalStateException.class);

        quartzRecusa.set(false);
        assertThat(controller.get(t.id()).cronExpression()).isEqualTo(CRON_OK);
        assertThat(cronNoQuartz(t.id())).isEqualTo(CRON_OK);
    }

    @Test
    @DisplayName("a edição que dá certo troca o job, e pausar tira o gatilho do Quartz")
    void edicaoQueDaCerto() throws Exception {
        ScheduledTriggerDto t = controller.create(gatilho(null, CRON_OK, true));

        controller.update(t.id(), gatilho(t.id(), "0 30 7 * * ?", true));
        assertThat(cronNoQuartz(t.id())).isEqualTo("0 30 7 * * ?");

        controller.update(t.id(), gatilho(t.id(), "0 30 7 * * ?", false));
        assertThat(cronNoQuartz(t.id())).isNull();
        assertThat(controller.get(t.id()).enabled()).isFalse();
    }
}
