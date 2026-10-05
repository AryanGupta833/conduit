# Human tasks

`HUMAN_TASK` creates a durable request and moves its task and workflow execution to `WAITING_FOR_HUMAN`. It does not sleep, retain a worker, or hold a queue delivery; the request and its response are stored in PostgreSQL. The scheduled expiry reconciler also checks persisted expiry timestamps, so a restart does not reset a deadline.

## Configuration

```json
{
  "title": "Approve Production Deployment",
  "description": "Review the generated deployment artifact before publishing.",
  "type": "APPROVAL",
  "assignee": "devops",
  "metadata": {"artifact": "build/package.zip"},
  "timeoutSeconds": 86400,
  "timeoutPolicy": "FAIL"
}
```

Types are `APPROVAL`, `INPUT`, `REVIEW`, and `CONFIRMATION`. Timeout policies are `FAIL` (default), `REJECT`, and `SKIP`. Approval records a successful task; rejection fails it, so normal success-dependent downstream tasks do not run. Input stores a JSON object as the task result variables and publishes safe fields to the existing `${variables.name}` expression context. Existing expression and plugin validation remains in force for these untrusted values.

## API

- `GET /api/human-tasks` lists requests; optional filters are `status`, `workflowExecutionId`, `assignee`, and `type`. Statuses are `WAITING_FOR_HUMAN`, `APPROVED`, `REJECTED`, `RESPONDED`, `EXPIRED`, and `CANCELLED`.
- `GET /api/human-tasks/{id}` returns one request.
- `POST /api/human-tasks/{id}/approve` and `/reject` accept an optional `{"comment":"..."}` body.
- `POST /api/human-tasks/{id}/respond` accepts `{"response":{"environment":"production"},"comment":"..."}` for `INPUT` tasks.

The response includes request, workflow execution, and task execution IDs; status, type, title, description, assignee, timestamps, actor, and response. Missing IDs return 404, invalid input 400, and invalid/already completed transitions 409. Completion is compare-and-set under the workflow execution lock; the winner persists the task result and execution variables and resumes downstream evaluation through the coordinator and existing outbox dispatcher in the same transaction. Retried calls return 409 without re-running downstream work. Submitted data is limited to 64 KiB, nested to 32 levels, and rejects secret-like field names.

## Authorization and audit

Spring Security is already present, but this feature does not add an identity or assignee authorization model. `completedBy` uses the authenticated principal where available and otherwise records `anonymous`; creation is recorded as `system`. Assignee is descriptive metadata and must not be treated as access control. Creation, completion, and expiry also emit task execution log entries without recording the response payload.

## Manual test workflows

Two published, unexecuted workflows are seeded in the local `conduit` database:

1. **Manual Test - Human Approval and Branching** — approve to take the `ON_SUCCESS` branch; reject to take the `ON_FAILURE` branch. Both branches set a distinct `humanDecision` variable.
2. **Manual Test - Human Input and Context** — submit a JSON object containing `operatorNote`; the downstream JSON transform references `${variables.operatorNote}`.

Use the app's **Human tasks** page to review and complete requests. To start either workflow manually, open its workflow page and execute the published version. These rows are local database data, not a migration or portable fixture. The `Conduitfrontend` project provides the human-task queue and action panel. Automated integration coverage against a real PostgreSQL/Redis deployment is still separate from these manual workflows.
