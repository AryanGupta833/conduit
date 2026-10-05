# Conduit plugin execution audit

Audit performed against the repository state on 2026-10-03. This is an implementation and verification record, not a claim that every integration has production certification.

## Execution architecture

`WorkflowPlugin`, `PluginContext`, `PluginMetadata`, `PluginResult`, `PluginConfigurationException`, and `PluginWorkspace` are the small public plugin SDK. The four-argument `PluginContext` constructor remains for compatibility; `TaskRunnerService` uses the five-argument constructor and supplies the current execution's `ExecutionWorkspace` on normal workflow execution.

`TaskRunnerService` is the configuration boundary. It resolves expressions, creates an execution workspace, validates configured roots through `ExecutionWorkspace.resolve`, rebases relative workspace fields under the selected `workspaceRoot`, validates file inputs where required, and constructs the context. `ExecutionWorkspace` owns path traversal/symlink checks, streams, atomic writes, and quota accounting. `ExecutionWorkspaceService` creates execution roots, marks terminal state, and applies success/failure retention cleanup. Container plugins use `ManagedWorkspaceMount`; the Docker host path is not accepted directly from workflow configuration.

For managed filesystem plugins, `workspaceRoot` must resolve to a directory inside the managed execution workspace. Missing managed roots are created and revalidated through the workspace resolver; an existing regular file cannot be used as a root. Relative path fields are interpreted relative to the validated `workspaceRoot`. A `workspace:/...` value denotes an execution-workspace path and is resolved before platform `Path` parsing. Absolute paths are accepted only when already inside the current execution workspace. Path-bearing configuration handled centrally includes `path`, `destinationPath`, `repositoryPath`, `contextPath`, and S3 upload `filePath`; the latter is passed to the plugin as a validated managed absolute path because the current S3 transfer implementation consumes a `Path`.

## Built-in plugin inventory

