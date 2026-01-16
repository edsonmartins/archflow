-- ============================================================================
-- Archflow Audit Log Table
-- ============================================================================
-- This table stores audit events for security and compliance tracking.
-- Supports:
--   - Authentication/Authorization events
--   - CRUD operations on resources
--   - Workflow, Agent, Tool, LLM executions
--   - Security events (access denied, suspicious activity)
-- ============================================================================

-- Create audit log table
CREATE TABLE af_audit_log (
    -- Primary key
    id                   VARCHAR(36) PRIMARY KEY,

    -- Event metadata
    timestamp            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    action_code          VARCHAR(50) NOT NULL,
    success              BOOLEAN NOT NULL DEFAULT TRUE,

    -- Actor information
    user_id              VARCHAR(100),
    username             VARCHAR(100),

    -- Resource information
    resource_type        VARCHAR(50),
    resource_id          VARCHAR(255),

    -- Error details
    error_message        TEXT,

    -- Request context
    ip_address           VARCHAR(45),
    user_agent           VARCHAR(500),
    session_id           VARCHAR(100),
    trace_id             VARCHAR(64),

    -- Additional context (JSON)
    context              TEXT,

    -- Audit metadata
    created_at           TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- Indexes for common queries
    INDEX idx_audit_timestamp (timestamp DESC),
    INDEX idx_audit_user (user_id, timestamp DESC),
    INDEX idx_audit_action (action_code, timestamp DESC),
    INDEX idx_audit_resource (resource_type, resource_id, timestamp DESC),
    INDEX idx_audit_trace (trace_id),
    INDEX idx_audit_success (success, timestamp DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Add table comment
ALTER TABLE af_audit_log COMMENT = 'Audit log for tracking system operations and security events';

-- ============================================================================
-- PostgreSQL variant (commented out - uncomment for PostgreSQL)
-- ============================================================================
/*
CREATE TABLE af_audit_log (
    -- Primary key
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Event metadata
    timestamp            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    action_code          VARCHAR(50) NOT NULL,
    success              BOOLEAN NOT NULL DEFAULT TRUE,

    -- Actor information
    user_id              VARCHAR(100),
    username             VARCHAR(100),

    -- Resource information
    resource_type        VARCHAR(50),
    resource_id          VARCHAR(255),

    -- Error details
    error_message        TEXT,

    -- Request context
    ip_address           VARCHAR(45),
    user_agent           VARCHAR(500),
    session_id           VARCHAR(100),
    trace_id             VARCHAR(64),

    -- Additional context (JSONB)
    context              JSONB,

    -- Audit metadata
    created_at           TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes
CREATE INDEX idx_audit_timestamp ON af_audit_log(timestamp DESC);
CREATE INDEX idx_audit_user ON af_audit_log(user_id, timestamp DESC);
CREATE INDEX idx_audit_action ON af_audit_log(action_code, timestamp DESC);
CREATE INDEX idx_audit_resource ON af_audit_log(resource_type, resource_id, timestamp DESC);
CREATE INDEX idx_audit_trace ON af_audit_log(trace_id);
CREATE INDEX idx_audit_success ON af_audit_log(success, timestamp DESC);

-- Add table comment
COMMENT ON TABLE af_audit_log IS 'Audit log for tracking system operations and security events';
*/

-- ============================================================================
-- Partitioning for high-volume deployments (MySQL 8.0+)
-- ============================================================================
/*
-- Partition by month (optional - for high-volume scenarios)
ALTER TABLE af_audit_log
PARTITION BY RANGE (TO_DAYS(timestamp)) (
    PARTITION p202501 VALUES LESS THAN (TO_DAYS('2025-02-01')),
    PARTITION p202502 VALUES LESS THAN (TO_DAYS('2025-03-01')),
    PARTITION p202503 VALUES LESS THAN (TO_DAYS('2025-04-01')),
    PARTITION p202504 VALUES LESS THAN (TO_DAYS('2025-05-01')),
    PARTITION p202505 VALUES LESS THAN (TO_DAYS('2025-06-01')),
    PARTITION p202506 VALUES LESS THAN (TO_DAYS('2025-07-01')),
    PARTITION pmax VALUES LESS THAN MAXVALUE
);
*/
