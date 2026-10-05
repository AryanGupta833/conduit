# Workflow platform features

## Preflight validation

`POST /api/workflows/{workflowId}/validate` validates the latest workflow version without executing plugins or external integrations. It checks the DAG, registered plugin types, JSON object configuration, metadata-backed required fields/types/enumerations, and whether referenced workflow credentials exist by name (never returning credential values). Plugins without schemas produce warnings. This does not test credential validity, network access, workspace file existence, or cloud connectivity.

## Environment profiles

Profiles are scoped to a workflow and contain non-secret JSON variables. Select one profile for future runs with `PUT /api/workflows/{workflowId}/environments/selected`; pass `{"profileId": null}` to clear the selection. Runs snapshot the selected values into the PostgreSQL execution context under `variables`, so later profile edits do not change existing executions. Secret-like names are rejected. Store secrets in workflow credentials and use explicit credential-reference objects in plugin configuration.

## Execution inspection and artifacts

`GET /api/executions/{executionId}/tasks/{taskExecutionId}/details` returns the latest expression-resolved inputs and persisted result fields. Sensitive-looking keys, URI user-info, common query-string credentials, and bearer tokens are redacted.

`GET /api/executions/{executionId}/artifacts` lists metadata for regular files beneath the managed execution workspace. It excludes symlinks, hidden files, and host paths, and is bounded to 1,000 entries and eight directory levels. It does not return file content or offer downloads.

## Rerun and comparison

`POST /api/executions/{executionId}/replay` creates a new run using the exact workflow version and snapshotted environment variables from a terminal execution, including versions no longer currently published. The current Kafka trigger event contains only workflow and event IDs, not a separate business payload to restore. Integrations and side effects run again. Use only when that is intended.

`GET /api/executions/{executionId}/compare/{otherExecutionId}` compares task status, retry count, and persisted task result equality by task name without returning either run's output payload.

## Workflow transfer

`GET /api/workflows/{workflowId}/export` exports the latest definition in schema version 1. Sensitive-key values are blanked and URL user-info is redacted. `POST /api/workflows/import` creates a draft from that format. `POST /api/workflows/{workflowId}/clone` creates a copy of the latest definition. Imported/cloned workflows are drafts and require validation and publication before execution.

## Queue operational state

`GET /api/operations/queue` reports configured Redis stream/group names, retained stream entries, pending unacknowledged deliveries, the last delivered record ID, and consumer pending/idle state. Retained entries and pending deliveries are not queue lag. Lag is intentionally not estimated from these values.

## Execution correlation and recovery notes

Each new execution receives a UUID correlation ID. It is returned by execution status and summary APIs, included in durable Redis task messages, and placed in the worker logging MDC (console logs show `correlationId=...`). Older Redis messages without this optional field remain readable. Use the value to connect logs, execution details, and support reports; it is an identifier, not an authorization token.

Dispatch already uses a PostgreSQL outbox before publishing to Redis, and startup recovery reconciles running executions and pending deliveries. PostgreSQL remains the authoritative execution record. This does not make a remote side effect and a local result commit atomic: plugins still need provider-level idempotency where available.

The existing manual preflight is a dry check only: it does not call endpoints or verify live integration access. Existing controls also include exact-version replay/comparison, pause/resume/cancel, workspace quotas, environment snapshots, and Redis queue pending-state inspection. Tenant isolation, per-tenant quotas, queue priority/fairness, end-to-end deadlines/cancellation for all plugin types, retry budgets/circuit-breaker policy enforcement, full execution event history, operator replay from an intermediate step, and documented/automated disaster-recovery drills require separate architecture work and are not implied by workflow validation.

See [Backup and restore](backup-and-restore.md) for the PostgreSQL, key-material, queue-recovery, and restore-drill checklist. The document does not replace deployment-specific backup automation or verified recovery objectives.

## Notifications

`/api/notifications` provides an instance-wide inbox for terminal workflow failures and human-task waits. The current application has no user/recipient identity model, so notifications are shared by the instance and do not have per-user ownership.

## API security and accounts

The API uses first-party account/password authentication and signed JWT bearer access tokens; there is no OAuth sign-in or public registration. The frontend keeps access tokens in memory and uses a rotating opaque refresh token in an `HttpOnly`, `SameSite=Strict` cookie. Access tokens are short-lived (10 minutes by default). Refresh tokens rotate on use, are stored as hashes, and have both a sliding lifetime and a 90-day maximum family age. Reuse of a rotated refresh token revokes that token family. Password/account changes revoke outstanding tokens. Passwords are BCrypt-hashed, failed logins are temporarily locked, and persistent PostgreSQL rate limits apply by source IP and IP/account pair (60 and 8 attempts per 15 minutes respectively). Do not place an untrusted reverse proxy in front of the API without configuring trusted client-IP handling at the proxy/network boundary.

All write endpoints are assigned an explicit minimum role; any new API write endpoint is admin-only until it receives a reviewed role rule. Non-admin accounts can access only workflows they own, including nested versions, tasks, dependencies, executions, artifacts, context, logs, and human tasks; legacy unowned workflows are assigned to the earliest enabled administrator at startup. Human tasks are visible/actionable to their assigned account or an administrator. Inline passwords, tokens, keys, credential values, and credential-bearing URLs are rejected in newly saved/imported plugin configuration and at execution time. Configure secrets with `{"$credential":"name"}` references. Workflow graph responses redact legacy inline secret fields; existing workflows that stored raw secrets need credential migration before their tasks can run again.