| Plugin | Main inputs → result | Workspace / external boundary | Timeout and cleanup | Current test coverage |
|---|---|---|---|---|
| HTTP | URL, method → bounded response body/status | No file workspace; RestTemplate, SSRF address policy | HTTP client connect/read limits; response stream bounded/closed by extractor | HTTP plugin tests; local-server cases depend on loopback |
| WEB_SCRAPE | One or up to 10 pages, CSS selectors → bounded structured extraction; optional isolated Chromium rendering for JavaScript pages | Public HTTP(S) only; every browser request is revalidated against SSRF policy; pagination remains same-origin; no login or bot-control bypass | 10-page cap, loop detection, 1 MiB raw HTML cap, 256 KiB combined output cap, 100 items per page, 200 browser requests, 2 concurrent render sessions | Verify with a permitted public test site before deployment |
| SHELL | command, image, executor → process output/exit metadata | Managed `working/` mounted at `/conduit/workspace` | Docker process timeout, bounded stdout/stderr, container cleanup | Shell backend selection; live shell workflow |
| LOG | Task name/variables → status text | No external system | In-process, no owned resource | Batch/plugin tests |
| FAIL | No required inputs → intentional failed result | No external system | In-process | Retry/worker tests |
| SLEEP | Fixed 3-second delay → completion or interruption failure | No external system | `InterruptedException` now returns failure and restores interrupt flag | Batch plugin test |
| SET_VARIABLES | Variables map → merged variables/count | No external system | In-process | Batch plugin tests |
| WEBHOOK | URL, method, headers/body → bounded response/status | HTTP/SSRF policy; configured headers can contain credentials | HTTP client limits; response bounded and closed | Webhook tests |
| JSON_TRANSFORM | JSON input, operation/path → transformed JSON | No filesystem; Jackson | In-process; input size is not separately capped by this plugin | Batch plugin tests |
| POSTGRESQL | JDBC URL, username/password, SQL/parameters → bounded rows or update count | PostgreSQL via bounded datasource registry in managed application use | JDBC query timeout, max rows/fetch size; registry owns pooled lifecycle | PostgreSQL unit tests; full app test connected to `conduit_tests`; live workflow not run |
| REDIS | Host/port/auth/database, GET/SET/DELETE/EXISTS → operation result | Redis connection registry in managed use | Connection timeout and value/key limits; connection closed per command | Redis unit tests; no live workflow verified |
| FILE_READ | `workspaceRoot`, path, encoding → file content/size | Managed workspace stream in TaskRunner flow | Stream closed; output bounded; file regularity checked by workspace | File tests; SHELL→FILE_READ and AWS round trip live |
| FILE_WRITE | `workspaceRoot`, path, content/encoding → bytes written | Managed workspace atomic write in TaskRunner flow | Same-directory staging, quota, atomic move/cleanup | File tests; workspace write tests; no dedicated live FILE_WRITE chain in this audit |
| KAFKA_PRODUCER | Topic, key/message, timeout → partition/offset | KafkaTemplate | Wait bounded by configured timeout; future completion/error converted to failure | Kafka producer tests; broker workflow not run |
| S3_UPLOAD | Bucket/key plus managed file or inline content → ETag/size | AWS SDK default credential chain and configured region; file must be in managed execution workspace in TaskRunner flow | File streaming; multipart above threshold and abort on error | S3 unit tests; live S3 upload workflows |
| S3_DOWNLOAD | Bucket/key, destination → managed workspace file | AWS `ResponseInputStream` into `PluginWorkspace.writeAtomic` | Stream closed; quota checked while streaming; content length verified before publish; partial removed on error | S3 tests; live upload→download→read workflow |
| SFTP | LIST, DOWNLOAD, UPLOAD → bounded JSON/file result | Apache MINA SSHD; required SHA256 server-key pin; private network opt-in; local paths confined to managed workspace | 60-second connection/auth upper bound, 500-entry listing cap, 100 MiB transfer cap, per-task temporary upload name | Compile verified; no live SFTP server exercised |
| AWS_SNS_PUBLISH | Topic ARN, message, optional subject → message ID | SNS SDK using AWS default credential chain and configured region | SDK call deadline 60s; message bounded to 256 KiB | No live SNS publish verified in this pass |
| AWS_SQS_SEND | Queue URL, message, optional FIFO attributes → message ID | SQS SDK; HTTPS AWS queue hosts only; default credential chain | SDK call deadline 60s; body bounded to 1 MiB; FIFO dedup uses task idempotency key by default | No live SQS send verified in this pass |
| AWS_LAMBDA_INVOKE | Function name/ARN, JSON payload, invocation mode → bounded result | Lambda SDK; default credential chain; no endpoint override from workflow input | SDK call deadline 60s; input 900 KiB and output 256 KiB caps | No live Lambda invoke verified in this pass |
| AWS_DYNAMODB | Single-item GET_ITEM/PUT_ITEM/DELETE_ITEM → JSON item/result | DynamoDB SDK; default credential chain; no scans or unbounded queries | SDK call deadline 60s; input item/key bounded to 350 KiB | No live DynamoDB operation verified in this pass |
| HEALTH_CHECK | HTTP/TCP target and expected response → status | HTTP/TCP with private/reserved address policy | Client timeouts and bounded response handling | Health-check tests; local HTTP server tests require loopback |
| GIT | Clone/fetch/pull/status/branch/log/checkout → bounded process output | TaskRunner confines repository/destination paths; Git invoked with argument lists | Timeout, output cap, descendants terminated; failed clone destination cleanup | Git tests; live GIT→SHELL→S3_UPLOAD workflow |
| DOCKER_BUILD | Context, Dockerfile, image/options → build output | Context resolved under execution workspace; no workflow bind mount | Bounded output, timeout and process/container cleanup paths | Docker build unit tests; live build not run in this pass |
| DOCKER_RUN | Image/command/options → bounded output/exit metadata | Only managed workspace mount; read-only root and resource limits | Timeout, output cap, child/container cleanup | Docker run unit and mount tests; live run not part of this pass |
| KUBERNETES_JOB | Image, command/args/env/resources → job/log result | Kubernetes API; no hostPath or shared execution workspace mount | Deadline/polling/log bounds, cleanup and TTL; restricted pod settings | Kubernetes unit tests; live cluster workflow not run |

There are 25 built-in `WorkflowPlugin` implementations in this checkout. External JAR providers use Java `ServiceLoader`; discovery is deterministic, provider failures are isolated, duplicate types are rejected, and retained classloaders are closed on shutdown. External JARs are trusted in-process code, not sandboxed.

Execution-context integration is described in [execution-context.md](execution-context.md). Plugin outputs, variables, and metadata become visible only in the `TaskResultHandler` transaction that commits terminal task state and workflow progression. Failed attempts are not published. Configuration supports typed/nested `${dotted.path}` interpolation from PostgreSQL context; secret-like field names are filtered and interpolation never reads process environment values. Output and aggregate context sizes are bounded.

