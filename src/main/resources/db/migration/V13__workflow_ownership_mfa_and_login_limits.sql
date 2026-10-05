ALTER TABLE workflows ADD COLUMN IF NOT EXISTS owner_id BIGINT REFERENCES conduit_users(id);
CREATE INDEX IF NOT EXISTS idx_workflows_owner ON workflows(owner_id);

ALTER TABLE conduit_users ADD COLUMN IF NOT EXISTS totp_secret_ciphertext TEXT;
ALTER TABLE conduit_users ADD COLUMN IF NOT EXISTS pending_totp_secret_ciphertext TEXT;
ALTER TABLE conduit_users ADD COLUMN IF NOT EXISTS mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE conduit_users ADD COLUMN IF NOT EXISTS recovery_codes_json TEXT NOT NULL DEFAULT '[]';

CREATE TABLE IF NOT EXISTS conduit_login_rate_limits (
    bucket_key CHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMP NOT NULL,
    attempt_count INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_login_rate_limit_window ON conduit_login_rate_limits(window_started_at);
