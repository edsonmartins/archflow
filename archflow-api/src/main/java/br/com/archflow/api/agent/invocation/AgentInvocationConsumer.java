package br.com.archflow.api.agent.invocation;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * O consumidor da {@link AgentInvocationQueue} (RCHFLW-80).
 *
 * <p>A fila tinha quatro produtores — o invoke e o chat do Agent lab, os gatilhos agendados (cron e
 * "Disparar agora") e os eventos recebidos — e nenhum consumidor: todos respondiam {@code ACCEPTED} e
 * nada mais acontecia. Este é o único lugar que retira invocações da fila; os quatro chegam aqui
 * pelo mesmo caminho.</p>
 *
 * <p>Como funciona:</p>
 * <ul>
 *   <li><b>Um despachante, concorrência limitada.</b> Uma única thread reivindica ({@link
 *       AgentInvocationQueue#claim}) e entrega cada invocação a uma virtual thread; um semáforo limita
 *       quantas rodam ao mesmo tempo ({@code maxConcurrency}). Sem vaga, o despachante espera em vez de
 *       retirar: uma invocação retirada sem ter onde rodar ficaria parada fora da fila. Na fila em
 *       memória isso faz deste o consumidor único — nada mais chama {@code poll}.</li>
 *   <li><b>Nunca perder.</b> A invocação só é confirmada ({@link AgentInvocationQueue#complete}) depois de
 *       o resultado final gravado. Na fila JDBC, um consumidor que morre (ou um banco que falha ao
 *       gravar) deixa o lease vencer e a invocação volta; o lease é renovado enquanto o agente roda, para
 *       que uma execução longa não seja reivindicada por outra réplica.</li>
 *   <li><b>Nunca duplicar.</b> Antes de executar, o consumidor consulta o resultado: já final quer dizer
 *       que só faltou confirmar (caiu entre gravar e confirmar), e ele confirma sem rodar de novo. Um
 *       {@code RUNNING} encontrado é uma execução que morreu no meio; ela é retomada até
 *       {@code maxAttempts} vezes e depois vira {@code FAILED} — sem isso, uma invocação que derruba o
 *       processo derrubaria toda réplica que a pegasse, para sempre.</li>
 *   <li><b>Falha do agente é resultado.</b> Exceção do agente (ou {@code Result} sem sucesso) vira
 *       {@code FAILED} com a mensagem; não é repetida — repetir um agente que respondeu "não sei" só
 *       gastaria o mesmo erro de novo.</li>
 *   <li><b>Tempo limite.</b> Cada execução tem um prazo ({@code executionTimeout}). Sem ele, um agente
 *       pendurado num MCP/LLM que não responde segurava a vaga do semáforo e renovava o lease para
 *       sempre: nenhuma outra réplica o retomava, nada gravava {@code FAILED}, e {@code maxConcurrency}
 *       chamadas assim paravam o consumidor da réplica. Vencido o prazo, a execução é interrompida e
 *       vira {@code FAILED}; a vaga e o lease são liberados mesmo que o agente ignore a interrupção.</li>
 *   <li><b>Desligamento devolve.</b> Uma execução interrompida pelo desligamento volta para a fila na
 *       hora ({@link AgentInvocationQueue#release}), sem contar como tentativa: num rolling deploy ela
 *       ficaria escondida o lease inteiro, e três deploys durante execuções longas a dariam por
 *       abandonada.</li>
 *   <li><b>Retenção.</b> Resultados finais mais velhos que {@code resultRetention} são apagados
 *       periodicamente; ninguém apaga um resultado depois de lido.</li>
 * </ul>
 *
 * <p>É um {@link SmartLifecycle}: o Spring o liga no fim do refresh, depois de todos os singletons —
 * entre eles o Flyway. Ligado como {@code initMethod}, o despachante reivindicava contra uma tabela
 * ainda sem {@code claimed_by} num banco não migrado, e podia executar invocações remanescentes antes de
 * a aplicação estar pronta.</p>
 */
public class AgentInvocationConsumer implements SmartLifecycle, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentInvocationConsumer.class);

    /** De quanto em quanto a retenção roda (no máximo); a primeira volta é na partida. */
    static final Duration PURGE_EVERY = Duration.ofHours(1);
    /** Depois do {@code shutdownNow}, o tempo para os workers interrompidos devolverem as invocações. */
    private static final Duration RELEASE_GRACE = Duration.ofSeconds(2);

    /**
     * @param maxConcurrency invocações executando ao mesmo tempo nesta réplica
     * @param pollInterval   espera do despachante quando a fila está vazia
     * @param lease          reserva de uma invocação reivindicada; renovada a cada terço enquanto roda
     * @param maxAttempts    execuções interrompidas toleradas antes de desistir da invocação
     * @param shutdownGrace  quanto o desligamento espera as execuções em andamento
     * @param executionTimeout prazo de uma execução do agente; zero desliga (o agente pode pendurar a vaga)
     * @param resultRetention  por quanto tempo um resultado final fica consultável; zero desliga a limpeza
     */
    public record Settings(int maxConcurrency, Duration pollInterval, Duration lease, int maxAttempts,
                           Duration shutdownGrace, Duration executionTimeout, Duration resultRetention) {

        public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofMinutes(10);
        public static final Duration DEFAULT_RESULT_RETENTION = Duration.ofDays(7);

        public Settings {
            if (maxConcurrency <= 0) {
                throw new IllegalArgumentException("maxConcurrency deve ser positivo");
            }
            if (maxAttempts <= 0) {
                throw new IllegalArgumentException("maxAttempts deve ser positivo");
            }
            requirePositive(pollInterval, "pollInterval");
            requirePositive(lease, "lease");
            Objects.requireNonNull(shutdownGrace, "shutdownGrace");
            requireNotNegative(executionTimeout, "executionTimeout");
            requireNotNegative(resultRetention, "resultRetention");
        }

        /** Com o prazo de execução e a retenção padrão. */
        public Settings(int maxConcurrency, Duration pollInterval, Duration lease, int maxAttempts,
                        Duration shutdownGrace) {
            this(maxConcurrency, pollInterval, lease, maxAttempts, shutdownGrace, DEFAULT_EXECUTION_TIMEOUT,
                    DEFAULT_RESULT_RETENTION);
        }

        public static Settings defaults() {
            return new Settings(8, Duration.ofMillis(500), Duration.ofMinutes(5), 3, Duration.ofSeconds(10));
        }

        private static void requireNotNegative(Duration d, String name) {
            if (d == null || d.isNegative()) {
                throw new IllegalArgumentException(name + " não pode ser negativo (zero desliga)");
            }
        }

        private static void requirePositive(Duration d, String name) {
            if (d == null || d.isZero() || d.isNegative()) {
                throw new IllegalArgumentException(name + " deve ser positivo");
            }
        }
    }

    private final AgentInvocationQueue queue;
    private final AgentInvocationResultStore store;
    private final AgentInvocationExecutor executor;
    private final Settings settings;
    private final Clock clock;
    private final String consumerId;
    private final Semaphore permits;
    /** Retiradas da fila e ainda sem resultado final — a consulta as vê como RUNNING desde o claim. */
    private final Map<String, InvocationRequest> inFlight = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ExecutorService workers =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("agent-invocation-", 0).factory());
    /** Renovação dos leases e a limpeza da retenção. */
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("agent-invocation-timer-", 0).factory());

    private volatile boolean running;
    private Thread dispatcher;

    public AgentInvocationConsumer(AgentInvocationQueue queue, AgentInvocationResultStore store,
                                   AgentInvocationExecutor executor, Settings settings) {
        this(queue, store, executor, settings, Clock.systemUTC());
    }

    public AgentInvocationConsumer(AgentInvocationQueue queue, AgentInvocationResultStore store,
                                   AgentInvocationExecutor executor, Settings settings, Clock clock) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.permits = new Semaphore(settings.maxConcurrency());
        this.consumerId = consumerIdOf();
    }

    /** Liga o despachante (e a limpeza da retenção). Idempotente. */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        dispatcher = Thread.ofPlatform().daemon().name("agent-invocation-dispatcher").start(this::dispatch);
        schedulePurge();
        log.info("Consumidor da fila de invocações ligado: id={}, maxConcurrency={}, lease={}, timeout={}, "
                        + "retenção={}", consumerId, settings.maxConcurrency(), settings.lease(),
                settings.executionTimeout(), settings.resultRetention());
    }

    /** Desligamento pelo ciclo de vida do Spring — o mesmo do {@link #close()}. */
    @Override
    public void stop() {
        close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * A invocação que esta réplica retirou da fila e ainda não terminou. Na fila em memória, entre o claim e
     * o primeiro registro, ela não está nem na fila nem no store; sem isto a consulta daria 404 nesse
     * intervalo.
     */
    public Optional<InvocationRequest> inFlight(String requestId) {
        return requestId == null ? Optional.empty() : Optional.ofNullable(inFlight.get(requestId));
    }

    public String consumerId() {
        return consumerId;
    }

    private void dispatch() {
        while (running) {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Optional<InvocationRequest> next;
            try {
                next = queue.claim(consumerId, settings.lease());
            } catch (RuntimeException e) {
                // Banco fora, por exemplo: nada foi retirado; tenta de novo na próxima volta.
                permits.release();
                log.warn("Falha ao reivindicar invocação da fila: {}", e.getMessage());
                if (!pause()) {
                    return;
                }
                continue;
            }
            if (next.isEmpty()) {
                permits.release();
                if (!pause()) {
                    return;
                }
                continue;
            }
            InvocationRequest request = next.get();
            if (!running) {
                // O claim (I/O no banco) não responde à interrupção: o desligamento chegou durante ele.
                permits.release();
                releaseQuietly(request.requestId());
                return;
            }
            inFlight.put(request.requestId(), request);
            try {
                workers.execute(() -> {
                    try {
                        process(request);
                    } finally {
                        permits.release();
                    }
                });
            } catch (RejectedExecutionException e) {
                // Só no desligamento: devolve na hora (JDBC); em memória morre com o processo.
                permits.release();
                inFlight.remove(request.requestId());
                releaseQuietly(request.requestId());
                log.warn("Invocação {} retirada durante o desligamento e devolvida à fila", request.requestId());
                return;
            }
        }
    }

    /** @return {@code false} quando o consumidor foi desligado durante a espera */
    private boolean pause() {
        try {
            Thread.sleep(settings.pollInterval());
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Executa uma invocação reivindicada e registra o resultado (regras da classe). */
    void process(InvocationRequest request) {
        String id = request.requestId();
        inFlight.put(id, request);
        ScheduledFuture<?> renewal = scheduleRenewal(id);
        try {
            Optional<AgentInvocationResult> previous = store.find(id);
            if (previous.isPresent() && previous.get().status().terminal()) {
                // Resultado gravado, confirmação perdida: confirmar sem rodar de novo.
                queue.complete(id, consumerId);
                return;
            }
            int attempts = previous.map(AgentInvocationResult::attempts).orElse(0) + 1;
            AgentInvocationResult started = AgentInvocationResult.running(request, attempts, clock.instant());
            if (attempts > settings.maxAttempts()) {
                store.save(started.failed("invocação abandonada: " + (attempts - 1)
                        + " execuções interrompidas antes de terminar", clock.instant()));
                queue.complete(id, consumerId);
                log.error("Invocação {} (agent={}) abandonada após {} execuções interrompidas",
                        id, request.agentId(), attempts - 1);
                return;
            }
            store.save(started);

            AgentInvocationResult outcome;
            try {
                Object output = executeWithinTimeout(request);
                outcome = started.completed(toData(output), clock.instant());
            } catch (Exception | LinkageError | StackOverflowError e) {
                if (!running && (e instanceof InterruptedException || Thread.currentThread().isInterrupted())) {
                    // Interrompida pelo desligamento, não pelo agente: volta para a fila agora, e o
                    // registro volta ao que era — o desligamento não é uma tentativa do agente.
                    log.warn("Invocação {} interrompida pelo desligamento; devolvida à fila", id);
                    giveBack(request, previous);
                    return;
                }
                outcome = started.failed(messageOf(e), clock.instant());
                log.info("Invocação {} (agent={}, tenant={}) falhou: {}",
                        id, request.agentId(), request.tenantId(), outcome.error());
            }
            store.save(outcome);
            queue.complete(id, consumerId);
            log.info("Invocação {} (agent={}, tenant={}) terminou: {}",
                    id, request.agentId(), request.tenantId(), outcome.status());
        } catch (RuntimeException e) {
            // Falha ao gravar/confirmar (infraestrutura). Sem confirmação, a fila durável a devolve.
            log.error("Invocação {} não pôde ser registrada; fica na fila até o lease vencer", id, e);
        } finally {
            if (renewal != null) {
                renewal.cancel(false);
            }
            inFlight.remove(id);
        }
    }

    /**
     * Executa o agente numa thread própria e espera no máximo {@code executionTimeout}. A thread própria é
     * o que garante a vaga de volta: um agente bloqueado em I/O que ignora a interrupção continua preso
     * nela, mas o worker segue, grava {@code FAILED} e libera semáforo e lease.
     */
    private Object executeWithinTimeout(InvocationRequest request) throws Exception {
        Duration timeout = settings.executionTimeout();
        if (timeout.isZero()) {
            return executor.execute(request);
        }
        FutureTask<Object> task = new FutureTask<>(() -> executor.execute(request));
        Thread.ofVirtual().name("agent-invocation-run-" + request.requestId()).start(task);
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            log.warn("Invocação {} (agent={}) excedeu o tempo limite de {}; interrompida",
                    request.requestId(), request.agentId(), timeout);
            throw new TimeoutException("a execução do agente excedeu o tempo limite de " + timeout
                    + " (archflow.agents.invocation.timeout)");
        } catch (InterruptedException e) {
            task.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    /**
     * Desfaz o claim de uma execução que o desligamento interrompeu: o registro volta ao anterior (ou a
     * {@code QUEUED}, para a consulta não mostrar um RUNNING que ninguém executa) e a fila a libera.
     */
    private void giveBack(InvocationRequest request, Optional<AgentInvocationResult> previous) {
        boolean interrupted = Thread.interrupted(); // o driver JDBC pode recusar I/O numa thread interrompida
        try {
            store.save(previous.orElseGet(() -> AgentInvocationResult.queued(request)));
        } catch (RuntimeException e) {
            log.warn("Invocação {}: falha ao restaurar o registro no desligamento: {}",
                    request.requestId(), e.getMessage());
        } finally {
            releaseQuietly(request.requestId());
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Devolve o claim à fila; falhar só atrasa a invocação até o lease vencer. */
    private void releaseQuietly(String requestId) {
        boolean interrupted = Thread.interrupted();
        try {
            queue.release(requestId, consumerId);
        } catch (RuntimeException e) {
            log.warn("Invocação {} não pôde ser devolvida à fila; volta quando o lease vencer: {}",
                    requestId, e.getMessage());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void schedulePurge() {
        Duration retention = settings.resultRetention();
        if (retention.isZero()) {
            return;
        }
        long every = Math.min(retention.toMillis(), PURGE_EVERY.toMillis());
        try {
            timers.scheduleAtFixedRate(this::purgeExpiredResults, 0, every, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Limpeza da retenção não agendada: consumidor já desligado");
        }
    }

    /** Apaga os resultados finais além da retenção. Falhar (banco fora) só adia para a próxima volta. */
    int purgeExpiredResults() {
        Duration retention = settings.resultRetention();
        if (retention.isZero()) {
            return 0;
        }
        try {
            int purged = store.purgeFinishedBefore(clock.instant().minus(retention));
            if (purged > 0) {
                log.info("Retenção das invocações: {} resultado(s) com mais de {} apagado(s)", purged, retention);
            }
            return purged;
        } catch (RuntimeException e) {
            log.warn("Falha ao aplicar a retenção dos resultados de invocação: {}", e.getMessage());
            return 0;
        }
    }

    private ScheduledFuture<?> scheduleRenewal(String requestId) {
        long every = Math.max(100, settings.lease().toMillis() / 3);
        try {
            return timers.scheduleAtFixedRate(() -> {
                try {
                    if (!queue.renew(requestId, consumerId, settings.lease())) {
                        log.warn("Lease da invocação {} perdido: outro consumidor pode executá-la", requestId);
                    }
                } catch (RuntimeException e) {
                    log.warn("Falha ao renovar o lease da invocação {}: {}", requestId, e.getMessage());
                }
            }, every, every, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    /**
     * A saída como dados JSON (mapa, lista, texto...). Converter aqui, e não na resposta HTTP, faz a
     * consulta devolver o mesmo formato da memória e do banco; o que o Jackson não sabe converter vira o
     * texto da saída, em vez de perder o resultado.
     */
    Object toData(Object output) {
        if (output == null || output instanceof String || output instanceof Number || output instanceof Boolean) {
            return output;
        }
        try {
            return mapper.convertValue(output, Object.class);
        } catch (IllegalArgumentException e) {
            return String.valueOf(output);
        }
    }

    static String messageOf(Throwable e) {
        Throwable t = e;
        while ((t instanceof CompletionException || t instanceof ExecutionException
                || t instanceof InvocationTargetException) && t.getCause() != null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private static String consumerIdOf() {
        String host = System.getenv("HOSTNAME");
        String prefix = host == null || host.isBlank() ? "archflow" : host;
        return prefix + "-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Para de reivindicar, espera o despachante sair (ele pode estar no meio de um claim, que devolve) e
     * dá às execuções em andamento até {@code shutdownGrace}; as que passarem são interrompidas e voltam
     * para a fila.
     */
    @Override
    public void close() {
        Thread current;
        synchronized (this) {
            if (!running) {
                timers.shutdownNow();
                workers.shutdownNow();
                return;
            }
            running = false;
            current = dispatcher;
        }
        try {
            if (current != null) {
                current.interrupt();
                // Sem esperar, o despachante podia reivindicar mais uma depois do shutdown dos workers.
                current.join(settings.shutdownGrace());
            }
            workers.shutdown();
            if (!workers.awaitTermination(settings.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
                workers.awaitTermination(RELEASE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        timers.shutdownNow();
        log.info("Consumidor da fila de invocações desligado: id={}", consumerId);
    }
}