Task attempts run on a bounded executor with a deadline. Deterministic configuration, validation, security, and workspace-limit errors are not retried; retry delays saturate safely at the configured cap. Completion is committed through result handling before workflow progression is considered complete. A crash after a plugin performs a remote side effect but before local durable completion can still repeat that effect, so integrations need their own idempotency keys where available.

## Changes from this audit

### Shared workspace path scoping

Before this pass, `TaskRunnerService.configureWorkspace()` rebased only selected fields (FILE_READ and S3 fields). GIT `destinationPath`, FILE_WRITE `path`, and Docker build `contextPath` could remain relative to the execution root even when `workspaceRoot` selected `workspace:/working`. Docker SHELL mounts only `working/`, so GIT could report a successful clone outside that mount; the following ZIP command then produced an empty 22-byte archive while also returning success.

TaskRunner now scopes all managed relative path fields under the already validated configured root, then runs them through `ExecutionWorkspace.resolve()`. Explicit logical paths remain execution-root logical paths and are not prefixed twice. Absolute paths still have to pass the existing execution-root containment and symlink checks. S3 upload file paths are required to exist as regular files and are handed off as a validated managed absolute path; other plugin paths remain execution-root-relative for their existing plugin APIs.

### S3_DOWNLOAD

The prior implementation used the AWS SDK path response transformer and an unmanaged `conduit-s3-*.partial` temp file before copying into the workspace. An HTTP 200 was the successful S3 response status; SDK response-body processing into its target file then failed locally. The observed exception does not provide enough low-level detail to attribute the Windows temp-file read/write failure to a specific OS cause.

S3_DOWNLOAD now uses `S3Client.getObject(request)` as a streaming `ResponseInputStream` and calls `PluginWorkspace.writeAtomic()`. A content-length verifier throws before EOF publication if the body size differs from the response metadata. The workspace writer creates a same-directory partial, checks quota per chunk, rejects traversal/symlink paths, closes and deletes partial output on failure, and atomically moves completed bytes into place. No OS temp file is retained or needed.

### Sleep interruption contract

`SLEEP` previously restored the interrupt flag but then returned `success=true`. It now returns a failed result with interruption metadata when cancelled/interrupted.

## Security and runtime properties

- Workspace confinement is per execution ID. `ExecutionWorkspace.resolve()` rejects absolute logical paths, traversal outside the root, and any existing symlink segment. Workspace directory helpers verify real paths.
- `workspaceRoot` is validated as an execution-workspace location before path fields are scoped. Path fields represent a directory-relative file or directory according to the plugin; S3_UPLOAD checks `filePath` as a regular file, and FILE_READ uses `openRead`.
- `writeAtomic()` stages beside the destination and cleans partial files on exceptions. Its quota is an application-level accounting limit; it is not an OS quota and cannot prevent arbitrary in-process code or child processes from temporarily writing extra bytes outside the atomic writer.
- Task attempts run on a fixed-size bounded executor and are cancelled on timeout. Java interruption cannot forcibly terminate arbitrary in-process plugin code. Process-backed plugins have additional process-tree termination; remote jobs/requests rely on their client/API cancellation and timeout behavior.
- Docker SHELL uses `--read-only`, a bounded writable managed workspace mount, ephemeral `/tmp`, CPU/memory/PID limits, and bounded output. Network access is not disabled globally for SHELL because workflows may require network; deployments must apply daemon/host egress policy where needed.
- Git commands use `ProcessBuilder` argument lists, bounded merged output, timeout/process-tree cleanup, and disable the `ext::` protocol. GIT still has outbound network reachability according to host policy; repository URLs are not an SSRF boundary.
- HTTP, WEBHOOK, and HEALTH_CHECK reject resolved private/reserved addresses by default and disable redirects in the shared client. DNS is resolved separately by the transport after validation, so DNS rebinding/TOCTOU remains possible. Use a DNS-pinned client or egress proxy for a stronger boundary.
- PostgreSQL and Redis credentials are not intentionally included in result metadata/log messages. Database failure results are deliberately generic today, which avoids leaking SQL/connection details but reduces diagnostics. Kafka/S3 exception text needs ongoing review for provider-specific secret redaction.
- Kubernetes jobs disable service-account-token automount, use RuntimeDefault seccomp, disallow privilege escalation/privileged mode, apply resource limits, deadlines and TTL, and do not mount host paths. They cannot share the local Windows execution workspace without an explicit PVC/object-store design.
- The four-argument legacy `PluginContext` constructor yields `workspace()==null`. The TaskRunner production path always provides the managed workspace, but direct legacy plugin invocation can use compatibility paths in older filesystem plugins. New callers should pass `PluginWorkspace` and production workflow execution must remain routed through TaskRunner.

