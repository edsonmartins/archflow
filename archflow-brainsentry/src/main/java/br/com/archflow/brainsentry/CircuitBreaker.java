package br.com.archflow.brainsentry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker mínimo e sem dependências para proteger chamadas ao Brain
 * Sentry: depois de {@code failureThreshold} falhas consecutivas o circuito
 * ABRE e as chamadas são rejeitadas localmente por {@code openDuration};
 * passado esse período, uma única chamada de sonda (HALF_OPEN) decide se o
 * circuito fecha de novo ou reabre.
 *
 * <p>Evita que uma indisponibilidade prolongada do serviço externo consuma
 * timeout após timeout em cada fluxo — as falhas passam a custar microssegundos
 * e ficam visíveis nos contadores.
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long openDurationMillis;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openedAt = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();

    public CircuitBreaker(String name, int failureThreshold, Duration openDuration) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openDurationMillis = openDuration.toMillis();
    }

    /**
     * @return {@code true} se a chamada pode prosseguir; {@code false} se o
     *         circuito está aberto e a chamada deve ser rejeitada localmente
     */
    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() >= openDurationMillis
                    && state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                log.info("Circuit breaker '{}' half-open — allowing a probe request", name);
                return true;
            }
            rejectedCount.incrementAndGet();
            return false;
        }
        // HALF_OPEN: a sonda já está em voo; rejeita as demais até o veredito
        rejectedCount.incrementAndGet();
        return false;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        if (state.getAndSet(State.CLOSED) != State.CLOSED) {
            log.info("Circuit breaker '{}' closed after successful probe", name);
        }
    }

    public void recordFailure() {
        failureCount.incrementAndGet();
        State current = state.get();
        if (current == State.HALF_OPEN) {
            reopen();
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= failureThreshold && current == State.CLOSED) {
            reopen();
        }
    }

    private void reopen() {
        openedAt.set(System.currentTimeMillis());
        if (state.getAndSet(State.OPEN) != State.OPEN) {
            log.error("Circuit breaker '{}' OPEN after {} consecutive failures — "
                    + "rejecting calls locally for {} ms", name, consecutiveFailures.get(),
                    openDurationMillis);
        }
    }

    public State getState() {
        return state.get();
    }

    public long getRejectedCount() {
        return rejectedCount.get();
    }

    public long getFailureCount() {
        return failureCount.get();
    }
}
