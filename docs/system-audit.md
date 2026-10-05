# Conduit System Audit

Audit date: 2026-10-03

## Scope and confidence

This is a code-based audit of the current repository and the fixes verified in this pass. It is not a production-readiness certification: service-backed failure scenarios still require execution against Kafka, Docker, Kubernetes, and S3. Unauthenticated API access remains expressly excluded from the requested fixes.

## Architecture map

### Workflow definition and execution creation

- `WorkflowController` and `WorkflowService` create workflows; `WorkflowVersionService` owns version creation/publishing/rollback.
- New workflows begin at unpublished draft version 1. Versioned executions select a published version by publication status/version number. Draft edits remain editable in the latest version but are not executable until graph validation and publication.
- `WorkflowGraphService` validates and plans the workflow DAG; dependencies connect task nodes within a workflow version.
- `ExecutionCreationService` runs transactionally. It loads the latest version, creates a `WorkflowExecution`, an execution context, and one `TaskExecution` per task. The task idempotency key is `<execution-id>:<task-node-id>` and is protected by a unique database constraint.
- `RuntimeWorkflowExecutor` starts roots and evaluates the legacy in-process execution path. The distributed path uses `DistributedWorkflowCoordinator` and `TaskDispatchService`.

### Distributed task dispatch and recovery

- `TaskDispatchService.dispatch` claims `PENDING -> QUEUED` with a conditional database update, then inserts a `TASK_QUEUE` outbox record in the same transaction.
- `OutboxPublisher` polls pending rows, writes task messages to Redis Streams (`conduit:task-stream`, group `conduit-workers` by default), then marks the outbox event `PUBLISHED`. A crash after Redis publish and before the database commit can publish a duplicate; downstream task idempotency is required, so this is at-least-once delivery rather than exactly-once delivery.
- `TaskWorker` reads batches of at most the configured queue batch size and submits work to a bounded executor. A rejected submission remains pending for recovery. Successful/terminal deliveries are acknowledged; active-lease duplicates are deferred without ACK.
- `TaskRecoveryService` claims stale pending messages after the configured idle interval and submits to the same bounded worker executor. On saturation it stops submitting the batch, leaving unsubmitted Redis records pending for a later recovery pass.
- Malformed Redis stream records are copied to a bounded-field dead-letter stream and acknowledged; ordinary Redis setup failures are surfaced rather than mistaken for an existing group.
- `WorkflowRecoveryService` resumes dispatch for database executions still marked `RUNNING` after application startup.

### Task execution, retries, and fencing

- `TaskRunnerService` first reuses a durable completed idempotency result, otherwise acquires a PostgreSQL-backed lease and fencing token through `IdempotencyService`.
- Lease renewal, completion, and release use conditional updates scoped to the lease token. A stale token cannot complete or release a newer lease. The task result is committed to the idempotency row before task success is persisted; if the worker dies after that commit, a redelivery can reuse the result.
- Plugin attempts run in a bounded, process-wide executor and are cancelled on timeout. Java interruption cannot forcibly stop a plugin that ignores interruption; external backends must enforce their own timeout and cleanup.
- Retry policy is applied by `TaskRunnerService`; configuration, validation, security, and quota failures are classified as permanent. External side effects cannot be made atomic with PostgreSQL by this idempotency table alone.

### State, controls, and dependencies

- `TaskResultHandler` persists terminal task state and delegates dependency and workflow progression to `DistributedWorkflowCoordinator`.
- The coordinator uses conditional `PENDING -> QUEUED` dispatch to prevent multiple queue events from winning for the same task, and propagates skipped tasks through downstream edges.
- `ExecutionControlService` controls pause, resume, and cancellation. The verified pause/resume defect and repair are documented below.

### Integration and execution services

