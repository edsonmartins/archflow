package br.com.archflow.api.triggers.store;

import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Registro dos gatilhos agendados (RCHFLW-187).
 *
 * <p>Era um mapa estático no controller, enquanto o job do Quartz ia para o banco com o JDBC ligado: depois
 * de um restart a tela não listava nada e os jobs continuavam disparando sem achar o gatilho. Em memória por
 * default (dev, junto do {@code RAMJobStore}); durável em {@code JdbcPersistenceConfiguration} (migration
 * V6_15), no mesmo par dos outros stores — os dois lados ficam sempre do mesmo tipo.</p>
 *
 * <p>A configuração e o último disparo são gravados por operações separadas de propósito: o disparo do cron
 * não pode apagar uma edição feita no meio dele, e a edição não pode apagar o desfecho do disparo. Os campos
 * calculados ({@code nextFireTime}, {@code timeZone}) não são gravados — vêm do Quartz a cada leitura.</p>
 */
public interface ScheduledTriggerStore {

    /** Todos os gatilhos, do mais antigo ao mais novo. */
    List<ScheduledTriggerDto> list();

    Optional<ScheduledTriggerDto> find(String id);

    /**
     * Grava um gatilho novo.
     *
     * @return {@code false} quando o id já existe — nada é gravado
     */
    boolean insert(ScheduledTriggerDto trigger);

    /**
     * Troca a configuração (nome, cron, tenant, agente, payload, ativo e {@code updatedAt}) se a gravada ainda
     * é a versão {@code expectedUpdatedAt}. O último disparo fica como está.
     *
     * @return o gatilho gravado, ou vazio quando ele sumiu ou outra gravação mudou a versão
     */
    Optional<ScheduledTriggerDto> updateConfig(ScheduledTriggerDto config, Instant expectedUpdatedAt);

    /** @return {@code false} quando não havia gatilho com esse id */
    boolean delete(String id);

    /** Grava o último disparo, sem tocar na configuração. Gatilho ausente: nada acontece. */
    void recordLastRun(String id, Instant firedAt, String error, String requestId, String status);

    /**
     * Grava o desfecho do disparo {@code requestId} — só se ele ainda for o último: um disparo que chegou no
     * meio já é o "último", e o desfecho do anterior não pode cobri-lo.
     */
    void recordOutcome(String id, String requestId, String status, String error);
}
