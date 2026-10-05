# Conduit

**Conduit is a visual workflow orchestration platform for building and operating reliable, versioned automation pipelines.** Model work as a directed acyclic graph (DAG), connect integrations and compute steps, add conditional paths or human approval gates, then run and inspect each execution.

The backend is built with Java 21 and Spring Boot. PostgreSQL is the durable source of truth for workflow definitions and execution state; Redis Streams dispatch tasks to workers; Kafka can trigger workflows from events. Tasks can use integrations, managed workspaces, Docker, or Kubernetes.

> **Project status:** Conduit is an actively developed personal project. Review configuration, security, and operational requirements for your environment before exposing an instance or using it for production workloads.

## What Conduit can do

- **Design graph workflows:** compose named tasks into dependency graphs with parallel paths, join behavior, success/failure dependencies, and expression-based conditions.
- **Version and validate:** work in drafts, publish immutable workflow versions, validate a graph before execution, clone or import/export workflow definitions, and compare or replay completed runs.
- **Run and recover tasks:** use retries, idempotency keys, fenced task leases, a transactional outbox, Redis consumer-group redelivery, and startup recovery to coordinate at-least-once task delivery.
- **Share data between tasks:** persist variables, task outputs, variables, and metadata in the execution context. Downstream task configuration can interpolate expressions such as `${variables.customerId}` and `${tasks.fetchUser.output}`.
- **Route to people:** pause at human approval, rejection, or response steps and continue the graph based on the decision.
- **Operate executions:** inspect task status, resolved inputs, logs, lifecycle history, artifacts, and queue state; pause, resume, cancel, or replay executions; set deadlines, concurrency limits, and workflow priority.
- **Manage secrets and configuration:** create workflow-scoped encrypted credentials, reference them from plugin configuration, and snapshot non-secret environment profiles for each run.
- **Extend integrations:** use built-in plugins or create trusted Java plugins with the Plugin SDK.
- **Monitor the service:** expose health and Prometheus metrics and use the included Prometheus/Grafana setup.

## Built-in plugins

The backend currently registers these 29 built-in plugin types:

| Area | Plugin types |
|---|---|
| Workflow utilities | `SET_VARIABLES`, `JSON_TRANSFORM`, `LOG`, `SLEEP`, `FAIL`, `HUMAN_TASK` |
| HTTP and data acquisition | `HTTP`, `WEBHOOK`, `HTTP_DOWNLOAD`, `WEB_SCRAPE` |
| Files and source control | `FILE_READ`, `FILE_WRITE`, `GIT`, `SFTP` |
| Compute and containers | `SHELL`, `DOCKER_BUILD`, `DOCKER_RUN`, `KUBERNETES_JOB`, `HEALTH_CHECK` |
| Databases and messaging | `POSTGRESQL`, `REDIS`, `KAFKA_PRODUCER`, `EMAIL_SEND` |
| AWS | `S3_UPLOAD`, `S3_DOWNLOAD`, `DYNAMODB`, `LAMBDA_INVOKE`, `SNS_PUBLISH`, `SQS_SEND` |

Plugin capabilities and configuration schemas are available through the authenticated plugin API and the in-app workflow editor. Some integrations need external services, credentials, network access, or runtime tools (for example Docker, Kubernetes, AWS, SMTP, or an SFTP server); the plugin being present does not provision those dependencies.

## Architecture

```text
REST API or Kafka trigger
          │
          ▼
 PostgreSQL workflow versions, executions, task state, context
          │
          ├── transactional outbox ──► Redis Streams / consumer groups
          │                                      │
          │                                      ▼
          │                              task workers and retries
          │                                      │
          │                                      ▼
          │                       Plugin SDK / integration plugins
          │                         ├── managed workspace
          │                         ├── Docker executor
          │                         └── Kubernetes executor
          │                                      │
          └──────── durable task result ◄────────┘
```