- Kafka trigger path: `WorkflowTriggerProducer` sends an event ID and workflow ID to `conduit.workflow.trigger`; `WorkflowTriggerConsumer` calls `WorkflowTriggerService`. `WorkflowTriggerInboxService` reserves the event ID in PostgreSQL and creates/associates the workflow execution in the same transaction. Duplicate delivery returns the existing execution ID. Legacy messages without an event ID cannot be deduplicated.
- Plugins execute through `PluginManager` and `PluginContext`. File, Git, Docker, and S3 tasks use the per-execution `ExecutionWorkspace` managed by `ExecutionWorkspaceService`; logical workspace paths are confined to that workspace.
- Docker execution uses `DockerExecutionService` / `ManagedWorkspaceMount`; Kubernetes jobs use `KubernetesJobPlugin` and the `KubectlCommandRunner` abstraction.
- PostgreSQL is the durable state store; Redis Streams is the worker transport; Kafka is the workflow trigger transport; Micrometer custom metrics are registered in `ConduitMetrics`.

## Verified issue fixed

### Medium: completion could dispatch children while an execution was paused; resume could not reconcile completions

`DistributedWorkflowCoordinator.handleTaskCompletion` previously returned only for `CANCELLED`, so a completion racing with pause could dispatch downstream tasks. Simply changing that guard would strand already completed tasks because `ExecutionControlService.resume` only changed `PAUSED` to `RUNNING` and did not resume dependency evaluation.

The coordinator now locks the workflow-execution row while processing completion and only dispatches while the workflow is `RUNNING`. Resume is handled transactionally in the coordinator: it locks and transitions the paused execution, rebuilds task statuses, reevaluates children of already terminal tasks, and checks workflow completion. Pause/cancel use the same row lock so these control operations serialize with coordinator completion across application instances. Pausing no longer writes a failure marker to the live workspace.

Regression tests added for (1) no child dispatch while paused, (2) resuming dispatches a child whose parent completed during the pause, and (3) resuming queues roots when pause wins before initial dispatch.

Resume also dispatches still-pending DAG roots, covering a pause that races ahead of the initial root dispatcher.

### High: duplicate Kafka deliveries could create duplicate workflow executions

