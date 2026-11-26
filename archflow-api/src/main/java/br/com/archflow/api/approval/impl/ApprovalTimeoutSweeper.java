package br.com.archflow.api.approval.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Roda {@link ApprovalQueueService#expireStale()} periodicamente.
 *
 * <p>Usa um executor próprio de uma thread em vez de {@code @Scheduled} porque a
 * aplicação não habilita o agendamento do Spring — ligá-lo por causa de uma
 * varredura mudaria o comportamento de todo o contexto. Um executor dedicado
 * também torna o sweeper testável sem subir Spring.
 *
 * <p>A varredura é idempotente: expirar algo já decidido perde a corrida para a
 * decisão humana e é ignorado.
 */
public class ApprovalTimeoutSweeper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTimeoutSweeper.class);

    private final ApprovalQueueService queue;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    /** Nulo sem aviso (testes, motor sem o ouvinte): aí só o vencimento conhecido e o intervalo valem. */
    private final ApprovalRequestedSignal signal;
    /** A mesma referência na inscrição e na saída: um method reference novo não seria removido. */
    private final Runnable wakeUp = this::wake;
    /** A varredura agendada. Só uma cadeia de agendamentos pode existir — ver {@link #scheduleNext}. */
    private ScheduledFuture<?> next;

    /** Folga depois do vencimento, para a varredura não rodar no instante exato e não ver nada. */
    static final long DEADLINE_SLACK_MS = 50;

    public ApprovalTimeoutSweeper(ApprovalQueueService queue, Duration interval) {
        this(queue, interval, null);
    }

    /**
     * @param signal avisa quando um pedido novo abre, para a varredura ir ao prazo dele; nulo = sem
     *               aviso, e um pedido aberto depois do agendamento só é visto no intervalo
     */
    public ApprovalTimeoutSweeper(ApprovalQueueService queue, Duration interval, ApprovalRequestedSignal signal) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.signal = signal;
        this.interval = interval == null || interval.isZero() || interval.isNegative()
                ? Duration.ofMinutes(5)
                : interval;
        ThreadFactory threads = r -> {
            Thread t = new Thread(r, "archflow-approval-sweeper");
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(threads);
    }

    /**
     * Agenda a varredura — sempre. Antes, sem prazo global, nada era agendado; mas um nó de
     * aprovação pode ter o próprio prazo, e ele nunca venceria. Sem prazo global e sem nó com
     * prazo, a varredura só lê a fila e não expira nada.
     */
    public void start() {
        if (signal != null) {
            signal.subscribe(wakeUp);
        }
        scheduleNext();
        if (queue.hasTimeout()) {
            log.info("Sweeper de aprovações ativo (intervalo={})", interval);
        } else {
            log.info("Sweeper de aprovações ativo (intervalo={}); sem prazo global "
                    + "(archflow.approval.timeout=0) — só vencem os nós com prazo próprio", interval);
        }
    }

    /**
     * A próxima varredura: no vencimento do próximo pedido, quando ele vem antes do intervalo.
     *
     * <p>Com só o intervalo fixo, um pedido vencido ficava até 5 min fora da fila, mas PENDING na API
     * e "aguardando" no detalhe da execução (RCHFLW-126). Rodar no vencimento fecha essa janela sem
     * criar decisão fora daqui: a recusa por timeout continua sendo só desta varredura. Um pedido
     * aberto depois deste agendamento chega por {@link #wake}; sem aviso, o intervalo é o teto.</p>
     *
     * <p>Substitui a varredura agendada antes, para nunca haver duas cadeias: uma varredura extra
     * ({@link #wake}) reagenda no fim, e a antiga continuaria se reagendando junto. Quando quem
     * reagenda é a própria varredura agendada, o cancel a pega já rodando: sem interrupção, ela
     * termina normalmente.</p>
     */
    private synchronized void scheduleNext() {
        long delay = interval.toMillis();
        try {
            delay = queue.untilNextExpiry()
                    // A fila expira o que passou do prazo (isAfter): uma folga curta garante que,
                    // quando a varredura rodar, o pedido já conte como vencido.
                    .map(until -> Math.min(interval.toMillis(), until.toMillis() + DEADLINE_SLACK_MS))
                    .orElse(delay);
        } catch (RuntimeException e) {
            log.warn("Não foi possível calcular o próximo vencimento de aprovação: {}", e.getMessage());
        }
        if (next != null) {
            next.cancel(false);
        }
        try {
            next = scheduler.schedule(this::sweepAndReschedule, Math.max(delay, 0L), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closed) {
            // close() já desligou o executor: não há mais o que agendar.
        }
    }

    /**
     * Um pedido novo abriu: varre agora e reagenda pelo prazo de todos, o dele incluído (RCHFLW-126).
     *
     * <p>Varre, e não só reagenda, porque o prazo do pedido pode já ter passado quando o aviso chega
     * (um prazo curto, uma travessia lenta) — e {@link ApprovalQueueService#untilNextExpiry()} ignora
     * prazos vencidos. Varrer só recusa o que venceu: a decisão continua sendo desta varredura, como
     * system:timeout. Roda na thread do sweeper, nunca na da travessia que avisou.</p>
     */
    public void wake() {
        try {
            scheduler.execute(this::sweepAndReschedule);
        } catch (RejectedExecutionException closed) {
            // Desligado: não há mais o que varrer.
        }
    }

    private void sweepAndReschedule() {
        try {
            sweepQuietly();
        } finally {
            scheduleNext();
        }
    }

    /**
     * Uma varredura. Nunca propaga: uma falha momentânea do store não pode matar
     * o reagendamento.
     */
    void sweepQuietly() {
        try {
            int expired = queue.expireStale();
            if (expired > 0) {
                log.info("{} aprovação(ões) expirada(s) e recusada(s) automaticamente", expired);
            }
        } catch (RuntimeException e) {
            log.warn("Falha na varredura de aprovações expiradas: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (signal != null) {
            signal.unsubscribe(wakeUp);
        }
        scheduler.shutdownNow();
    }
}
