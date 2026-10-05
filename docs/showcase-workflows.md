# Conduit Showcase Workflows

The local development database contains six canonical, multi-plugin workflow graphs for manual demonstrations. New definitions are drafts; configure credentials and endpoints, review the task graph, then publish before running. Workflow credentials are referenced by name and must be added through the workflow's **Credentials** panel.

## Scenarios

| Workflow | Real-world scenario | Main capabilities |
|---|---|---|
| Live Scenario — Retail Catalog Intelligence and Human Pricing Review | Gather public product, buyer, and cart signals; assemble a price-review packet; request human review; persist and publish the decision | HTTP, JSON transforms, branching, human tasks, PostgreSQL, Redis, S3, Kafka, webhook |
| Live Scenario — GitHub Repository Intelligence and Maintainer Escalation | Collect repository, issue, and commit signals; assess maintainer risk; route for human triage; persist and notify | HTTP, JSON transforms, human tasks, PostgreSQL, Redis, S3, Kafka, webhook |
| Live Scenario — Partner FX Settlement Reconciliation and Payout Approval | Combine a partner statement with public FX rates; reconcile and request approval; archive evidence and publish the payout event | S3 download/upload, HTTP, file read/write, JSON transforms, PostgreSQL, Redis, Kafka, webhook, sleep |
| Scenario Graph — Spring Petclinic Release Candidate and Canary Promotion | Inspect source, enforce a release gate, build and smoke-test a container, run a Kubernetes canary, health-check it, and route promotion or failure to people | Git, shell, Docker build/run, Kubernetes job, health check, human tasks, webhook, Kafka, retry/failure paths |
| Public Supplier Intake and Purchase Approval | Download a public supplier feed and scrape a paginated catalog while inspecting the vendor SFTP drop; join the evidence at a human approval gate; archive and notify on approval | HTTP download, file read, JSON transform, web scrape, SFTP list/upload, human tasks, email, file write |
| AWS Marketplace Order Review and Event Routing | Fetch a public sample order, ask Lambda for a risk assessment, pause for human review, persist the decision, and fan out to operations and fulfillment | HTTP, JSON transform, Lambda invoke, DynamoDB, human tasks, SNS, FIFO SQS, idempotency keys |

Together these graphs exercise all 29 built-in plugin types currently registered by the backend, including the newer SFTP and AWS plugins. `LOG`, `FAIL`, and `SLEEP` are deliberately used for event recording, failure-path demonstrations, and pacing; their empty `{}` configurations are valid.

## Before running

- **Supplier intake:** Replace the example SFTP host, username, and host-key fingerprint; add the `vendor-sftp-password` workflow credential. Configure the SMTP server, sender, recipient, and `smtp-password` credential. The approval branch uploads to the configured vendor SFTP server, so that server must permit writes. A public read-only SFTP server is suitable only for the listing/download portion.
- **AWS order routing:** Replace the Lambda function name, DynamoDB table, SNS topic ARN, and SQS FIFO queue URL with resources in the same AWS account/region. Configure the backend AWS credential chain with least-privilege permissions for those resources. The example identifiers are placeholders, not real AWS resources.
- **Existing live scenarios:** Review task settings for your own database, Redis, Kafka, S3, Git, Docker, and Kubernetes endpoints. Do not publish a scenario with placeholder destinations or credentials.
- Public HTTP and scraping examples use sample/test datasets. They do not bypass sign-in, bot controls, or access restrictions.

The backend example definitions are in [`examples/workflows`](../examples/workflows/). The frontend also offers the supplier intake and AWS order-routing graphs as workflow templates.

## Database cleanup

Duplicate, unexecuted copies of the four canonical live scenarios were removed from the local development database after verifying they had no workflow runs, task runs, or workflow credentials. The latest four copies were retained. Workflows with execution history were preserved for auditability.
