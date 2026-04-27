package br.com.archflow.api.triggers;

import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;

import java.util.List;

/**
 * CRUD over cron-scheduled triggers. Each trigger is materialised as a
 * Quartz job at create/update time; deletion removes the job.
 */
public interface ScheduledTriggerController {

    List<ScheduledTriggerDto> list();

    ScheduledTriggerDto get(String id);

    ScheduledTriggerDto create(ScheduledTriggerDto dto);

    ScheduledTriggerDto update(String id, ScheduledTriggerDto dto);

    void delete(String id);

    /** Fires the trigger once, immediately, bypassing the cron schedule. */
    ScheduledTriggerDto fireNow(String id);
}
