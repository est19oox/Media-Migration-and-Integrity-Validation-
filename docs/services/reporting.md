# Reporting Service

[English](reporting.md) | [简体中文](reporting.zh-CN.md)

Reporting consumes MMIV v2 business outcome events from RabbitMQ, stores an
immutable audit trail in MongoDB, and projects one queryable report per
migration. It does not orchestrate Discovery, Transfer, or Integrity and it
does not read their relational tables.

A folder upload is one migration run: Discovery allocates one shared
`migrationId` for every file in the batch, and Reporting aggregates all file
outcomes under that single report. For example, a 160-file upload produces one
report summary, not 160 report cards. The owner view lists failed files by
default. Administrators can switch between failed-only and all-file views for
troubleshooting; both views are paginated and retain their full result totals.

The frozen producer contract and examples are in
[`../contracts/events/v2/README.md`](../contracts/events/v2/README.md).
Discovery publishes
migration/discovery outcomes, Transfer publishes storage outcomes, and
Integrity publishes validation outcomes. Duplicate delivery is idempotent and
invalid or exhausted events are sent to `reporting.events.dlq`.

## Access model

Browser and external clients use API Gateway on port 8080. Reporting validates
the relayed JWT again and applies data ownership itself:

| Gateway endpoint                          | Access             | Purpose                            |
| ----------------------------------------- | ------------------ | ---------------------------------- |
| `GET /api/reports/mine`                   | Authenticated user | Paged reports owned by JWT subject |
| `GET /api/reports/{migrationId}`          | Report owner       | One sanitized report               |
| `GET /api/reports/{migrationId}/files`    | Report owner       | Stable paged file results          |
| `GET /api/reports/{migrationId}/timeline` | Report owner       | Sanitized business timeline        |
| `GET /api/admin/reports`                  | Administrator      | Cross-user report search           |
| `GET /api/admin/reports/{migrationId}/files` | Administrator   | Failed/all file troubleshooting    |
| `GET /api/history`                        | Administrator      | Operational audit history          |
| `GET /api/dashboard`                      | Administrator      | Aggregate operational dashboard    |

A user cannot select another owner with a query parameter. Internal exception
text, infrastructure addresses, credentials, absolute paths, and checksum
values are not returned by the user timeline.

The service's loopback port 8084 is for local diagnostics. Production access
must go through Gateway; frontend code must not call Reporting directly.

## Storage and processing guarantees

- `audit_events` contains the immutable consumed event records.
- `migration_reports` contains the current migration projection.
- Per-file events remain individually auditable, but they do not create
  separate migration reports.
- Messages are acknowledged only after audit and projection persistence.
- Reusing an `eventId` with different content is rejected as a conflict.
- Events may arrive out of order; stage-owned fields are recalculated safely.
- A late terminal event can recover an `INCOMPLETE` report.
- MongoDB and RabbitMQ run as separate containers from the Java service.

Transfer's older AWS SQS operational log is unrelated and is not consumed by
Reporting.

## Build and unit test

Use Java 25 and the repository Maven wrapper:

```sh
services/user-access-service/mvnw -B -f services/reporting-service/pom.xml clean verify
```

Unit tests disable external listeners and health contributors. The real Compose
runtime still checks RabbitMQ and MongoDB health.

## Standalone smoke test

For Reporting-only development, the existing smoke test starts Reporting,
RabbitMQ, and MongoDB, publishes the five successful-path examples, and proves
duplicate delivery does not change totals:

```powershell
powershell -ExecutionPolicy Bypass -File services/reporting-service/scripts/smoke.ps1
```

```sh
sh services/reporting-service/scripts/smoke.sh
```

Stop that standalone stack without deleting its volumes:

```sh
docker compose -f services/reporting-service/compose.yml down
```

## Integrated Transfer-outage acceptance

After starting the complete integrated stack, run:

```sh
bash tests/api/verify-reporting-with-transfer-down.sh
```

This is stronger than the standalone smoke test: it creates a real User Access
account, signs into Gateway, publishes a report whose `initiatedBy` equals that
user's JWT subject, and verifies that the frontend, session, report list,
detail, file results, and timeline remain available while Transfer is stopped.
Cleanup restarts Transfer and deletes the temporary account and MongoDB fixture.

The integrated Compose stack exposes RabbitMQ management only on
`127.0.0.1:15672` so the acceptance script can publish fixtures without making
the management UI remotely reachable.
