# Conduit backup and restore runbook

This runbook describes the state that must be backed up together. It is an operational checklist, not an automated disaster-recovery guarantee. Practice restore into an isolated environment before relying on a backup.

## Protect and back up

1. Put PostgreSQL into the normal consistent backup process. A logical backup can be created with `pg_dump --format=custom --file=conduit.dump "$CONDUIT_DB_URL"`; use a protected shell/session and keep the dump encrypted at rest.
2. Back up the exact values of `CONDUIT_CREDENTIALS_ENCRYPTION_KEY`, `CONDUIT_JWT_SECRET`, and (if enabled) `CONDUIT_MFA_ENCRYPTION_KEY` in a separate encrypted secrets manager. Never put these keys beside an unencrypted database dump. Losing the credential encryption key makes stored workflow credentials unreadable; changing the JWT key invalidates issued access tokens; losing the MFA key requires account recovery/re-enrollment.
3. Back up deployment configuration without embedding secret values, custom plugin JARs and their checksums, and any managed workspace files that are business artifacts. Workspace paths are deployment-specific and are not a replacement for durable S3/object storage.
4. Record the Conduit release, Flyway schema version, PostgreSQL version, Redis stream/group names, Kafka trigger topic/group configuration, and external integration resource identifiers with the backup metadata.
5. Retain backups with access controls, encryption, and a tested retention policy. Verify the dump is readable and periodically run a restore drill.

## Restore into an isolated environment

1. Provision PostgreSQL, Redis, Kafka, and required object storage. Restore the matching database snapshot with `pg_restore --clean --if-exists --dbname "$CONDUIT_DB_URL" conduit.dump` only against the explicitly selected recovery database. Do not point this command at a live production database during a drill.
2. Restore the matching encryption/signing keys from the protected secret backup before starting Conduit. Preserve the credential encryption key exactly; do not generate a replacement for an existing database.
3. Deploy the matching Conduit release and allow Flyway to validate/apply only the expected forward migrations. Review startup output and schema version before opening traffic.
4. Redis is queue/dispatch state, not the execution-context authority. Inspect pending deliveries and the PostgreSQL outbox, then use Conduit's recovery/queue mechanisms to reconcile durable task state. Do not manually mark task executions successful to clear a queue.
5. Kafka trigger redelivery and external plugin side effects can repeat around a recovery boundary. Keep consumers idempotent, inspect pending outbox/trigger inbox records, and avoid replaying side-effecting executions until an operator has reviewed them.
6. Verify account login, workflow/version visibility, execution context, human-task state, queue operations, and a deliberately chosen non-production workflow. Confirm credentials resolve without displaying their values. Re-enable external triggers and production traffic only after the checks pass.

## Recovery boundary

PostgreSQL stores workflow definitions, executions, task results, context, outbox records, and trigger deduplication state. Redis Streams can be rebuilt/recovered from that durable application state and consumer-group pending data; they are not the authority for committed task outputs. External systems are outside the PostgreSQL transaction, so a restore cannot undo a side effect that already reached AWS, a webhook, Kafka, or another provider.

The repository does not currently automate backup scheduling, immutable off-site retention, restore verification, or recovery-time/recovery-point objectives. Operators must supply and test those controls for their deployment.
