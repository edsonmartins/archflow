package br.com.archflow.api.triggers;

import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;

import java.time.Instant;
import java.util.List;

/**
 * CRUD over cron-scheduled triggers. Each trigger is materialised as a
 * Quartz job at create/update time; deletion removes the job.
 */
public interface ScheduledTriggerController {

    List<ScheduledTriggerDto> list();

    ScheduledTriggerDto get(String id);

    ScheduledTriggerDto create(ScheduledTriggerDto dto);

    /** Edição sem pré-condição de versão — a de sempre, para quem faz PUT sem {@code If-Match}. */
    default ScheduledTriggerDto update(String id, ScheduledTriggerDto dto) {
        return update(id, dto, null);
    }

    /**
     * Edita o gatilho.
     *
     * @param expectedUpdatedAt o {@code updatedAt} que quem edita leu ({@code If-Match}); com ele, a edição só
     *                          grava se o gatilho continua nessa versão — senão {@link TriggerConflictException}.
     *                          {@code null} = sem pré-condição
     */
    ScheduledTriggerDto update(String id, ScheduledTriggerDto dto, Instant expectedUpdatedAt);

    void delete(String id);

    /** Fires the trigger once, immediately, bypassing the cron schedule. */
    ScheduledTriggerDto fireNow(String id);

    /**
     * Gatilho recusado por um motivo que quem preencheu o formulário pode corrigir (400). O {@code code} é o
     * que a tela traduz (RCHFLW-191): a mensagem é a da API, em inglês, e o texto cru do Quartz não explicava
     * o formato a quem errou o cron.
     */
    final class TriggerRejectedException extends IllegalArgumentException {

        public static final String NAME_REQUIRED = "TRIGGER_NAME_REQUIRED";
        public static final String AGENT_UNKNOWN = "TRIGGER_AGENT_UNKNOWN";
        public static final String CRON_INVALID = "TRIGGER_CRON_INVALID";
        public static final String CRON_NEVER_FIRES = "TRIGGER_CRON_NEVER_FIRES";

        private final String code;

        public TriggerRejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * Outra gravação mudou o gatilho desde que quem edita o leu (409, RCHFLW-191). Antes o segundo Salvar
     * gravava o formulário inteiro por cima e desfazia em silêncio o cron e o payload do primeiro.
     */
    final class TriggerConflictException extends IllegalStateException {

        public static final String CODE = "TRIGGER_CONFLICT";

        private final transient Instant currentUpdatedAt;

        public TriggerConflictException(String id, Instant currentUpdatedAt) {
            super("Trigger " + id + " was changed elsewhere since it was loaded");
            this.currentUpdatedAt = currentUpdatedAt;
        }

        public Instant currentUpdatedAt() {
            return currentUpdatedAt;
        }
    }
}
