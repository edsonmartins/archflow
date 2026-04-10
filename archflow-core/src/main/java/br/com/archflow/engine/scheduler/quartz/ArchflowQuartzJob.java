package br.com.archflow.engine.scheduler.quartz;

import br.com.archflow.engine.scheduler.ScheduledJob;
import br.com.archflow.engine.scheduler.ScheduledJobListener;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Job Quartz que delega a execução para o {@link ScheduledJobListener} registrado.
 *
 * <p>Quando o Quartz dispara este job, ele reconstrói o {@link ScheduledJob}
 * a partir do {@link JobDataMap} e chama o listener configurado.
 */
public class ArchflowQuartzJob implements Job {
    private static final Logger logger = Logger.getLogger(ArchflowQuartzJob.class.getName());

    static final String KEY_TENANT_ID = "tenantId";
    static final String KEY_JOB_ID = "jobId";
    static final String KEY_CRON = "cronExpression";
    static final String KEY_AGENT_ID = "agentId";
    static final String KEY_LISTENER = "listener";

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap dataMap = context.getMergedJobDataMap();

        String tenantId = dataMap.getString(KEY_TENANT_ID);
        String jobId = dataMap.getString(KEY_JOB_ID);
        String cronExpression = dataMap.getString(KEY_CRON);
        String agentId = dataMap.getString(KEY_AGENT_ID);

        logger.info(() -> String.format("Executing scheduled job: tenant=%s, job=%s, agent=%s",
                tenantId, jobId, agentId));

        ScheduledJobListener listener = (ScheduledJobListener) dataMap.get(KEY_LISTENER);
        if (listener == null) {
            logger.warning("No ScheduledJobListener registered — job will be ignored");
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) dataMap.get("payload");

        ScheduledJob job = new ScheduledJob(
                tenantId, jobId, cronExpression, agentId,
                payload != null ? payload : Map.of(),
                true, Instant.now()
        );

        try {
            listener.onJobTriggered(job);
        } catch (Exception e) {
            logger.log(Level.SEVERE, String.format(
                    "Error executing job: tenant=%s, job=%s", tenantId, jobId), e);
            throw new JobExecutionException(e);
        }
    }
}