PostgreSQL owns durable workflow and execution state. Redis is a dispatch mechanism, not the execution-context database. Kafka is an optional trigger path, not a context store. Redis streams provide at-least-once delivery; Conduit uses task idempotency and fencing to protect database transitions, but cannot make an arbitrary external side effect exactly once. Integrations should use provider idempotency support where available.

## User interface

The backend API is designed to work with the separate **Conduitfrontend** project, which provides the visual workflow editor and execution interface. Configure the frontend's API origin and the backend CORS allowlist to match your local or deployed URLs. Frontend setup and release steps are maintained with that project.

## Requirements

- Java 21
- Maven (or the included `scripts/mvn.cmd` wrapper on Windows)
- PostgreSQL
- Redis
- Docker Compose for the bundled Kafka, Prometheus, and Grafana services
- Optional: Docker Engine, Kubernetes, AWS credentials/resources, SMTP, SFTP, and external endpoints depending on workflows

## Local development setup

### 1. Configure PostgreSQL and Redis

Create a PostgreSQL database named `conduit` and make PostgreSQL and Redis available locally. Defaults are PostgreSQL `localhost:5432` and Redis `localhost:6379`. Override the database settings with `CONDUIT_DB_URL`, `CONDUIT_DB_USERNAME`, and `CONDUIT_DB_PASSWORD`; override Redis with Spring's `SPRING_DATA_REDIS_HOST` and related Redis properties. Kafka defaults to `localhost:9092`.

Flyway manages schema changes. Hibernate validates the schema; it does not update production tables. For a new empty database, the baseline migration creates the current schema. Existing installations should keep their Flyway history and data.

### 2. Create local secrets

Set secrets in your terminal environment or use a secret manager. Do not commit real values to Git. Example for PowerShell (compatible with Windows PowerShell):

```powershell
function New-ConduitKey([int] $Length) {
  $bytes = New-Object byte[] $Length
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  [Convert]::ToBase64String($bytes)
}

$env:CONDUIT_DB_PASSWORD = "your-local-postgres-password"
$env:CONDUIT_JWT_SECRET = New-ConduitKey 64
$env:CONDUIT_CREDENTIALS_ENCRYPTION_KEY = New-ConduitKey 32
$env:CONDUIT_MFA_ENCRYPTION_KEY = New-ConduitKey 32
$env:CONDUIT_BOOTSTRAP_ADMIN_EMAIL = "admin@example.com"
$env:CONDUIT_BOOTSTRAP_ADMIN_PASSWORD = "choose-a-unique-password-at-least-12-characters"
$env:CONDUIT_ALLOWED_ORIGINS = "http://localhost:3000"
```

Keep the credential and MFA encryption keys stable and backed up. If the credential key is lost, stored workflow secrets cannot be decrypted. The bootstrap administrator is created only when the account table is empty; remove the bootstrap password from the runtime environment after initialization. There is no public sign-up or OAuth sign-in.

### 3. Start supporting services

The Compose file starts Kafka, Prometheus, and Grafana. PostgreSQL and Redis are expected to be available separately.

```powershell
$env:CONDUIT_METRICS_TOKEN = New-ConduitKey 48
$env:GF_SECURITY_ADMIN_PASSWORD = "choose-a-unique-local-grafana-password"
docker compose up -d
```

`CONDUIT_METRICS_TOKEN` is only for Prometheus scraping; it is not an API login token. Grafana is available at `http://localhost:3000`, Prometheus at `http://localhost:9090`.

### 4. Start the backend

```powershell
scripts\mvn.cmd spring-boot:run
```

The API listens on `http://localhost:8080`. On Windows, the included command wrapper avoids loopback initialization issues encountered with some Maven launch environments. Health is available at `/actuator/health`; Prometheus metrics are at `/actuator/prometheus` and require the metrics token.

### 5. Sign in and configure workflows