Users can enable TOTP MFA from Settings after confirming their password and an authenticator code. Accepted authenticator time-step counters are stored to prevent code replay. Ten single-use recovery codes are issued once; admins can reset a user's MFA after verifying their identity out of band. Set `CONDUIT_MFA_ENCRYPTION_KEY` to a base64-encoded random 32-byte key before allowing enrollment. Back it up and keep it stable: losing it requires administrators to reset/re-enroll affected accounts. The secret is encrypted with AES-GCM at rest; recovery codes are stored as hashes. Enrollment, disable, and admin reset invalidate existing sessions.

Required deployment secrets are `CONDUIT_JWT_SECRET` (base64-encoded random key, at least 64 decoded bytes) and `CONDUIT_CREDENTIALS_ENCRYPTION_KEY` (base64-encoded random 32-byte key). Configure `CONDUIT_MFA_ENCRYPTION_KEY` as a separate random 32-byte key to enable MFA. Set `CONDUIT_BOOTSTRAP_ADMIN_EMAIL` and `CONDUIT_BOOTSTRAP_ADMIN_PASSWORD` only for first startup with an empty account table; startup fails closed if no accounts exist and bootstrap credentials are missing. Remove the bootstrap password from the runtime environment after initialization. There is no public sign-up or self-service password reset; an admin creates accounts and resets passwords. Roles are ADMIN, BUILDER, OPERATOR, and VIEWER. Workflow ownership is per account; administrators can access all workflows.

Flyway V1–V15 upgrade existing installations with migration history. The `B12__conduit_current_schema.sql` baseline migration creates the full schema for a brand-new empty database; Flyway then applies V13 for workflow ownership, MFA fields, and the login rate-limit table, V14 for TOTP replay protection, and V15 to align legacy refresh-token hash column types with Hibernate. Existing non-empty databases without Flyway history retain the configured baseline version 0 behavior and apply V1 onward. Do not manually mark an existing database as fresh or baseline it over unreviewed schema/data.

Set `CONDUIT_ALLOWED_ORIGINS` to a comma-separated exact-origin allowlist. Use HTTPS and keep `CONDUIT_REFRESH_COOKIE_SECURE=true` in production. The default localhost origins and insecure-cookie setting are intended for local development only. Health endpoints expose no details. `/actuator/prometheus` uses a separate `CONDUIT_METRICS_TOKEN` (at least 32 characters) that grants metrics access only; other Actuator endpoints require an admin JWT. Never reuse either operational key as a user password. Rotate the JWT signing key to invalidate all active access tokens and preserve the credential encryption key or stored workflow credentials cannot be decrypted.

## Workflow credentials

Credential values are stored per workflow and encrypted with AES-256-GCM. `GET /api/workflows/{workflowId}/credentials` returns names and timestamps only. Create and rotate endpoints accept plaintext over the authenticated API, but never return it. In plugin configuration, use the exact object form `{"$credential":"awsUpload"}`. The marker remains in saved workflow configuration; Conduit resolves it from that workflow immediately before plugin validation/execution. Credentials are not copied when a workflow is cloned/imported. Only admins can manage credentials, and credential use is audited. Plugins are executable integrations and can transmit configured credentials to their target services; Conduit cannot infer or prevent intentional plugin-level exfiltration.
## Execution reliability additions

- `GET /api/executions/{id}/history` returns the durable lifecycle events recorded for that execution. The endpoint is restricted to the execution owner or an administrator; event details are bounded and task failure text is not copied into the event journal.
- `POST /api/workflows/{id}/execute-with-options` accepts an optional `{"deadlineSeconds": 3600}` body. The deadline is persisted in PostgreSQL, scanned by a scheduled job, and closes the execution as `TIMED_OUT`; the supported range is 1 second through 7 days. A timed-out run can be replayed as a new execution of its original version.
- `PUT /api/workflows/{id}/concurrency` accepts `{"maxConcurrentExecutions": 5}`. PostgreSQL workflow-row locking serializes admission, so simultaneous start requests cannot exceed the configured per-workflow cap. Unconfigured workflows retain the prior unlimited behavior.
- Account-owned workflows also share a locked per-account active-run quota, configured by `CONDUIT_MAX_CONCURRENT_EXECUTIONS_PER_ACCOUNT` (default 25; set to 0 to disable). Owners are serialized during admission so simultaneous starts from separate workflows cannot overrun the quota.
- `PUT /api/workflows/{id}/priority` accepts `{"priority": 5}` in the range -10 to 10. The value is snapshotted with each execution and orders PostgreSQL outbox dispatch; waiting events gain one effective priority point per minute, preventing indefinite starvation of old low-priority work. Already-published Redis FIFO entries are not reordered.
- Flyway V17 adds the event journal, optional deadline, and concurrency cap. V18 adds workflow and outbox priorities. Existing runs have no deadline and existing workflows have no cap and default to normal priority.

HTTP and WEBHOOK plugins receive the stable task execution idempotency key in an `Idempotency-Key` request header. Compatible services can deduplicate retried deliveries. Other plugins can read the same value through `PluginContext.idempotencyKey()`. These controls do not promise exactly-once effects in external systems: a provider must honor the key, and PostgreSQL cannot atomically commit a remote API request.
