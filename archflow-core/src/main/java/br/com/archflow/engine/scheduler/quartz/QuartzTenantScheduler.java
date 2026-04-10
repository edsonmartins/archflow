package br.com.archflow.engine.scheduler.quartz;

import br.com.archflow.engine.scheduler.ScheduledJob;
import br.com.archflow.engine.scheduler.ScheduledJobListener;
import br.com.archflow.engine.scheduler.TenantScheduler;
import br.com.archflow.engine.scheduler.dlq.DeadLetterQueue;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementação do {@link TenantScheduler} usando Quartz Scheduler.
 *
 * <p>Cada job é registrado com {@code JobKey(jobId, tenantId)} para
 * garantir isolamento entre tenants. Falha no job de um tenant não
 * afeta os demais.
 *
 * <p>Quando um job falha, ele é enviado para a {@link DeadLetterQueue}
 * configurada (se presente).
 */
public class QuartzTenantScheduler implements TenantScheduler {
    private static final Logger logger = Logger.getLogger(QuartzTenantScheduler.class.getName());

    private final Scheduler scheduler;
    private final ScheduledJobListener jobListener;
    private final DeadLetterQueue deadLetterQueue;
    private final Map<String, ScheduledJob> jobRegistry;

    public QuartzTenantScheduler(ScheduledJobListener jobListener) {
        this(jobListener, null);
    }

    public QuartzTenantScheduler(ScheduledJobListener jobListener, DeadLetterQueue dlq) {
        this(createDefaultScheduler(), jobListener, dlq);
    }

    public QuartzTenantScheduler(Scheduler scheduler, ScheduledJobListener jobListener, DeadLetterQueue dlq) {
        this.scheduler = scheduler;
        this.jobListener = jobListener;
        this.deadLetterQueue = dlq;
        this.jobRegistry = new ConcurrentHashMap<>();

        try {
            if (!scheduler.isStarted()) {
                scheduler.start();
            }
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to start Quartz scheduler", e);
        }

        registerGlobalJobListener();
    }

    @Override
    public void schedule(ScheduledJob job) {
        String registryKey = registryKey(job.tenantId(), job.jobId());

        if (jobRegistry.containsKey(registryKey)) {
            throw new IllegalArgumentException(
                    "Job already exists: tenant=" + job.tenantId() + ", job=" + job.jobId());
        }

        try {
            JobDetail jobDetail = buildJobDetail(job);
            Trigger trigger = buildTrigger(job);

            scheduler.scheduleJob(jobDetail, trigger);
            jobRegistry.put(registryKey, job);

            logger.info(() -> String.format("Scheduled job: tenant=%s, job=%s, cron=%s",
                    job.tenantId(), job.jobId(), job.cronExpression()));

        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to schedule job: " + registryKey, e);
        }
    }

    @Override
    public void reschedule(String tenantId, String jobId, String newCron) {
        String registryKey = registryKey(tenantId, jobId);
        ScheduledJob existing = jobRegistry.get(registryKey);

        if (existing == null) {
            throw new IllegalArgumentException("Job not found: tenant=" + tenantId + ", job=" + jobId);
        }

        try {
            TriggerKey triggerKey = triggerKey(tenantId, jobId);
            Trigger newTrigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .withSchedule(CronScheduleBuilder.cronSchedule(newCron))
                    .build();

            scheduler.rescheduleJob(triggerKey, newTrigger);

            ScheduledJob updated = existing.withCron(newCron);
            jobRegistry.put(registryKey, updated);

            logger.info(() -> String.format("Rescheduled job: tenant=%s, job=%s, newCron=%s",
                    tenantId, jobId, newCron));

        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to reschedule job: " + registryKey, e);
        }
    }

    @Override
    public boolean cancel(String tenantId, String jobId) {
        String registryKey = registryKey(tenantId, jobId);
        try {
            boolean deleted = scheduler.deleteJob(jobKey(tenantId, jobId));
            jobRegistry.remove(registryKey);
            if (deleted) {
                logger.info(() -> String.format("Cancelled job: tenant=%s, job=%s", tenantId, jobId));
            }
            return deleted;
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to cancel job: " + registryKey, e);
        }
    }

    @Override
    public void pause(String tenantId, String jobId) {
        try {
            scheduler.pauseJob(jobKey(tenantId, jobId));
            String key = registryKey(tenantId, jobId);
            ScheduledJob existing = jobRegistry.get(key);
            if (existing != null) {
                jobRegistry.put(key, existing.withEnabled(false));
            }
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to pause job", e);
        }
    }

