# Conduit System Reliability Audit

Audit date: 2026-10-03

## Scope and confidence

This report reflects source inspection of the current checkout and regressions verified in this pass. It is not a production-readiness certification. The full Maven suite passed using the workspace-local repository (425 tests, 10 skipped). Live Kafka, Docker, Kubernetes, and AWS/S3 failure-injection runs are not all covered by this suite. Unauthenticated API access remains excluded from requested fixes.

## Runtime architecture

- Workflow APIs and `WorkflowService` manage workflows. `WorkflowVersionService` creates, publishes, and rolls back versions. Execution selection uses the latest published version; draft versions are not executable.
- `WorkflowGraphService` validates and plans the version DAG. `ExecutionCreationService` transactionally creates the execution, context, and task rows.
- `DistributedWorkflowCoordinator` evaluates dependency/join readiness, skips unreachable descendants, controls completion, and delegates dispatch. Pause, resume, cancellation, and completion coordinate through workflow-row locks and conditional task transitions.
- `TaskDispatchService` claims `PENDING -> QUEUED` and inserts an outbox row transactionally. `OutboxPublisher` claims rows with PostgreSQL `FOR UPDATE SKIP LOCKED`, then sends them to Redis Streams. A crash after Redis append and before the DB status commit can still duplicate delivery.
- `TaskWorker` consumes bounded Redis batches and submits to a bounded executor. Saturated submissions remain pending; recovery claims at most the available batch and stops on executor rejection. `TaskRunnerService` claims a PostgreSQL idempotency lease, renews it, runs a plugin with a timeout, and commits the result guarded by a token. `WorkflowRecoveryService` redispatches RUNNING executions at startup.
- Kafka workflow triggers carry event UUIDs and reserve them in a PostgreSQL inbox in the same transaction as execution creation. Legacy events without IDs cannot be deduplicated.
- Plugins run through `PluginManager` and `PluginContext`; workspace-backed plugins use execution-scoped workspaces. Docker and Kubernetes use dedicated execution services. PostgreSQL is durable state, Redis Streams is task transport, Kafka is trigger transport, and Micrometer publishes bounded-label custom metrics.

## Verified fixes in this pass

### Medium — Redis recovery claimed more stale messages than it returned

`TaskQueueService.recover` limits stale IDs passed to `XCLAIM` to the remaining return-batch capacity. Previously, a page could claim extra entries that were left unprocessed under the recovery consumer and would wait for another idle interval. The regression test is included in the passing full suite.

### High — task SUCCESS preceded output persistence

`TaskResultHandler` now owns task terminal-state persistence and coordinator progression so downstream dispatch does not precede durable result handling. Conflicting terminal overwrites are rejected; redelivery reconciles coordinator work. Timeout results retain TIMEOUT status. Focused and full suite passed.

### Medium — logging/release failure could mask a plugin failure

The task attempt failure handler treats failure logging as secondary and attaches logging/release exceptions as suppressed exceptions to the original failure. This preserves the plugin/attempt failure as the surfaced cause.

### High — deterministic failures consumed retry budget

Configuration, argument/validation, security, and workspace quota failures are classified as permanent; transient failures still use the configured retry policy. Retry delay calculation saturates safely instead of overflowing for large attempt counts. Retry classification and delay tests pass.

### High — invalid workflow edits could bypass version graph rules

New workflows start as draft version 1; publication validates that the graph is nonempty and version-local. Task/dependency mutations lock the owning workflow, require the latest draft, reject duplicate names/edges and self/cross-version edges, and recheck DAG cycles after updates. Flyway V4/V5 add uniqueness constraints after checking existing duplicates. Workflow/task/dependency tests pass.

### Medium — concurrent task outputs could overwrite shared context

Context variable updates now use a pessimistic DB row lock for serialized read/modify/write, removing the catch-and-retry pattern that could run inside a rollback-only transaction. Regression tests pass.

### Medium — workspace quota scans could fail open or scale poorly

Unreadable files now fail the quota check rather than being counted as zero bytes. Atomic writes count current usage once and enforce a cumulative byte cap while streaming. Workspace tests pass.

### Medium — coordinator outages could be misreported as plugin failures

Plugin execution success is no longer marked before result handling. A failure during workflow progression leaves the Redis delivery pending for recovery; duplicate terminal delivery can reconcile the coordinator without rewriting a completed result. Worker/result-handler tests pass.

## Reliability semantics and unresolved findings

