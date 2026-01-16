package br.com.archflow.agent.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Registro de emitters de streaming ativos.
 *
 * <p>Este registry mantém track de todos os streams ativos,
 * permitindo broadcasting, cleanup e gerenciamento.
 *
 * <h3>Funcionalidades:</h3>
 * <ul>
 *   <li>Registro de emitters por executionId</li>
 *   <li> broadcasting para múltiplos subscribers</li>
 *   <li>Cleanup automático de emitters expirados</li>
 *   <li>Heartbeat automático para keep-alive</li>
 * </ul>
 */
public class EventStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(EventStreamRegistry.class);

    private final ConcurrentHashMap<String, EventStreamEmitter> emitters;
    private final ConcurrentHashMap<String, Set<String>> executionEmitters;
    private final ScheduledExecutorService scheduler;
    private final long heartbeatIntervalMs;
    private final long emitterTimeoutMs;

    /**
     * Cria um novo registry com configurações padrão.
     */
    public EventStreamRegistry() {
        this(30000, 300000); // heartbeat 30s, timeout 5min
    }

    /**
     * Cria um novo registry com configurações específicas.
     *
     * @param heartbeatIntervalMs Intervalo de heartbeat em ms
     * @param emitterTimeoutMs Timeout para cleanup de emitters inativos
     */
    public EventStreamRegistry(long heartbeatIntervalMs, long emitterTimeoutMs) {
        this.emitters = new ConcurrentHashMap<>();
        this.executionEmitters = new ConcurrentHashMap<>();
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.emitterTimeoutMs = emitterTimeoutMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "event-stream-registry");
            t.setDaemon(true);
            return t;
        });

        startHeartbeat();
        startCleanup();
    }

    /**
     * Registra um emitter.
     *
     * @param emitter Emitter a registrar
     * @return O emitter registrado
     */
    public EventStreamEmitter register(EventStreamEmitter emitter) {
        emitters.put(emitter.getEmitterId(), emitter);

        // Associa com executionId
        executionEmitters.computeIfAbsent(
                emitter.getExecutionId(),
                k -> ConcurrentHashMap.newKeySet()
        ).add(emitter.getEmitterId());

        log.debug("[{}] Registered emitter: {} for execution: {}",
                emitter.getExecutionId(), emitter.getEmitterId(), emitter.getExecutionId());

        return emitter;
    }

    /**
     * Cria e registra um novo emitter.
     *
     * @param executionId ID da execução
     * @return Emitter criado e registrado
     */
    public EventStreamEmitter createEmitter(String executionId) {
        EventStreamEmitter emitter = new EventStreamEmitter(executionId);
        return register(emitter);
    }

    /**
     * Remove um emitter.
     *
     * @param emitterId ID do emitter
     * @return Emitter removido ou null
     */
    public EventStreamEmitter unregister(String emitterId) {
        EventStreamEmitter emitter = emitters.remove(emitterId);
        if (emitter != null) {
            // Remove da associação de execution
            Set<String> execEmitters = executionEmitters.get(emitter.getExecutionId());
            if (execEmitters != null) {
                execEmitters.remove(emitterId);
                if (execEmitters.isEmpty()) {
                    executionEmitters.remove(emitter.getExecutionId());
                }
            }
            log.debug("[{}] Unregistered emitter: {}",
                    emitter.getExecutionId(), emitterId);
        }
        return emitter;
    }

    /**
     * Retorna um emitter por ID.
     *
     * @param emitterId ID do emitter
     * @return Emitter ou null
     */
    public EventStreamEmitter getEmitter(String emitterId) {
        return emitters.get(emitterId);
    }

    /**
     * Retorna todos os emitters de uma execução.
     *
     * @param executionId ID da execução
     * @return Lista de emitters
     */
    public List<EventStreamEmitter> getEmitters(String executionId) {
        Set<String> emitterIds = executionEmitters.get(executionId);
        if (emitterIds == null || emitterIds.isEmpty()) {
            return List.of();
        }

        List<EventStreamEmitter> result = new ArrayList<>();
        for (String id : emitterIds) {
            EventStreamEmitter emitter = emitters.get(id);
            if (emitter != null && !emitter.isCompleted()) {
                result.add(emitter);
            }
        }
        return result;
    }

    /**
     * Envia um evento para todos os emitters de uma execução.
     *
     * @param executionId ID da execução
     * @param event Evento a enviar
     * @return Número de emitters que receberam o evento
     */
    public int broadcast(String executionId, ArchflowEvent event) {
        List<EventStreamEmitter> execEmitters = getEmitters(executionId);
        int sent = 0;

        for (EventStreamEmitter emitter : execEmitters) {
            if (!emitter.isCompleted()) {
                try {
                    emitter.send(event);
                    sent++;
                } catch (Exception e) {
                    log.warn("[{}] Failed to send event to emitter {}",
                            executionId, emitter.getEmitterId(), e);
                }
            }
        }

        return sent;
    }

    /**
     * Envia um delta de chat para todos os emitters de uma execução.
     *
     * @param executionId ID da execução
     * @param content Conteúdo do delta
     * @return Número de emitters que receberam
     */
    public int broadcastDelta(String executionId, String content) {
        return broadcast(executionId,
                br.com.archflow.agent.streaming.domain.ChatEvent.delta(content, executionId));
    }

    /**
     * Completa todos os emitters de uma execução.
     *
     * @param executionId ID da execução
     * @return Número de emitters completados
     */
    public int completeAll(String executionId) {
        List<EventStreamEmitter> execEmitters = getEmitters(executionId);
        int completed = 0;

        for (EventStreamEmitter emitter : execEmitters) {
            if (!emitter.isCompleted()) {
                try {
                    emitter.complete();
                    completed++;
                } catch (Exception e) {
                    log.warn("[{}] Failed to complete emitter {}",
                            executionId, emitter.getEmitterId(), e);
                }
            }
        }

        return completed;
    }

    /**
     * Completa todos os emitters com erro.
     *
     * @param executionId ID da execução
     * @param error Erro
     * @return Número de emitters completados
     */
    public int completeAllWithError(String executionId, Throwable error) {
        List<EventStreamEmitter> execEmitters = getEmitters(executionId);
        int completed = 0;

        for (EventStreamEmitter emitter : execEmitters) {
            if (!emitter.isCompleted()) {
                try {
                    emitter.completeWithError(error);
                    completed++;
                } catch (Exception e) {
                    log.warn("[{}] Failed to complete emitter {} with error",
                            executionId, emitter.getEmitterId(), e);
                }
            }
        }

        return completed;
    }

    /**
     * Inicia o heartbeat automático.
     */
    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                int sent = 0;

                for (EventStreamEmitter emitter : emitters.values()) {
                    if (!emitter.isCompleted()) {
                        try {
                            emitter.sendHeartbeat();
                            sent++;
                        } catch (Exception e) {
                            // Emitter pode estar com problemas, remove
                            log.debug("Removing failed emitter during heartbeat: {}",
                                    emitter.getEmitterId());
                            unregister(emitter.getEmitterId());
                        }
                    }
                }

                if (sent > 0) {
                    log.trace("Heartbeat sent to {} active emitters", sent);
                }
            } catch (Exception e) {
                log.warn("Error during heartbeat", e);
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Inicia o cleanup de emitters expirados.
     */
    private void startCleanup() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                int removed = 0;
                long now = System.currentTimeMillis();

                for (Map.Entry<String, EventStreamEmitter> entry : emitters.entrySet()) {
                    EventStreamEmitter emitter = entry.getValue();

                    // Remove se completado ou expirado
                    if (emitter.isCompleted() ||
                        (now - emitter.getCreatedAt()) > emitterTimeoutMs) {
                        emitters.remove(entry.getKey());

                        // Remove da associação de execution
                        Set<String> execEmitters = executionEmitters.get(emitter.getExecutionId());
                        if (execEmitters != null) {
                            execEmitters.remove(entry.getKey());
                            if (execEmitters.isEmpty()) {
                                executionEmitters.remove(emitter.getExecutionId());
                            }
                        }

                        removed++;
                    }
                }

                if (removed > 0) {
                    log.debug("Cleanup: removed {} expired/completed emitters", removed);
                }
            } catch (Exception e) {
                log.warn("Error during cleanup", e);
            }
        }, emitterTimeoutMs, emitterTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Retorna estatísticas do registry.
     *
     * @return Estatísticas
     */
    public RegistryStats getStats() {
        int activeEmitters = 0;
        int completedEmitters = 0;
        int totalExecutions = executionEmitters.size();

        for (EventStreamEmitter emitter : emitters.values()) {
            if (emitter.isCompleted()) {
                completedEmitters++;
            } else {
                activeEmitters++;
            }
        }

        return new RegistryStats(
                emitters.size(),
                activeEmitters,
                completedEmitters,
                totalExecutions
        );
    }

    /**
     * Encerra o registry e limpa recursos.
     */
    public void shutdown() {
        log.info("Shutting down EventStreamRegistry...");

        // Completa todos os emitters ativos
        for (EventStreamEmitter emitter : emitters.values()) {
            if (!emitter.isCompleted()) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.warn("Error completing emitter during shutdown", e);
                }
            }
        }

        emitters.clear();
        executionEmitters.clear();
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("EventStreamRegistry shutdown complete");
    }

    /**
     * Estatísticas do registry.
     */
    public record RegistryStats(
            int totalEmitters,
            int activeEmitters,
            int completedEmitters,
            int totalExecutions
    ) {
        public int getActiveEmitters() {
            return activeEmitters;
        }

        public int getCompletedEmitters() {
            return completedEmitters;
        }
    }
}