    @Override
    public void resume(String tenantId, String jobId) {
        try {
            scheduler.resumeJob(jobKey(tenantId, jobId));
            String key = registryKey(tenantId, jobId);
            ScheduledJob existing = jobRegistry.get(key);
            if (existing != null) {
                jobRegistry.put(key, existing.withEnabled(true));
            }
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to resume job", e);
        }
    }

    @Override
    public List<ScheduledJob> listByTenant(String tenantId) {
        return jobRegistry.values().stream()
                .filter(j -> j.tenantId().equals(tenantId))
                .toList();
    }

    @Override
    public Optional<ScheduledJob> getJob(String tenantId, String jobId) {
        return Optional.ofNullable(jobRegistry.get(registryKey(tenantId, jobId)));
    }

    @Override
    public int cancelAllByTenant(String tenantId) {
        try {
            Set<JobKey> keys = scheduler.getJobKeys(GroupMatcher.jobGroupEquals(tenantId));
            for (JobKey key : keys) {
                scheduler.deleteJob(key);
                jobRegistry.remove(registryKey(tenantId, key.getName()));
            }
            return keys.size();
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to cancel all jobs for tenant: " + tenantId, e);
        }
    }

    /**
     * Para o scheduler. Deve ser chamado no shutdown da aplicação.
     */
    public void shutdown() {
        try {
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdown(true);
            }
        } catch (SchedulerException e) {
            logger.log(Level.WARNING, "Error shutting down scheduler", e);
        }
    }

    private JobDetail buildJobDetail(ScheduledJob job) {
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(ArchflowQuartzJob.KEY_TENANT_ID, job.tenantId());
        dataMap.put(ArchflowQuartzJob.KEY_JOB_ID, job.jobId());
        dataMap.put(ArchflowQuartzJob.KEY_CRON, job.cronExpression());
        dataMap.put(ArchflowQuartzJob.KEY_AGENT_ID, job.agentId());
        dataMap.put(ArchflowQuartzJob.KEY_LISTENER, jobListener);
        dataMap.put("payload", new HashMap<>(job.payload()));

        return JobBuilder.newJob(ArchflowQuartzJob.class)
                .withIdentity(jobKey(job.tenantId(), job.jobId()))
                .usingJobData(dataMap)
                .storeDurably(false)
                .build();
    }

    private Trigger buildTrigger(ScheduledJob job) {
        return TriggerBuilder.newTrigger()
                .withIdentity(triggerKey(job.tenantId(), job.jobId()))
                .withSchedule(CronScheduleBuilder.cronSchedule(job.cronExpression()))
                .build();
    }

    private void registerGlobalJobListener() {
        if (deadLetterQueue == null) return;

        try {
            scheduler.getListenerManager().addJobListener(new org.quartz.JobListener() {
                @Override
                public String getName() {
                    return "archflow-dlq-listener";
                }

                @Override
                public void jobToBeExecuted(JobExecutionContext context) {
                    // no-op
                }

                @Override
                public void jobExecutionVetoed(JobExecutionContext context) {
                    // no-op
                }

                @Override
                public void jobWasExecuted(JobExecutionContext context, JobExecutionException exception) {
                    if (exception != null) {
                        JobDataMap dataMap = context.getMergedJobDataMap();
                        String tenantId = dataMap.getString(ArchflowQuartzJob.KEY_TENANT_ID);
                        String jobId = dataMap.getString(ArchflowQuartzJob.KEY_JOB_ID);

                        ScheduledJob failedJob = jobRegistry.get(registryKey(tenantId, jobId));
                        if (failedJob != null) {
                            deadLetterQueue.enqueue(failedJob, exception);
                            logger.warning(() -> String.format(
                                    "Job sent to DLQ: tenant=%s, job=%s, error=%s",
                                    tenantId, jobId, exception.getMessage()));
                        }
                    }
                }
            });
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to register DLQ listener", e);
        }
    }

    private static JobKey jobKey(String tenantId, String jobId) {
        return new JobKey(jobId, tenantId);
    }

    private static TriggerKey triggerKey(String tenantId, String jobId) {
        return new TriggerKey(jobId + "-trigger", tenantId);
    }

    private static String registryKey(String tenantId, String jobId) {
        return tenantId + ":" + jobId;
    }

    private static Scheduler createDefaultScheduler() {
        try {
            Properties props = new Properties();
            props.setProperty("org.quartz.scheduler.instanceName", "ArchflowScheduler");
            props.setProperty("org.quartz.threadPool.threadCount", "4");
            props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
            return new StdSchedulerFactory(props).getScheduler();
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to create Quartz scheduler", e);
        }
    }
}
