# MMIV architecture

[English](architecture.md) | [简体中文](architecture.zh-CN.md)

MMIV is a set of seven independently built Spring Boot services. Eureka
provides service registration and discovery. The API Gateway serves the static
frontend and exposes explicit routes to backend services. RabbitMQ carries
business lifecycle events and operational service logs; it does not execute
the migration workflow.

## Services and data stores

| Service          | Default port | Main responsibility                                          | Data or dependency                 |
| ---------------- | -----------: | ------------------------------------------------------------ | ---------------------------------- |
| Service Registry |         8761 | Eureka registry                                              | Eureka clients                     |
| API Gateway      |         8080 | Browser sessions, CSRF checks, frontend, API routes          | Eureka, User Access, RabbitMQ      |
| Transfer         |         8081 | Uploads and S3 transfer orchestration                        | Discovery, Integrity, S3, RabbitMQ |
| Integrity        |         8082 | Destination object validation                                | Discovery, S3, RabbitMQ            |
| User Access      |         8083 | Authentication and destination permissions                   | Oracle, RabbitMQ                   |
| Reporting        |         8084 | Event projections, reports, and operational history          | RabbitMQ, MongoDB                  |
| Discovery        |         8085 | Source discovery, metadata plans, and destination assignment | Oracle, User Access, RabbitMQ      |

The Gateway frontend is built from `frontend/` and copied into the Gateway
application resources. It is not a separate production service. Vite is used
only as a local frontend development server.

In the integrated Compose deployment, the Gateway is published on host port 8080. Registry and backend application ports bind to loopback; the services
communicate over the Compose network. Supporting Oracle, RabbitMQ, and MongoDB
containers are not additional MMIV application services.

## Request and event flow

1. A user authenticates through User Access. The browser exchanges the signed
   token with Gateway for an HttpOnly session cookie; cookie-authenticated
   mutations use Gateway's CSRF protection.
2. The client creates a metadata plan through Discovery. Discovery checks the
   caller's current destination permission through User Access and records the
   assigned destination.
3. Transfer resolves the plan and rechecks authorization before writing the
   prepared file to S3. Clients do not choose arbitrary buckets or object keys.
4. Integrity checks the assigned destination object and reports its validation
   result to Transfer. Transfer owns the response, retry, and guarded cleanup
   behavior.
5. Discovery, Transfer, and Integrity publish correlated business outcome
   events. Reporting consumes these events and projects migration reports in
   MongoDB.
6. All seven services publish operational log events to RabbitMQ; Reporting
   stores the log records in MongoDB.

See [service integration](services/integration.md) for the current
authorization and event details, and the [event v2 contract](contracts/events/v2/README.md)
for the schema and publisher rules.

## Build boundaries

Each directory under `services/` has its own Maven project and versioned
dependencies. Shared Java event code is under `common/`; the service POMs add
the shared source and resources to their builds. The machine-readable event
contract and valid examples are under `contracts/events/v2/`.

## Deployment boundary

GitHub Actions builds and verifies the application images, then uses Terraform,
Ansible host preparation, Docker Compose, and AWS Systems Manager for the
configured release workflow. The Gateway is the public application entry
point. See [Docker release deployment](deployment/docker-release.md) for
readiness and recovery details.
