# Execution context

## Source of truth and persistence

`ExecutionContextEntity.variableJson` remains the single PostgreSQL document for one `WorkflowExecution`. The document uses `_schemaVersion: 1`, a `variables` object, and a `tasks` object keyed by stable `TaskNode` IDs. A task entry contains its name, terminal status, and (for success only) output, result variables, and metadata. Existing flat JSON context rows are read and lazily converted when the next update is written; no second table or migration is required.

Plugin execution stays outside database transactions. After a plugin result has been durably stored by the idempotency service, `TaskResultHandler` commits the task terminal state, context update, and workflow coordinator progression in one short transaction. A failed attempt does not publish output. A retry publishes only its final result. A duplicate terminal delivery does not append or overwrite context. If completion transaction fails, the Redis record remains pending; redelivery obtains the idempotent result and retries the transaction.

Context read/modify/write is serialized by a PostgreSQL pessimistic row lock. Parallel branch outputs have separate task IDs and are merged without stale whole-document replacement. Concurrent result variables with identical names are merged in transaction commit order; workflows should use distinct global variable names when branches execute in parallel.

## Context view and expressions

The expression view is rebuilt from the durable context document plus persisted task execution statuses. It does not depend on process memory, Redis, Kafka, or a live `RuntimeExecutionContext`.

Example view:

```json
{
  "variables": { "customerId": "C123" },
  "tasks": {
    "fetchUser": {
      "status": "SUCCESS",
      "output": { "id": 7 },
      "variables": { "userId": 7 },
      "metadata": { "statusCode": 200 }
    }
  },
  "workflow": { "executionId": 42, "version": 3, "status": "RUNNING" }
}
```

Configuration strings use `${dotted.path}`. Exact-placeholder values preserve their JSON type; embedded values become strings. Nested JSON objects and arrays are traversed recursively. Supported examples:

```json
{
  "url": "https://example.test/users/${variables.customerId}",
  "headers": { "X-User": "${tasks.fetchUser.variables.userId}" },
  "timeout": 30,
  "resultId": "${tasks.fetchUser.output.id}"
}
```

Existing `${taskName.output...}` and top-level variable references continue to resolve. Task names are unique within a workflow version by database constraint. For names that are awkward in dotted syntax, use `tasksById.task_<id>...` with the stable database task ID.

Branch `EXPRESSION` uses the existing boolean expression evaluator, with dotted map lookups, string/number/boolean/null literals, `==`, `!=`, comparisons, `&&`, `||`, parentheses, and unary `!`. It accepts either a bare expression or a full `${...}` wrapper, for example:

```text
${tasks.fetchUser.output.status == "SUCCESS" && variables.customerId != null}
```

`ALL_PARENTS` and `ANY_PARENT` retain their existing join behavior. False branches wait until their parents are terminal, then the coordinator marks the child `SKIPPED` and recursively reevaluates descendants. `ON_SUCCESS`, `ON_FAILURE`, `ALWAYS`, and `ON_COMPLETION` retain their established terminal-state semantics.

The evaluator only reads maps and literals; it has no Java reflection, function calls, Spring bean, filesystem, network, database, or environment access. Expression input is limited to 2,048 characters, 256 tokens, and 1,024 characters per string literal. Configuration JSON and the whole persisted context default to 1 MiB; each task result defaults to 256 KiB and interpolated strings to 64 KiB. Override the context limits with `CONDUIT_EXECUTION_CONTEXT_MAX_BYTES` and `CONDUIT_EXECUTION_CONTEXT_MAX_TASK_RESULT_BYTES`. Secret-like map keys (password, secret, token, credential, API/private/access key, authorization, cookie) are omitted from task result persistence and cannot be interpolated. Opaque strings must still be treated as potentially sensitive; plugins should not return credentials in output.

Prometheus counters record context updates/failures, expression evaluation outcomes, interpolation outcomes, conditional branch outcomes, and skipped branches. They do not label metrics with execution IDs, task names, or user data.

`variables` and `tasks` are intended for modest runtime data. Store large artifacts in the execution workspace or S3 and pass a path/object reference. Opaque plugin strings cannot be reliably inspected for secrets; plugins should not return credentials as output.

## Recovery and integrations

PostgreSQL task rows and context are authoritative. On application restart, workflow recovery resumes dispatch from database state; successful outputs remain available for downstream interpolation. A task result already completed under its idempotency key is reused on redelivery and is not rerun. Redis Streams remains the delivery queue, and Kafka remains the trigger transport with its existing event inbox deduplication. Neither stores execution context.

No context HTTP endpoint is added here; callers should use the existing execution/task inspection APIs rather than exposing the internal JSON document.

## Verification

`ExecutionContextPostgresConcurrencyTest` issues concurrent context writes against PostgreSQL and checks that all updates survive, then creates a fresh service reader and verifies that task output and variables reconstruct from persisted state. Unit tests cover atomic task-result publication, nested JSON output, safe interpolation, type preservation, malformed/missing/null paths, secret filtering, expression limits, and branch evaluation. The requested live `SET_VARIABLES → SHELL → FILE_READ → S3_UPLOAD` chain was **SKIPPED** in this run: Docker CLI could not connect to the Docker daemon, AWS CLI reported no configured credentials or region, and no target bucket was configured. No AWS call or fake S3 result is claimed.