The trigger event originally contained only `workflowId`, and each consumer invocation unconditionally called execution creation. Kafka redelivery therefore created another execution for the same message. Events now carry a UUID, and migration V2 adds an inbox table with a primary key on that UUID and a foreign key to the created execution on existing databases. A transactional PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` reserves the message before execution creation; duplicate deliveries resolve to the original execution. Producer events with distinct IDs remain distinct intended trigger requests. Legacy payloads lacking `eventId` remain accepted for compatibility and are not deduplicated.

The event inbox closes duplicate execution creation for Kafka redelivery. It does not make arbitrary external effects exactly-once, and duplicate async dispatch of the same execution still relies on task queue claims/idempotency.

### High: a stale worker could persist a lease-loss exception as task failure

`TaskRunnerService` correctly fenced idempotency completion by lease token and raised `TaskLeaseLostException`, but `TaskWorker` treated that exception as an ordinary execution failure. Its generic failure path called `TaskResultHandler`, allowing the old worker to set the task `FAILED` and propagate failure while a newer lease owner was still running. The worker now defers lease-loss deliveries without result propagation or ACK. The same worker path also stops starting queued work after cancellation: `TaskExecutionService` locks the workflow row used by pause/cancel, only claims start while the workflow is `RUNNING`, and marks not-yet-started work `SKIPPED` when cancelled. Paused deliveries remain pending to be retried after resume.

Regression tests cover stale-lease deferral and cancellation-before-start handling. Already-running plugins may still finish after cancellation if they ignore interruption; workflow status remains cancelled and late result handling does not resume downstream dispatch.

### High: execution selected the newest draft instead of the published version

Execution creation and the endpoints described as “latest published” queried only `latest=true`. Creating an unpublished draft marks it latest, so normal execute/plan/stage paths could run or expose draft configuration before publication. Those paths now select the highest-version row with `published=true`, and explicit execution by version rejects unpublished versions. A regression test covers selecting a published version while a newer draft exists and rejecting explicit draft execution.

`WorkflowVersionService.createVersion` also omitted plugin type, plugin configuration, display name, and canvas positions when cloning tasks. New versions now retain those fields, with a regression test.

## Findings requiring service-backed verification or follow-up

- **Task completion atomicity:** task SUCCESS now commits through `TaskResultHandler` with coordinator progression. Terminal redelivery reconciles coordinator work; coordinator failure is not rewritten as plugin failure. Timeout results retain TIMEOUT status.
- **Workflow definition integrity:** publish validates nonempty DAGs and version-local dependencies. Task/dependency mutation is draft-only, serialized on the workflow row, and rejects duplicate task names/edges and cycles. Existing duplicate data is checked before V4/V5 unique constraints are installed.
- **Shared execution context:** updates serialize on a pessimistic DB row lock to avoid lost updates when different task outputs merge concurrently.
- **Worker and scheduler backpressure:** scheduled recovery submits asynchronously to a bounded pool; rejected submissions stay pending. The scheduler pool is explicitly bounded and shuts down gracefully.
- **Workspace quota accounting:** unreadable files no longer count as zero bytes; atomic writes calculate existing usage once and enforce cumulative streaming limits. Process/plugin writes outside the atomic writer remain cooperative quota use.
- **Cron:** active workflows without a published version are skipped. Real multi-instance scheduler load/failure testing remains outstanding.

- **Authentication/authorization:** `SecurityConfig` currently permits every request and disables CSRF. The workflow APIs can mutate and execute workflows without an authenticated principal. There is no existing identity/role model to extend safely, so this remains a production blocker rather than introducing a one-off credential scheme in this audit.
- **Outbox delivery:** database and Redis are not one transaction. Duplicate Redis messages are expected after the publish/commit crash window. The current task idempotency key is the downstream duplicate guard; failure injection should verify this across a real Redis/PostgreSQL pair.
- **Worker recovery and saturation:** bounded task executor and pending-message recovery are present. Real-process tests are still needed for executor saturation, Redis disconnects, ACK failure, and process death around plugin completion.
- **Idempotency side effects:** the lease fences database result writes, but it cannot guarantee an external side effect happened once if the process crashes after the side effect and before durable result commit. Plugins performing such effects need an external idempotency key or documented at-least-once semantics.
- **Workflow completion/cancellation races:** row-level workflow coordination now serializes pause, cancellation, and distributed completion. The task-row transition paths and full multi-process cancellation races still need PostgreSQL-backed concurrency tests.
- **Scheduling:** inspect and test scheduler ownership across multiple app instances before claiming duplicate-schedule protection.
- **Cron scheduling:** a transactional scheduler now creates executions from published versions and advances the scheduling cursor. Time-zone policy remains the application/JVM local zone because workflows do not store a schedule zone.
- **Workspace retention:** cleanup consults PostgreSQL execution status and avoids `RUNNING`/`PAUSED` workspaces; crash, cross-process active-use, and filesystem-lock behavior still need Windows and multi-process tests.
- **Metrics:** current custom metric labels are bounded to plugin type, outcome, backend, and direction. Queue stream length and pending count are different measurements; no claim is made that the current metrics expose consumer-group lag.
- **Configuration/security:** validate deployment-specific CORS, Actuator exposure, database secrets, Docker daemon permissions, Kubernetes service-account privileges, Kafka error handling, and connection pool limits against the actual deployment configuration.
- **Database credentials:** `application.properties` now requires `CONDUIT_DB_PASSWORD`; the local deployment must provide it when starting the app. Test profile credentials remain isolated in test resources. Other deployment secrets should also be injected through a secret manager/environment, not committed properties.

## Validation status

## Validation status (2026-10-03)

- **Full Maven suite: PASS — 425 tests, 0 failures, 0 errors, 10 skipped.** The final run used the direct Maven installation and workspace-local dependency repository. Skips were two workspace-path cases, one Redis-dependent custom workflow, six local HTTP health checks, and one S3 integration case. PostgreSQL integration and concurrency tests ran against local PostgreSQL 18.4; Flyway validated all five migrations.
- **Compile: PASS.** `mvn -Dmaven.repo.local=target/m2repo -DskipTests compile` completed successfully.
- The full suite first exposed two outdated recovery test expectations; tests were updated to assert bounded asynchronous submission and batch stop on saturation. Focused `TaskRecoveryServiceTest` and `TaskWorkerTest` then passed (15 tests), followed by the green complete suite.
- Redis/Kafka broker failure injection and live Docker, Kubernetes, and S3 failure scenarios were not performed. See [system-reliability-audit.md](system-reliability-audit.md) and [plugin-system-audit.md](plugin-system-audit.md) for detailed boundaries.
