CREATE TABLE conduit_users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(24) NOT NULL CHECK (role IN ('ADMIN', 'BUILDER', 'OPERATOR', 'VIEWER')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    token_version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_conduit_user_email UNIQUE (email)
);

CREATE TABLE conduit_refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES conduit_users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL,
    family_id UUID NOT NULL,
    family_expires_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash)
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
