ALTER TABLE conduit_users ADD COLUMN IF NOT EXISTS last_totp_counter BIGINT;