## Verification and integration matrix

### Automated

Final focused command:

```text
mvn -s target/maven-settings.xml -Dtest=TaskRunnerServiceWorkspacePathTest,S3DownloadPluginTest,ExecutionWorkspaceServiceTest,FilePluginTest,GitPluginTest,BatchOnePluginTest test
```

Result: **76 tests, 0 failures, 0 errors, 3 skipped**. Skips are environment-dependent Docker/symlink cases. This run includes workspace-root directory validation and workspace scoping regressions for FILE_WRITE, GIT, S3_UPLOAD, and S3_DOWNLOAD, plus S3 streaming, quota, failed stream, length mismatch, traversal, and partial cleanup tests.

Final full Maven suite for this feature pass: **425 tests, 0 failures, 0 errors, 10 skipped**. Skips were two workspace-path cases, one custom workflow integration (Redis-dependent), six health-check loopback cases, and one S3 integration case. The expanded execution-context/queue/trigger regression set passed **57 tests, 0 failures/errors, 1 environment-dependent skip**; a later core rerun passed 27 tests. PostgreSQL context parallel merge/reconstruction, Redis queue, Kafka inbox, and Flyway tests passed. The suite does not replace a real `SET_VARIABLES → SHELL → FILE_READ → S3_UPLOAD` workflow against a configured account/bucket.

### Real integrations

| Workflow | Result | Evidence |
|---|---|---|
| SHELL → S3_UPLOAD → S3_DOWNLOAD → FILE_READ (workflow 30, execution 2764) | **PASS** | All four tasks succeeded. Final FILE_READ output was `Conduit AWS round-trip test successful!`; file was under `<workspace>/2764/working/aws-roundtrip/downloaded.txt`. |
| GIT CLONE → SHELL ZIP → S3_UPLOAD (workflow 28, execution 2765) | **PASS** | All three tasks succeeded. ZIP was 5,866,945 bytes and contained 54 files under `<workspace>/2765/working/conduit.zip`; prior broken path produced a 22-byte empty ZIP. |
| GIT CLONE → GIT STATUS/LOG | **NOT RUN** | Unit coverage exists; a dedicated composed workflow was not run. |
| SHELL → FILE_WRITE → FILE_READ | **NOT RUN** | File and workspace atomic-write unit tests exist; a dedicated composed workflow was not run. |
| POSTGRESQL → downstream consumer | **NOT RUN** | PostgreSQL test application connected to local `conduit_tests`; no workflow chain was run. |
| REDIS SET → GET | **NOT RUN** | Unit tests exist; no live Redis workflow was run. |
| KAFKA_PRODUCER → consumer | **UNAVAILABLE/UNVERIFIED** | Kafka-dependent Spring context startup encountered loopback socket failures; no broker round trip was established. |
| DOCKER_BUILD → DOCKER_RUN | **NOT RUN** | Docker-backed SHELL workflows succeeded, but the build/run composition was not exercised. |
| KUBERNETES_JOB → result verification | **NOT RUN** | Unit tests ran; no live cluster Job was submitted. |

Docker, GitHub access, and AWS S3 were exercised by prior live workflows recorded above. Local PostgreSQL was reachable during the current Spring test run. Live Redis/Kafka/Kubernetes behavior and deployment-backed outage injection remain unverified.

## Remaining limitations / follow-up

1. A single SDK-level path API for directory/file resolution is not yet exposed. TaskRunner currently provides the shared path boundary, while workspace-consuming plugins use `openRead`/`writeAtomic` and process-oriented plugins receive validated workspace-relative configuration. Consider adding an SDK `resolveDirectory`/`resolveFile` contract only if a plugin needs it without leaking host paths to external providers.
2. Direct legacy plugin calls that omit `PluginWorkspace` should be retired or audited plugin-by-plugin; the public compatibility constructor is retained.
3. The workspace quota is cooperative/application-level, not a filesystem quota. Direct process writes can exceed it before TaskRunner observes final usage.
4. HTTP SSRF checks remain vulnerable to DNS rebinding between validation and the actual transport connection.
5. Docker build/run, Kubernetes live cleanup, live database/Redis/Kafka workflows, large-object multipart transfers, and process cancellation under real timeout load still need deployment-backed testing.
6. Result failures do not yet use one typed category taxonomy across all plugin implementations. Some integrations intentionally return generic errors to avoid including sensitive connection details; improve diagnostics through sanitized error codes rather than raw exception strings.
7. Shell/Docker workflows execute configured commands. Their security boundary depends on the Docker daemon and host egress policy; trusted plugin JARs likewise execute with server-process privileges.

