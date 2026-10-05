-- Current schema for brand-new installations. Flyway records baseline version
-- 12 after this migration; existing installations continue through V1..V12.
CREATE TABLE workflows (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255),
    status VARCHAR(255),
    created_at TIMESTAMP,
    cron_expression VARCHAR(255),
    active BOOLEAN,
    environment_profile_id BIGINT,
    last_scheduled_run TIMESTAMP
);
CREATE INDEX idx_workflows_active_cron ON workflows(id)
    WHERE active = TRUE AND cron_expression IS NOT NULL AND cron_expression <> '';

CREATE TABLE workflow_versions (
    id BIGSERIAL PRIMARY KEY,
    workflow_id BIGINT REFERENCES workflows(id),
    version_number INTEGER,
    published BOOLEAN,
    latest BOOLEAN,
    created_at TIMESTAMP,
    published_at TIMESTAMP,
    UNIQUE(workflow_id, version_number)
);

CREATE TABLE task_nodes (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255),
    workflow_version_id BIGINT REFERENCES workflow_versions(id),
    timeout_seconds INTEGER,
    max_retries INTEGER,
    join_condition VARCHAR(255),
    plugin_type VARCHAR(255),
    configuration_json TEXT,
    x_position DOUBLE PRECISION,
    y_position DOUBLE PRECISION,
    display_name VARCHAR(255),
    UNIQUE(workflow_version_id, name)
);

CREATE TABLE dependencies (
    id BIGSERIAL PRIMARY KEY,
    parent_task_id BIGINT REFERENCES task_nodes(id),
    child_task_id BIGINT REFERENCES task_nodes(id),
    condition VARCHAR(255),
    expression VARCHAR(255),
    UNIQUE(parent_task_id, child_task_id)
);

CREATE TABLE workflow_executions (
    id BIGSERIAL PRIMARY KEY,
    workflow_version_id BIGINT REFERENCES workflow_versions(id),
    status VARCHAR(255),
    version_number INTEGER,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    initiated_by VARCHAR(254),
    CONSTRAINT workflow_executions_status_check CHECK (status IN ('PENDING','RUNNING','SUCCESS','FAILED','PAUSED','CANCELLED','WAITING_FOR_HUMAN'))
);

CREATE TABLE task_executions (
    id BIGSERIAL PRIMARY KEY,
    workflow_execution_id BIGINT REFERENCES workflow_executions(id),
    task_node_id BIGINT REFERENCES task_nodes(id),
    status VARCHAR(255),
    retry_count INTEGER,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    resolved_configuration_json TEXT,
    CONSTRAINT task_executions_status_check CHECK (status IN ('PENDING','RUNNING','SUCCESS','FAILED','RETRYING','SKIPPED','TIMEOUT','QUEUED','WAITING_FOR_HUMAN'))
);

CREATE TABLE execution_contexts (
    id BIGSERIAL PRIMARY KEY,
    workflow_execution_id BIGINT NOT NULL UNIQUE REFERENCES workflow_executions(id),
    variable_json TEXT,
    version BIGINT
);

CREATE TABLE execution_logs (
    id BIGSERIAL PRIMARY KEY,
    timestamp TIMESTAMP,
    level VARCHAR(255),
    message TEXT,
    task_exxecution_id BIGINT REFERENCES task_executions(id)
);

CREATE TABLE idempotency_records (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    result_json TEXT,
    created_at TIMESTAMP,
    completed_at TIMESTAMP,
    lease_until TIMESTAMP,
    lease_token UUID NOT NULL,
    CONSTRAINT uk_idempotency_key UNIQUE(idempotency_key)
);

CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    last_error TEXT
);
CREATE INDEX idx_outbox_pending_id ON outbox_events(id) WHERE status = 'PENDING';

CREATE TABLE workflow_trigger_inbox (
    event_id UUID PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    execution_id BIGINT REFERENCES workflow_executions(id),
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_workflow_trigger_inbox_workflow_id ON workflow_trigger_inbox(workflow_id);

CREATE TABLE human_task_requests (
    id BIGSERIAL PRIMARY KEY,
    task_execution_id BIGINT NOT NULL UNIQUE REFERENCES task_executions(id),
    workflow_execution_id BIGINT NOT NULL REFERENCES workflow_executions(id),
    status VARCHAR(32) NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(300) NOT NULL,
    description TEXT,
    assignee VARCHAR(200),
    request_payload TEXT,
    response_payload TEXT,
    created_by VARCHAR(200),
    completed_by VARCHAR(200),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    completed_at TIMESTAMP,
    timeout_policy VARCHAR(16) NOT NULL DEFAULT 'FAIL'
);
CREATE INDEX idx_human_task_status ON human_task_requests(status);
CREATE INDEX idx_human_task_assignee ON human_task_requests(assignee);
CREATE INDEX idx_human_task_workflow ON human_task_requests(workflow_execution_id);
CREATE INDEX idx_human_task_execution ON human_task_requests(task_execution_id);
CREATE INDEX idx_human_task_expires ON human_task_requests(expires_at);

CREATE TABLE execution_notifications (
    id BIGSERIAL PRIMARY KEY,
    event_key VARCHAR(160) NOT NULL UNIQUE,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(300) NOT NULL,
    message TEXT,
    workflow_execution_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP
);
CREATE INDEX idx_execution_notification_created ON execution_notifications(created_at DESC);

CREATE TABLE workflow_environment_profiles (
    id BIGSERIAL PRIMARY KEY,
    workflow_id BIGINT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    values_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_environment_profile_workflow_name UNIQUE(workflow_id, name)
);
CREATE INDEX idx_environment_profile_workflow ON workflow_environment_profiles(workflow_id);

CREATE TABLE workflow_credentials (
    id BIGSERIAL PRIMARY KEY,
    workflow_id BIGINT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    secret_ciphertext TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_workflow_credential_name UNIQUE(workflow_id, name)
);
CREATE INDEX idx_workflow_credentials_workflow ON workflow_credentials(workflow_id);

CREATE TABLE conduit_users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(24) NOT NULL CHECK(role IN ('ADMIN','BUILDER','OPERATOR','VIEWER')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    token_version INTEGER NOT NULL DEFAULT 0,
    totp_secret_ciphertext TEXT,
    pending_totp_secret_ciphertext TEXT,
    mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    recovery_codes_json TEXT NOT NULL DEFAULT '[]',
    last_totp_counter BIGINT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_conduit_user_email UNIQUE(email)
);

CREATE TABLE conduit_refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES conduit_users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    family_id UUID NOT NULL,
    family_expires_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    CONSTRAINT uk_refresh_token_hash UNIQUE(token_hash)
);
CREATE INDEX idx_refresh_token_family ON conduit_refresh_tokens(family_id);
CREATE INDEX idx_refresh_token_family_expiry ON conduit_refresh_tokens(family_expires_at);
CREATE INDEX idx_refresh_token_user_active ON conduit_refresh_tokens(user_id, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE security_audit_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    actor VARCHAR(254) NOT NULL,
    target VARCHAR(254),
    source_ip VARCHAR(64),
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_security_audit_created ON security_audit_events(created_at);
CREATE INDEX idx_security_audit_actor ON security_audit_events(actor);
ALTER TABLE workflow_executions ADD COLUMN initiated_by VARCHAR(254);

CREATE TABLE conduit_login_rate_limits (
    bucket_key CHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMP NOT NULL,
    attempt_count INTEGER NOT NULL
);
CREATE INDEX idx_login_rate_limit_window ON conduit_login_rate_limits(window_started_at);
