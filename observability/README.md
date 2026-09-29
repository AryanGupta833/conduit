# Conduit observability

Conduit exposes application and JVM metrics through Spring Boot Actuator. Start the application on port 8080, then start the local monitoring services from the repository root:

```powershell
docker compose up -d prometheus grafana
```

- Prometheus: <http://localhost:9090>
- Grafana: <http://localhost:3000> (local development login: `admin` / `admin`)
- Health: <http://localhost:8080/actuator/health>
- Prometheus scrape endpoint: <http://localhost:8080/actuator/prometheus>

Prometheus scrapes the host application every 15 seconds. Grafana provisions the Prometheus data source and the **Conduit Operations** dashboard automatically.

The dashboard covers workflow and task outcomes, plugin-specific retries and durations, Docker/Kubernetes execution results and durations, active workers, and Redis consumer-group pending messages. `conduit_task_queue_pending` counts delivered messages not yet acknowledged. `conduit_task_queue_stream_entries` is the total number of retained Redis stream records; acknowledged records remain in the stream, so this value is not backlog depth.

All instrumentation is best effort. Metrics use bounded status, plugin, and backend tags and do not include workflow, task, user, or request identifiers.
