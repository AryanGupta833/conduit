# Conduit

Conduit is a Spring Boot workflow engine for dependency-aware execution of versioned DAG workflows. PostgreSQL stores workflows and execution state, Kafka accepts workflow trigger events, Redis Streams dispatches tasks to workers, and plugins run task work through Docker or Kubernetes backends.

## Architecture

```text
REST or Kafka trigger
        ↓
Workflow execution + task records (PostgreSQL)
        ↓
Runtime workflow dispatcher
        ↓
Transactional outbox → Redis Stream → TaskWorker
                                      ↓
                              TaskRunner + Plugin SDK
                                      ↓
                           Docker or Kubernetes backend
                                      ↓
                    TaskResultHandler → workflow progress
```

Task execution uses PostgreSQL idempotency records with renewable, fenced leases. Redis consumer groups provide at-least-once delivery; pending deliveries can be claimed again after their idle timeout. Prometheus metrics are exposed through Spring Boot Actuator.

## Requirements and local configuration

- Java 21 and Maven
- PostgreSQL and Redis
- Kafka when using Kafka workflow triggers
- Docker for Docker-backed tasks; `kubectl` and a configured cluster for Kubernetes-backed tasks

Set the database password in the process environment; credentials are not stored in the application properties:

```powershell
$env:CONDUIT_DB_PASSWORD = "your-local-postgres-password"
scripts\mvn.cmd spring-boot:run
```

The defaults connect to PostgreSQL at `localhost:5432` (database `conduit`), Redis at `localhost:6379`, and Kafka at `localhost:9092`. Override these with Spring Boot environment variables such as `SPRING_DATASOURCE_URL`, `SPRING_DATA_REDIS_HOST`, and `SPRING_KAFKA_BOOTSTRAP_SERVERS` when needed. Hibernate schema updates are enabled for local development; production deployments should manage schema changes deliberately.

The project Compose file starts Kafka, Prometheus, and Grafana. It expects an environment variable `GF_SECURITY_ADMIN_PASSWORD` for the Grafana admin password:

```powershell
$env:GF_SECURITY_ADMIN_PASSWORD = "your-local-grafana-password"
docker compose up -d
```

PostgreSQL and Redis must be available separately. On Windows, use `scripts\mvn.cmd` to avoid Java loopback initialization problems seen with some Maven launches.

## Workflows and plugins

Create workflows and tasks through the REST API, then start an execution with `POST /api/workflows/{id}/execute`. Kafka trigger messages use `com.aryan.conduit.trigger.WorkflowTriggerEvent` and contain a `workflowId`. The REST API is currently open to unauthenticated requests; do not expose it to an untrusted network.

Built-in plugins are HTTP, Shell, Log, Sleep, and Fail. Shell tasks select the Docker backend by default or Kubernetes with the `executor` configuration. The Plugin SDK also supports trusted Java JARs discovered through `ServiceLoader` from the configured plugin directory (`conduit.plugins.directory`, default `plugins`). Custom plugins execute in the Conduit JVM and are not sandboxed.

See the [Plugin SDK guide](docs/plugin-sdk.md) and the independent [example plugin project](examples/conduit-example-plugin).

Workflow APIs accept and report cron expressions, but this checkout does not currently run a periodic workflow scheduler. Start workflows through the REST or Kafka trigger paths.

## Observability

- Health: `GET /actuator/health`
- Prometheus scrape endpoint: `GET /actuator/prometheus`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

Grafana provisions the dashboard in `observability/grafana/dashboards`. Redis retained stream entries and pending consumer-group messages are shown separately; retained entries include acknowledged records and are not queue backlog depth. See [observability setup](observability/README.md).

## Tests

Set `CONDUIT_DB_PASSWORD` to a PostgreSQL account that can connect to the `conduit_tests` database, then run:

```cmd
scripts\mvn.cmd test
scripts\mvn.cmd clean test
```

The integration tests also require local Redis and Kafka services.
