# Media Migration and Integrity Validation

[English](README.md) | [简体中文](README.zh-CN.md)

MMIV is a microservice-based system for discovering, transferring, validating, and reporting media migrations with access control.

## Services

| Service | Port | Responsibility |
| --- | ---: | --- |
| Service Registry | 8761 | Eureka service registration and discovery |
| API Gateway | 8080 | Route browser and API requests to backend services |
| Transfer | 8081 | Transfer media between storage locations |
| Discovery | 8085 | Discover media and resources |
| User Access | 8083 | Manage and check access permissions |
| Reporting | 8084 | Generate migration and validation reports |
| Integrity | 8082 | Validate migrated media integrity |

## Documentation

- **[Draft]** [Architecture](docs/architecture/architecture.md) — architecture options and decisions requiring team discussion
- **[Draft]** [API Contracts](docs/api/api-contracts.md) — cross-service API contracts
- **[Draft]** [CI/CD](docs/ci-cd/ci-cd.md) — GitHub Actions and Jenkins responsibilities
- [Jenkins Setup](docs/jenkins/local-setup.md) — local scripts automatically build, configure, and run Jenkins

> Architecture and API documents marked **Draft** are still under team discussion.

## Development

Each microservice is maintained independently under `services/`.

Build and test a service from its own directory:

```bash
mvn clean verify
```


Gateway browser access and temporary Redis disabling: see docs/ci-cd/gateway-access.md.


## Merged gateway access

Gateway is public on EC2 TCP 8080; backends bind to localhost. See
[Gateway access](docs/ci-cd/gateway-access.md). The real Discovery Service from
main is preserved; its CD entry is disabled pending Oracle/runtime configuration.

## Current Docker deployment

Terraform and parallel image builds → idempotent host preparation → one Compose stack deployment through SSM: Registry first, backend group next, API Gateway last → functional tests and DAST. One release manifest records the image set and drives rollback. Ansible is used only for host preparation and optional Oracle configuration, sharing one CD installation. See [Docker CD process](docs/ci-cd/docker-deployment.md).

### Current deployment configuration

See [the main deployment update](docs/ci-cd/main-update.md) before running CD:
User Access uses a persistent Oracle container with automatically generated credentials
and JWT keys. No runtime GitHub secret is required for this default. The optional
`MMIV_RUNTIME_CONFIG` secret still supports an external User Access database and
explicit Integrity settings. Integrity keeps its current database configuration. Transfer publishes file events to SQS; the log service is left for its owner.

## Login and Transfer access

Gateway root (`http://EC2_PUBLIC_IP:8080/`) now opens the login page. After login, /upload.html opens. Transfer pages, uploads and file reads require a verified User Access JWT. Browser sessions use HttpOnly cookies and CSRF protection; API clients use Bearer headers. See [login setup](docs/ci-cd/login-access.md).

Signup is available through **Create an account** on login, or `/signup.html`. New accounts persist in Oracle, then return to login before accessing Transfer.
