ALTER TABLE jobs ADD COLUMN lease_until TIMESTAMP;

CREATE TABLE job_attempts (
    job_id       VARCHAR(64)  NOT NULL,
    attempt_no   INTEGER      NOT NULL,
    worker_id    VARCHAR(128) NOT NULL,
    started_at   TIMESTAMP    NOT NULL,
    completed_at TIMESTAMP,
    outcome      VARCHAR(32),
    error        TEXT,
    PRIMARY KEY (job_id, attempt_no),
    CONSTRAINT fk_job_attempt_job FOREIGN KEY (job_id) REFERENCES jobs (id)
);

CREATE INDEX idx_jobs_expired_lease ON jobs (status, lease_until);