Sign in through the frontend using the bootstrap administrator account. Create individual accounts with the appropriate roles (`ADMIN`, `BUILDER`, `OPERATOR`, or `VIEWER`). Add workflow credentials through the credentials interface and refer to them in plugin configuration with the exact form `{"$credential":"credentialName"}`. Configure the exact frontend origin in `CONDUIT_ALLOWED_ORIGINS`.

For workflows with external side effects, first validate and inspect the graph, replace example endpoints and resource identifiers, and confirm credentials and permissions before publishing or executing.

## Workflow data and expressions

Execution context is persisted in PostgreSQL and recovered from durable execution/task records. Successful task results become available to downstream steps after the task result is committed. Typical references include:

```text
${variables.customerId}
${tasks.fetchUser.output}
${tasks.fetchUser.variables.userId}
${tasks.fetchUser.metadata.statusCode}
```

String values in supported plugin configuration fields can use the same interpolation syntax, including nested maps and lists. Expressions are data lookups, not Java or script execution. Task references use the task name; use unique task names in a workflow. Secret material should be stored as workflow credentials, not ordinary execution variables.

See [Execution context and expressions](docs/execution-context.md) for context shape, persistence, safety, recovery, and size constraints.

## Authentication and security

Conduit uses first-party password accounts and signed JWT access tokens with rotating opaque refresh tokens in an `HttpOnly` cookie. It does not use OAuth and does not offer public registration. Passwords are hashed; roles and workflow ownership restrict access; MFA can be enabled with TOTP. Workflow credentials are encrypted at rest and their values are never returned by the API. Secret-like inline configuration is rejected/redacted in supported paths.

For deployment, use HTTPS, set `CONDUIT_REFRESH_COOKIE_SECURE=true`, set `CONDUIT_ALLOWED_ORIGINS` to exact trusted origins, use unique high-entropy secrets, limit account roles, and restrict access to the database, Redis, Kafka, plugin directory, and execution workers. Custom Java plugin JARs are trusted code and execute inside the Conduit JVM; the Plugin SDK does not sandbox them. See [security and platform features](docs/product-platform-features.md) for the current controls and their boundaries.

## Example workflows

The repository includes realistic multi-plugin workflow definitions and manual setup notes in [Showcase workflows](docs/showcase-workflows.md) and `examples/workflows/`. Scenarios cover public supplier intake, catalog research and human review, release/canary promotion, settlement reconciliation, and AWS event routing. They are templates: replace placeholder endpoints, credentials, and cloud resource identifiers before running.

## Plugin SDK

Implement `WorkflowPlugin`, provide metadata and configuration validation, then package a trusted plugin JAR with a Java `ServiceLoader` provider entry. Place the JAR in the configured plugin directory (`plugins` by default) before application startup.

- [Plugin SDK guide](docs/plugin-sdk.md)
- [Example plugin project](examples/conduit-example-plugin)
- [Plugin system audit](docs/plugin-system-audit.md)

## Documentation

- [Execution context and expressions](docs/execution-context.md)
- [Human tasks](docs/human-tasks.md)
- [SFTP plugin](docs/sftp-plugin.md)
- [JavaScript-rendered web scraping](docs/web-scrape-javascript-rendering.md)
- [Showcase workflows](docs/showcase-workflows.md)
- [Platform features, authentication, and operations](docs/product-platform-features.md)
- [Backup and restore](docs/backup-and-restore.md)
- [Observability setup](observability/README.md)
- [System reliability audit](docs/system-reliability-audit.md)

## Tests

Tests are written with the Spring Boot test stack. Unit tests can be run with:

```powershell
scripts\mvn.cmd test
```

Integration tests that use PostgreSQL, Redis, or Kafka require the corresponding local services and test database configuration. See `src/test/resources/application.properties` before running them. A full clean test run is:

```powershell
scripts\mvn.cmd clean test
```

## License

No license file is currently included. Until a license is added, standard copyright restrictions apply; contact the repository owner before reusing or redistributing the code.