No claim is made that every acceptance item in the master audit brief is complete. The workspace integration defects identified in this pass are fixed and exercised in live composed workflows; the not-run/unavailable items above remain explicit follow-up work.
# Plugin execution upgrades

The built-in plugin set includes two workflow actions for outbound delivery and artifacts:

* `EMAIL_SEND` sends SMTP mail over mandatory STARTTLS. Use a credential reference for the SMTP password, for example `{"$credential":"smtp-prod"}`. Email attachments must be relative paths in the managed execution workspace; arbitrary host paths are rejected. Delivery results contain recipient counts and do not include message contents or SMTP server diagnostics.
* `HTTP_DOWNLOAD` downloads a public HTTP(S) resource into a relative managed-workspace path. The default maximum is 50 MiB and the hard ceiling is 100 MiB. SSRF destination checks, response-size enforcement, atomic workspace writes and the workspace quota apply. Redirects remain disabled by the shared HTTP client.

`HTTP` and `WEBHOOK` attach the task idempotency key as `Idempotency-Key` when no user-supplied header is present. HTTP 408, 425, 429 and 5xx responses are retryable; other 4xx responses are permanent failures. `Retry-After` delta seconds and RFC 1123 dates are honored, bounded to five minutes, and combined with the configured retry backoff. Existing `WEB_SCRAPE` pagination remains bounded by its page limit and same-origin/SSRF checks.

`GIT` clone configuration accepts `depth` (1–10000), either `branch` or `tag`, and optional `expectedCommit` (full SHA-1 or SHA-256 object id). When a commit pin is supplied, the cloned `HEAD` must match exactly or the clone is removed and the task fails.

`POSTGRESQL` retains positional `parameters` and now accepts `namedParameters` for SQL placeholders such as `:accountId`; only one binding style can be used per task. `transaction: true` runs the single configured statement in an explicit JDBC transaction and rolls it back on failure. Query results remain JSON rows; writes return affected-row counts. This is task-scoped transaction support, not a distributed transaction with other plugins.

`S3_UPLOAD` keeps its existing multipart path for large files and now accepts string `metadata`, string `tags`, and `ifAbsent: true` to use S3's conditional create precondition. For single PUTs it sends the SHA-256 checksum to S3; large multipart transfers retain multipart semantics and persist a local checksum marker in object metadata. Objects exceeding the selected service limits fail with a structured plugin failure instead of being loaded wholly into memory.

`HUMAN_TASK` keeps its timeout policies (`FAIL`, `REJECT`, `SKIP`) and supports optional `reminderAfterSeconds` and `escalationAfterSeconds` with a required `escalationAssignee`. Reminder and escalation polls are idempotent database updates; escalation reassigns the waiting item and publishes an execution notification. Decisions are stored as structured response data plus a decision status in execution context.

`JSON_TRANSFORM` supports `MAP` (output field to safe dotted source path), `FILTER` (array items matching a field's JSON value), and `ARRAY_SELECT` (bounded index selection). Output is capped at 1 MiB by default and at most 5 MiB. It does not evaluate expressions or execute code.

All task plugin calls continue through the runner's common duration/retry metrics and credential-result redaction. Network plugins return bounded response data and avoid echoing credentials or full request URLs in errors. `SHELL` accepts Docker resource bounds (`cpus`, `memoryMb`, `pidsLimit`) and `networkDisabled`, reuses the managed workspace with explicit `workspaceMode`, and returns stdout, stderr, exit code and timeout state as result variables by default. Kubernetes SHELL supports its backend defaults and workspace mode; requesting Docker-specific resource or network controls with Kubernetes is rejected. Container cancellation terminates the active process and cleanup path. `DOCKER_RUN` retains its separate CPU, memory, PID, capability, read-only-root, network isolation and workspace controls.