- **Idempotency:** database lease acquisition and token-guarded completion prevent two valid owners from committing the same idempotency record concurrently. A crash after an external side effect but before durable result commit can cause that side effect to repeat after lease expiry. Arbitrary external effects are therefore at-least-once unless the external service/plugin accepts and enforces an idempotency key.
- **Outbox:** state change plus outbox insertion is transactional. Publishers claim pending rows with PostgreSQL `FOR UPDATE SKIP LOCKED`; concurrent instances claim different rows. Redis publication and PostgreSQL status update are not one transaction, so a crash after Redis append can still produce a duplicate on retry. Malformed/unsupported events are marked `FAILED` with an error message so they do not block later rows. PostgreSQL claim-concurrency and service tests passed.
- **Kafka:** UUID inbox deduplicates events with IDs. Legacy events without an ID remain non-deduplicated. End-to-end Kafka redelivery, malformed payload handling, retries, and offset behavior have not been validated against a running broker in this pass.
- **Retries:** `maxRetries` yields up to one initial attempt plus configured additional attempts. Deterministic configuration/validation/security/quota failures are non-retryable. External effects can still repeat if the plugin has performed them before a crash and the local result has not been made durable.
- **Scheduling:** `WorkflowCronScheduler` polls active cron workflows. It locks the workflow row, creates an execution from the latest published version, and advances `lastScheduledRun` in the same transaction. Multiple instances serialize on that row, so one occurrence cannot create two executions. Missed occurrences are caught up one per scheduler pass; timestamps use the application/JVM local time zone because workflow cron has no stored time-zone field. Recovery after a committed execution but before async dispatch uses the existing startup recovery for RUNNING executions. Cron claim unit tests were added; test execution remains pending.
- **Security:** `SecurityConfig` currently permits every request and disables CSRF. Authentication and authorization are not implemented; do not expose this API to untrusted networks.
- **Workspace:** paths are execution-scoped and checked against the configured root. Cleanup checks persisted status, a local quota lock, and a filesystem lock; cross-process active-use/cleanup races and Windows semantics need dedicated integration tests.
- **Docker/Kubernetes:** source contains timeout/cleanup and managed mount/job paths, but daemon/cluster unavailable, orphan cleanup, privilege boundaries, timeout, and cleanup failure cases were not run here.
- **Metrics/health:** custom metrics use bounded categories; stream length and consumer pending are separate measures. Deployment readiness expectations and broker failure health behavior need environment-backed verification.
- **External plugins:** HTTP/webhook, Kafka producer, SQL, Redis, Git, Docker/Kubernetes, and S3 may perform an external effect before local success is durable. Those boundaries are not exactly-once. Require external idempotency keys or document repeat semantics per operation.

## Scorecard

| Area | Status | Finding / evidence |
|---|---|---|
| Database and migrations | PASS WITH LIMITATION | PostgreSQL integration tests ran; Flyway validated five migrations. Existing production-schema upgrade still requires deployment-like validation. |
| Idempotency | PASS WITH LIMITATION | PostgreSQL lease/token design and existing concurrency tests are present; crash-after-side-effect cannot be made exactly-once generically. |
| Fencing | PASS WITH LIMITATION | Lease completion/release are token-conditional; fresh race suite not run here. |
| Redis | FIXED WITH LIMITATION | Dead-lettering, group creation failures, ACK/delete semantics, batch-bounded recovery, and saturation handling have unit coverage. Live outage/restart and unbounded DLQ retention remain follow-up concerns. |
| Kafka | PASS WITH LIMITATION | Inbox deduplicates UUID-bearing triggers; broker delivery/redelivery was not exercised. Legacy no-ID messages are not deduplicated. |
| Outbox | FIXED WITH LIMITATION | `FOR UPDATE SKIP LOCKED` claims distinct pending rows; malformed events become FAILED. Redis append/DB commit crash window remains at-least-once. |
| Task dispatch | PASS WITH LIMITATION | Conditional task claim plus transactional outbox; outbox claim concurrency passed locally, while all distributed deployment races are not covered. |
| Task runner | FIXED WITH LIMITATION | Result/coordinator ordering, terminal reconciliation, timeouts, and primary error preservation have regression coverage; generic third-party side effects remain at-least-once. |
| Retries | FIXED WITH LIMITATION | Permanent deterministic failures do not consume retry budget; external side effect and process restart semantics remain plugin-specific. |
| DAG and completion | PASS WITH LIMITATION | Conditional child dispatch and row-serialized controls exist; concurrent fan-in tests not run here. |
| Worker and saturation | PASS WITH LIMITATION | Bounded executor and rejected-delivery recovery exist; saturation behavior not re-run in this pass. |
| Schedulers | FIXED WITH LIMITATION | Cron occurrences are transactionally claimed under workflow row lock. Other periodic recovery/cleanup jobs remain local and rely on idempotent operations. |
| Workspace | PASS WITH LIMITATION | Root confinement and cleanup safeguards inspected; cross-process and Windows race tests unavailable. |
| Docker | SKIPPED | No daemon failure/timeout/security tests performed. |
| Kubernetes | SKIPPED | No cluster tests performed. |
| Plugins / AWS-S3 | PASS WITH LIMITATION | Prior plugin audit exists; side effects remain at-least-once absent external idempotency; AWS failure tests not run. |
| Observability | PASS WITH LIMITATION | Bounded metric categories inspected; live scrape correctness not exercised. |
| Health | PASS WITH LIMITATION | Actuator exposes health/prometheus; readiness contract under dependency outages not verified. |
| API and security | IMPLEMENTED WITH LIMITATIONS | First-party account/password login, short-lived JWT access tokens, rotating refresh cookies, workflow ownership checks across nested workflow resources, human-task assignment checks, inline-secret rejection/redaction, persistent IP/account login limits, TOTP MFA with one-time recovery codes and persisted replay prevention, serialized last-admin protection, audit events, and metrics-only credentials are implemented. Backend compile and frontend production build passed. Automated security tests, a clean PostgreSQL migration run, and proxy/network validation were not run in this pass; this is not a production certification. |
| Resource management | PASS WITH LIMITATION | Worker/attempt executors are bounded; ignored plugin interruption and distributed cleanup need service tests. |
| Failure handling | FIXED WITH LIMITATION | Primary plugin error is preserved over logging/release failure; broader cleanup/logging injection coverage remains. |
| Restart recovery | PASS WITH LIMITATION | Redis pending and RUNNING workflow recovery paths exist; restart simulation was not run. |
| Concurrency | PASS WITH LIMITATION | Existing PostgreSQL race tests are present; full set of races requested was not run in this environment. |

