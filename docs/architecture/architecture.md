# MMIV Architecture

MMIV is a service-based media migration system. The API Gateway serves the browser application and routes requests to independently deployed Spring Boot services. Eureka provides service registration; services use explicit HTTP interfaces for request/response work and RabbitMQ for lifecycle and operational events.

## Services

| Service          | Default port | Responsibility                                                |
| ---------------- | -----------: | ------------------------------------------------------------- |
| Service Registry |         8761 | Eureka registration and discovery                             |
| API Gateway      |         8080 | Browser entry point, sessions, routing, and request admission |
| Transfer         |         8081 | Authorized storage operations and transfer coordination       |
| Integrity        |         8082 | Stored-object checksum validation and audit records           |
| User Access      |         8083 | Accounts, authentication, and destination permissions         |
| Reporting        |         8084 | Event projections, reports, and administrative views          |
| Discovery        |         8085 | Source discovery and migration metadata                       |

The ports are application defaults. Deployment exposure is controlled by the selected runtime configuration; the integrated Compose stack publishes the Gateway and keeps backend application ports on loopback.

## Ownership and communication

Each service owns its business data and rules. User Access owns accounts and grants; Discovery owns migration plans and metadata; Transfer owns storage execution; Integrity owns validation outcomes; Reporting owns event projections. The Gateway and Service Registry are infrastructure components, not business contexts.

Services use HTTP for permission checks, plan allocation, transfer execution, and validation requests. Discovery, Transfer, and Integrity publish versioned events for Reporting to project. RabbitMQ transports these events; it does not coordinate the migration workflow.

For the implemented context map and security boundaries, see [service communication](service-communication.md). For aggregate invariants and persistence ownership, see [aggregate design](aggregate-design.md).

## Data and infrastructure

- Oracle stores User Access and Discovery data.
- MongoDB stores reporting projections.
- Amazon S3 stores migrated media.
- RabbitMQ carries lifecycle and operational events.
- Redis backs shared Gateway rate limits.

Local development and deployment require environment-specific configuration for these dependencies. Keep credentials and generated infrastructure state out of version control.
