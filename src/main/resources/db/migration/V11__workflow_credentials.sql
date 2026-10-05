CREATE TABLE workflow_credentials (
    id BIGSERIAL PRIMARY KEY,
    workflow_id BIGINT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    secret_ciphertext TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_workflow_credential_name UNIQUE (workflow_id, name)
);

CREATE INDEX idx_workflow_credentials_workflow ON workflow_credentials(workflow_id);
