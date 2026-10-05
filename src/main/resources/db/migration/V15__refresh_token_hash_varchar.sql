-- Hibernate maps RefreshToken.tokenHash as VARCHAR(64). V12 historically
-- created this column as fixed-width CHAR(64), which fails ddl-auto=validate.
ALTER TABLE conduit_refresh_tokens
    ALTER COLUMN token_hash TYPE VARCHAR(64)
    USING rtrim(token_hash);
