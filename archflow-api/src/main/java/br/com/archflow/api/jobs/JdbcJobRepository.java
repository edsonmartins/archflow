package br.com.archflow.api.jobs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JDBC job repository with conditional claiming and durable lifecycle state. */
public class JdbcJobRepository implements JobRepository {

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcJobRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public JobRecord create(JobRecord job) {
        if (job.idempotencyKey() != null) {
            JobRecord existing = findByIdempotencyKey(
                    job.tenantId(), job.type(), job.idempotencyKey());
            if (existing != null) return existing;
        }
        String sql = """
                INSERT INTO jobs
                    (id, tenant_id, workspace_id, type, status, payload, progress,
                     message, attempt, max_attempts, idempotency_key, cancel_requested,
                     worker_id, error, created_at, started_at, completed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            bind(statement, job);
            statement.executeUpdate();
            return job;
        } catch (Exception duplicate) {
            if (job.idempotencyKey() != null) {
                JobRecord existing = findByIdempotencyKey(
                        job.tenantId(), job.type(), job.idempotencyKey());
                if (existing != null) return existing;
            }
            throw new RuntimeException("Failed to create job " + job.id(), duplicate);
        }
    }

    @Override
    public JobRecord find(String tenantId, String id) {
        return queryOne("SELECT * FROM jobs WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    @Override
    public JobRecord findByIdempotencyKey(String tenantId, String type, String key) {
        if (key == null) return null;
        return queryOne("""
                SELECT * FROM jobs
                 WHERE tenant_id = ? AND type = ? AND idempotency_key = ?
                """, tenantId, type, key);
    }

    @Override
    public List<JobRecord> list(String tenantId, String type) {
        String sql = type == null
                ? "SELECT * FROM jobs WHERE tenant_id = ? ORDER BY created_at DESC"
                : "SELECT * FROM jobs WHERE tenant_id = ? AND type = ? ORDER BY created_at DESC";
        List<JobRecord> result = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            if (type != null) statement.setString(2, type);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(map(rows));
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list jobs", e);
        }
    }

    @Override
    public JobRecord claimNext(String workerId, Set<String> supportedTypes, Duration lease) {
        for (int attempt = 0; attempt < 3; attempt++) {
            JobRecord candidate = queryOne(
                    claimSelect(supportedTypes), supportedTypes.toArray());
            if (candidate == null) return null;
            if (candidate.cancelRequested()) {
                requestCancel(candidate.tenantId(), candidate.id());
                continue;
            }
            Instant now = Instant.now();
            String sql = """
                    UPDATE jobs SET status='RUNNING', attempt=attempt+1, worker_id=?,
                           started_at=COALESCE(started_at, ?), lease_until=?, updated_at=?
                     WHERE id=? AND status='QUEUED' AND cancel_requested=FALSE
                    """;
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(sql)) {
                statement.setString(1, workerId);
                statement.setTimestamp(2, Timestamp.from(now));
                statement.setTimestamp(3, Timestamp.from(now.plus(lease)));
                statement.setTimestamp(4, Timestamp.from(now));
                statement.setString(5, candidate.id());
                if (statement.executeUpdate() == 1) {
                    JobRecord claimed = find(candidate.tenantId(), candidate.id());
                    insertAttempt(claimed, workerId, now);
                    return claimed;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to claim job", e);
            }
        }
        return null;
    }

    @Override
    public boolean heartbeat(String id, String workerId, Duration lease) {
        return executeCount("""
                UPDATE jobs SET lease_until=?, updated_at=?
                 WHERE id=? AND worker_id=? AND status='RUNNING'
                """, Timestamp.from(Instant.now().plus(lease)), Timestamp.from(Instant.now()),
                id, workerId) == 1;
    }

    @Override
    public int recoverExpiredLeases() {
        Instant now = Instant.now();
        List<JobRecord> expired = queryMany("""
                SELECT * FROM jobs
                 WHERE status='RUNNING' AND lease_until < ?
                """, Timestamp.from(now));
        int recovered = 0;
        for (JobRecord job : expired) {
            if (executeCount("""
                    UPDATE jobs SET status='QUEUED', worker_id=NULL, lease_until=NULL,
                        message='Recovered after worker lease expired',
                        error='Worker lease expired', updated_at=?
                     WHERE id=? AND status='RUNNING' AND lease_until < ?
                    """, Timestamp.from(now), job.id(), Timestamp.from(now)) == 1) {
                finishAttempt(job.id(), job.attempt(), JobStatus.FAILED,
                        "Worker lease expired");
                recovered++;
            }
        }
        return recovered;
    }

    @Override
    public List<JobAttempt> attempts(String tenantId, String jobId) {
        if (find(tenantId, jobId) == null) return List.of();
        List<JobAttempt> result = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT * FROM job_attempts WHERE job_id=? ORDER BY attempt_no
                     """)) {
            statement.setString(1, jobId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String outcome = rows.getString("outcome");
                    result.add(new JobAttempt(jobId, rows.getInt("attempt_no"),
                            rows.getString("worker_id"), instant(rows, "started_at"),
                            instant(rows, "completed_at"),
                            outcome == null ? null : JobStatus.valueOf(outcome),
                            rows.getString("error")));
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list job attempts", e);
        }
    }

    @Override
    public JobRecord updateProgress(String id, int progress, String message) {
        execute("""
                UPDATE jobs SET progress=?, message=?, updated_at=?
                 WHERE id=? AND status='RUNNING'
                """, Math.max(0, Math.min(100, progress)), message,
                Timestamp.from(Instant.now()), id);
        return findById(id);
    }

    @Override
    public JobRecord complete(String id) {
        JobRecord job = findById(id);
        if (job == null) return null;
        JobStatus status = job.cancelRequested() ? JobStatus.CANCELLED : JobStatus.SUCCEEDED;
        execute("""
                UPDATE jobs SET status=?, progress=?, message=?, completed_at=?,
                    lease_until=NULL, updated_at=?
                 WHERE id=? AND status='RUNNING'
                """, status.name(), status == JobStatus.SUCCEEDED ? 100 : job.progress(),
                status == JobStatus.SUCCEEDED ? "Completed" : "Cancelled",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
        finishAttempt(id, job.attempt(), status, null);
        return findById(id);
    }

    @Override
    public JobRecord fail(String id, String error) {
        JobRecord job = findById(id);
        if (job == null) return null;
        boolean retry = !job.cancelRequested() && job.attempt() < job.maxAttempts();
        JobStatus status = job.cancelRequested() ? JobStatus.CANCELLED
                : retry ? JobStatus.QUEUED : JobStatus.FAILED;
        execute("""
                UPDATE jobs SET status=?, message=?, error=?, worker_id=?,
                    completed_at=?, lease_until=NULL, updated_at=?
                 WHERE id=? AND status='RUNNING'
                """, status.name(), retry ? "Queued for retry" : status == JobStatus.CANCELLED
                        ? "Cancelled" : "Failed", error, retry ? null : job.workerId(),
                retry ? null : Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
        finishAttempt(id, job.attempt(), status == JobStatus.QUEUED ? JobStatus.FAILED : status, error);
        return findById(id);
    }

    @Override
    public JobRecord requestCancel(String tenantId, String id) {
        JobRecord job = find(tenantId, id);
        if (job == null) return null;
        boolean queued = job.status() == JobStatus.QUEUED;
        execute("""
                UPDATE jobs SET cancel_requested=TRUE, status=?, message=?,
                    completed_at=?, updated_at=?
                 WHERE tenant_id=? AND id=? AND status IN ('QUEUED','RUNNING')
                """, queued ? "CANCELLED" : "RUNNING", "Cancellation requested",
                queued ? Timestamp.from(Instant.now()) : null, Timestamp.from(Instant.now()),
                tenantId, id);
        return find(tenantId, id);
    }

    @Override
    public JobRecord findById(String id) {
        return queryOne("SELECT * FROM jobs WHERE id = ?", id);
    }

    private JobRecord queryOne(String sql, Object... parameters) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? map(rows) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to query job", e);
        }
    }

    private List<JobRecord> queryMany(String sql, Object... parameters) {
        List<JobRecord> result = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(map(rows));
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to query jobs", e);
        }
    }

    private void execute(String sql, Object... parameters) {
        executeCount(sql, parameters);
    }

    private int executeCount(String sql, Object... parameters) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            return statement.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to update job", e);
        }
    }

    private static String claimSelect(Set<String> types) {
        if (types.isEmpty()) {
            return "SELECT * FROM jobs WHERE status='QUEUED' ORDER BY created_at LIMIT 1";
        }
        return "SELECT * FROM jobs WHERE status='QUEUED' AND type IN ("
                + String.join(",", java.util.Collections.nCopies(types.size(), "?"))
                + ") ORDER BY created_at LIMIT 1";
    }

    private void insertAttempt(JobRecord job, String workerId, Instant now) {
        execute("""
                INSERT INTO job_attempts
                    (job_id, attempt_no, worker_id, started_at)
                VALUES (?, ?, ?, ?)
                """, job.id(), job.attempt(), workerId, Timestamp.from(now));
    }

    private void finishAttempt(String jobId, int attempt, JobStatus outcome, String error) {
        execute("""
                UPDATE job_attempts SET completed_at=?, outcome=?, error=?
                 WHERE job_id=? AND attempt_no=? AND completed_at IS NULL
                """, Timestamp.from(Instant.now()), outcome.name(), error, jobId, attempt);
    }

    private void bind(java.sql.PreparedStatement statement, JobRecord job) throws Exception {
        Object[] values = {job.id(), job.tenantId(), job.workspaceId(), job.type(),
                job.status().name(), mapper.writeValueAsString(job.payload()), job.progress(),
                job.message(), job.attempt(), job.maxAttempts(), job.idempotencyKey(),
                job.cancelRequested(), job.workerId(), job.error(),
                Timestamp.from(job.createdAt()), timestamp(job.startedAt()),
                timestamp(job.completedAt()), Timestamp.from(job.updatedAt())};
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    private JobRecord map(ResultSet row) throws Exception {
        Map<String, Object> payload = mapper.readValue(
                row.getString("payload"), new TypeReference<>() {});
        return new JobRecord(row.getString("id"), row.getString("tenant_id"),
                row.getString("workspace_id"), row.getString("type"),
                JobStatus.valueOf(row.getString("status")), payload, row.getInt("progress"),
                row.getString("message"), row.getInt("attempt"), row.getInt("max_attempts"),
                row.getString("idempotency_key"), row.getBoolean("cancel_requested"),
                row.getString("worker_id"), row.getString("error"),
                instant(row, "created_at"), instant(row, "started_at"),
                instant(row, "completed_at"), instant(row, "updated_at"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet row, String name) throws Exception {
        Timestamp value = row.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
