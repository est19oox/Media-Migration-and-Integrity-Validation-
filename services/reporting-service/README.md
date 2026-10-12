# Reporting Service

Reporting consumes MMIV v2 outcome events from RabbitMQ, stores an immutable
audit trail in MongoDB, and maintains one queryable report per migration. It
does not control the Discovery, Transfer, or Integrity workflow.

The frozen publisher contract and examples are in
[`../../contracts/events/v2`](../../contracts/events/v2).

## Local prerequisites

- Docker Desktop (Windows/macOS) or Docker Engine with Compose v2 (Linux)
- JDK 25 for building the service JAR
- PowerShell 5.1+ on Windows, or POSIX `sh`, `curl`, and Python 3 on macOS/Linux

MongoDB and RabbitMQ are separate containers. The Compose stack uses pinned
multi-architecture images so the same setup works on Windows/Linux amd64 and
Apple Silicon arm64.

## Start and smoke-test the complete local stack

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File services/reporting-service/scripts/smoke.ps1
```

macOS/Linux:

```sh
sh services/reporting-service/scripts/smoke.sh
```

The smoke test builds the JAR, starts Reporting + MongoDB + RabbitMQ, publishes
the five successful-path contract fixtures, waits for a completed report, and
replays the Integrity event to prove duplicate delivery does not change totals.
It deliberately leaves the stack running for inspection.

Stop containers while keeping MongoDB/RabbitMQ data:

```sh
docker compose -f services/reporting-service/compose.yml down
```

Delete containers **and** local data only when intentional:

```sh
docker compose -f services/reporting-service/compose.yml down --volumes
```

Copy `.env.example` to `.env` in this directory only if you need different
local ports or credentials. Do not commit a real `.env` file.

## Local endpoints

| Endpoint | Purpose |
|---|---|
| `GET http://localhost:8084/actuator/health` | Runtime health |
| `GET http://localhost:8084/api/reports/{migrationId}` | One migration report |
| `GET http://localhost:8084/api/reports?page=0&size=20` | Paged reports |
| `GET http://localhost:8084/api/reports/{migrationId}/files` | Stable, paged file results |
| `GET http://localhost:8084/api/reports/{migrationId}/events` | Ordered audit events without processing internals |
| `http://localhost:15672` | RabbitMQ management UI |

Default local RabbitMQ login: `mmiv` / `mmiv-local`. These are development-only
defaults and must be overridden outside a developer machine.

## Build and test without Docker

From the repository root:

```sh
services/user-access-service/mvnw -B -f services/reporting-service/pom.xml clean verify
```

The Spring context test disables external health contributors and listener
startup, so unit verification does not require MongoDB or RabbitMQ to be
running. Runtime health in the actual Compose stack still checks both systems.

## Event processing guarantees

- Messages are ACKed only after the audit event and report projection persist.
- Reusing an `eventId` with different bytes is rejected as an integrity conflict.
- Identical redelivery is idempotent and does not double-count a file.
- Invalid/permanently conflicting events and exhausted transient failures go to
  `reporting.events.dlq` through `mmiv.events.dlx`.
- Events may arrive out of order; reports are recalculated from stage-owned
  fields and a late terminal event can recover an `INCOMPLETE` report.
- Event/report JSON contains no absolute host paths or checksum values.
