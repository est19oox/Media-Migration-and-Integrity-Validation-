# Service catalog

[English](catalog.md) | [简体中文](catalog.zh-CN.md)

The seven application services are independent Maven projects under `services/`.
They use the default ports below unless overridden by runtime configuration.
In the integrated Compose stack, only API Gateway is published on all host
interfaces; the other application ports bind to loopback.

| Service          | Port | Responsibility                                         | Principal dependencies             |
| ---------------- | ---: | ------------------------------------------------------ | ---------------------------------- |
| Service Registry | 8761 | Eureka registration and discovery                      | RabbitMQ                           |
| API Gateway      | 8080 | Frontend, browser session, and explicit backend routes | Eureka, RabbitMQ                   |
| Transfer         | 8081 | Metadata-authorized file uploads and S3 transfer       | Discovery, Integrity, S3, RabbitMQ |
| Integrity        | 8082 | S3 object integrity validation                         | Discovery, S3, RabbitMQ            |
| User Access      | 8083 | Authentication, account, and destination authorization | Oracle, RabbitMQ                   |
| Reporting        | 8084 | Business report and service-log projections            | RabbitMQ, MongoDB                  |
| Discovery        | 8085 | Source discovery and migration metadata                | Oracle, User Access, RabbitMQ      |

Shared Java event code is in `common/`. Event schemas and examples are in
`contracts/events/v2/`. See [integrated service flow](integration.md)
for authorization and request details.

## Service-specific guides

- [API Gateway](api-gateway.md) — browser session, routes, packaging,
  and build verification.
- [Reporting](reporting.md) — event consumption, data access,
  storage guarantees, and smoke tests.
- [Transfer and Integrity validation](transfer-integrity.md)
  — transfer outcomes, integrity requests, retries, and cleanup.

The other service responsibilities are summarized above and in the architecture
and integration guides; standalone README files duplicating this information
were removed.
