package br.com.archflow.api.triggers.store;

import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ScheduledTriggerStore} em memória, para dev e testes: não sobrevive a restart — como o
 * {@code RAMJobStore} do Quartz com que ele anda junto. O durável é o {@link JdbcScheduledTriggerStore}.
 */
public final class InMemoryScheduledTriggerStore implements ScheduledTriggerStore {

    private final Map<String, ScheduledTriggerDto> triggers = new ConcurrentHashMap<>();

    @Override
    public List<ScheduledTriggerDto> list() {
        return triggers.values().stream()
                .sorted(Comparator.comparing(ScheduledTriggerDto::createdAt).thenComparing(ScheduledTriggerDto::id))
                .toList();
    }

    @Override
    public Optional<ScheduledTriggerDto> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(triggers.get(id));
    }

    @Override
    public boolean insert(ScheduledTriggerDto trigger) {
        return triggers.putIfAbsent(trigger.id(), semAgenda(trigger)) == null;
    }

    @Override
    public Optional<ScheduledTriggerDto> updateConfig(ScheduledTriggerDto config, Instant expectedUpdatedAt) {
        boolean[] gravou = {false};
        ScheduledTriggerDto atual = triggers.computeIfPresent(config.id(), (k, gravado) -> {
            if (!Objects.equals(gravado.updatedAt(), expectedUpdatedAt)) {
                return gravado;
            }
            gravou[0] = true;
            return new ScheduledTriggerDto(gravado.id(), config.name(), config.cronExpression(), config.tenantId(),
                    config.agentId(), config.payload(), config.enabled(), gravado.createdAt(),
                    gravado.lastFiredAt(), gravado.lastError(), gravado.lastRequestId(), gravado.lastStatus(),
                    config.updatedAt(), null, null);
        });
        return gravou[0] ? Optional.of(atual) : Optional.empty();
    }

    @Override
    public boolean delete(String id) {
        return triggers.remove(id) != null;
    }

    @Override
    public void recordLastRun(String id, Instant firedAt, String error, String requestId, String status) {
        triggers.computeIfPresent(id, (k, t) -> t.withLastRun(firedAt, error, requestId, status));
    }

    @Override
    public void recordOutcome(String id, String requestId, String status, String error) {
        triggers.computeIfPresent(id, (k, t) -> requestId.equals(t.lastRequestId())
                ? t.withLastRun(t.lastFiredAt(), error, requestId, status)
                : t);
    }

    /** A agenda é do Quartz, lida a cada consulta: guardá-la aqui a deixaria velha. */
    private static ScheduledTriggerDto semAgenda(ScheduledTriggerDto t) {
        return t.withSchedule(null, null);
    }
}
