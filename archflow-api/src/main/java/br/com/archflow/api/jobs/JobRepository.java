package br.com.archflow.api.jobs;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.Set;

/** Persistence contract whose mutations return the resulting durable snapshot. */
public interface JobRepository {

    JobRecord create(JobRecord job);

    JobRecord find(String tenantId, String id);

    /** Internal worker lookup; never expose this without a tenant check. */
    JobRecord findById(String id);

    JobRecord findByIdempotencyKey(String tenantId, String type, String key);

    List<JobRecord> list(String tenantId, String type);

    JobRecord claimNext(String workerId, Set<String> supportedTypes, Duration lease);

    default JobRecord claimNext(String workerId) {
        return claimNext(workerId, Set.of(), Duration.ofSeconds(30));
    }

    boolean heartbeat(String id, String workerId, Duration lease);

    int recoverExpiredLeases();

    List<JobAttempt> attempts(String tenantId, String jobId);

    JobRecord updateProgress(String id, int progress, String message);

    JobRecord complete(String id);

    JobRecord fail(String id, String error);

    JobRecord requestCancel(String tenantId, String id);

    /**
     * Devolve à fila um job terminal que falhou ou foi cancelado, com {@code additionalAttempts}
     * tentativas a mais e {@code payload} no lugar do anterior. É condicional ao estado: devolve o
     * job reaberto, ou {@code null} quando nada mudou (job inexistente, de outro tenant ou fora de
     * FAILED/CANCELLED, por exemplo porque outro chamador já o reabriu). O chamador precisa saber
     * se foi ele quem reabriu; um job "como está" não diz isso. Mantém o id e a chave de
     * idempotência, que é única por tenant e tipo; o histórico de {@code job_attempts} continua
     * numerado a partir da última tentativa.
     */
    default JobRecord reopen(String tenantId, String id, int additionalAttempts, Map<String, Object> payload) {
        return reopen(tenantId, id, additionalAttempts, payload,
                java.util.Set.of(JobStatus.FAILED, JobStatus.CANCELLED));
    }

    /**
     * Como {@link #reopen(String, String, int, Map)}, reabrindo só um job num dos estados de
     * {@code from} (todos terminais). O {@code SUCCEEDED} entra para o resultado que deixou de valer:
     * o documento cujo índice foi desfeito por um rollback (RCHFLW-157). A condição no estado continua
     * decidindo quem reabriu.
     */
    JobRecord reopen(String tenantId, String id, int additionalAttempts, Map<String, Object> payload,
                     java.util.Set<JobStatus> from);
}
