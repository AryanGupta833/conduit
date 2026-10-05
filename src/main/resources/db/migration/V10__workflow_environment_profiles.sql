CREATE TABLE IF NOT EXISTS workflow_environment_profiles (
    id BIGSERIAL PRIMARY KEY,
    workflow_id BIGINT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    values_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_environment_profile_workflow_name UNIQUE (workflow_id, name)
);

ALTER TABLE workflows ADD COLUMN IF NOT EXISTS environment_profile_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_environment_profile_workflow ON workflow_environment_profiles (workflow_id);
