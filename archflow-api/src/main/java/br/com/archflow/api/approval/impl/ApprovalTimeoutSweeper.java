package br.com.archflow.api.approval.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    public ApprovalTimeoutSweeper(ApprovalQueueService queue, Duration interval) {
        this.queue = Objects.requireNonNull(queue, "queue");
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

    /** Agenda a varredura. Sem timeout configurado, não agenda nada. */
    public void start() {
        if (!queue.hasTimeout()) {
            log.info("Expiração de aprovações desligada (archflow.approval.timeout=0)");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::sweepQuietly,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Sweeper de aprovações ativo (intervalo={})", interval);
    }

    /**
     * Uma varredura. Nunca propaga: uma falha momentânea do store não pode matar
     * a tarefa agendada — {@code scheduleWithFixedDelay} cancela a execução
     * periódica se a task lançar.
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
        scheduler.shutdownNow();
    }
}