## Test and infrastructure record

- **PASS:** Final full suite — 425 tests, 0 failures, 0 errors, 10 skipped; Maven build succeeded.
- **PASS:** `OutboxEventDeliveryServiceTest` and `WorkflowScheduleClaimServiceTest` — 4 tests passed (delivery state, poison-event handling, due occurrence creation/cursor update, and not-due behavior).
- **PASS:** `OutboxClaimPostgresConcurrencyTest` — 1 test passed against PostgreSQL 18.4; two concurrent transactions using `SKIP LOCKED` claimed different pending rows.
- **PASS:** `TaskQueueServiceRecoveryTest#shouldNotClaimMoreStaleMessagesThanRecoveryBatchCanReturn` — 1 regression test passed.
- **PASS:** `TaskRunnerServiceConfigurationTest` — 1 test passed, verifying output persistence happens before the SUCCESS transition.
- **PASS:** `mvn -Dmaven.repo.local=target/m2repo -DskipTests compile`.
- Full suite initially identified two obsolete inline-processing expectations in `TaskRecoveryServiceTest`; updated assertions now verify asynchronous submission and saturation stopping the batch. Focused worker/recovery tests passed (15 tests) before the final green full suite.
- PostgreSQL integration/concurrency used local `conduit_tests` (PostgreSQL 18.4); Flyway validated all five migrations. Kafka broker delivery, Docker/Kubernetes, and AWS/S3 infrastructure failure tests were not run for this report.
- Test configuration disables listener startup, scheduled polling, and Redis health aggregation only in `src/test/resources/application.properties`; production scheduling and dependency health remain enabled. Loopback- and Redis-dependent tests skip when those services cannot be reached in the test environment.
- The Redis plugin’s temporary fallback Lettuce factory is destroyed after each execution. A cleanup regression plus the full `RedisPluginTest` class passed (10 tests).

## Remaining work before production readiness

1. Run security regression tests for role rules, cross-account workflow access, login throttling, MFA/recovery/replay, token revocation, and secret redaction; validate ownership filtering with all nested API routes.
2. Apply B12/V13/V14/V15 against an isolated empty PostgreSQL database and V13/V14/V15 against a representative pre-existing V12 schema, then verify Hibernate schema validation.
3. Test Redis/Kafka outage recovery, worker restart, retry restart, multi-instance scheduler recovery, and concurrent DAG fan-in under deployment-like conditions.
4. Validate reverse-proxy client-IP handling, HTTPS/cookie policy, CORS origins, JWT/MFA/credential key backup and rotation, and database backups in the deployment environment.
3. Run Docker/Kubernetes/S3 failure tests with real services and validate Flyway V4/V5 against a production-like database containing representative legacy data.
4. Bound/monitor the dead-letter stream and exercise retention/repair procedures operationally.
