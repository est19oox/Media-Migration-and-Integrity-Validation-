# Media Migration and Integrity Validation

[English](README.md) | [简体中文](README.zh-CN.md)

MMIV discovers, transfers, validates, and reports media migrations with destination access control. See [service integration and deployment](docs/integration/services.md) for the current APIs, RabbitMQ logging, and live integration test.

## Services

| Service          | Port | Responsibility                                     |
| ---------------- | ---: | -------------------------------------------------- |
| Service Registry | 8761 | Eureka service registration and discovery          |
| API Gateway      | 8080 | Route browser and API requests to backend services |
| Transfer         | 8081 | Transfer media between storage locations           |
| Discovery        | 8085 | Discover media and resources                       |
| User Access      | 8083 | Manage and check access permissions                |
| Reporting        | 8084 | Generate migration and validation reports          |
| Integrity        | 8082 | Validate migrated media integrity                  |

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

Repository formatting uses pinned Prettier plus each service's Spotless configuration and
`terraform fmt`. Run `npm ci` once, then use `scripts/format/format-all.ps1` and
`scripts/format/check-all.ps1` on Windows, or run the matching scripts with `bash` on Linux.
The check scripts never modify files. The `Code Formatting Check` GitHub Actions workflow
(`.github/workflows/auto-format.yml`) is check-only: on pushes to `main`, `feature/**`, `fix/**`,
`chore/**` and `release/**`, and on pull requests to `main`, it runs `npm run format:check`
(Prettier), Spotless for every Maven service, and `terraform fmt -check` for
`infrastructure/terraform`. It never commits changes. Run `format-all` locally and commit the
result when a check fails.

Line endings are LF for all text formatted by Prettier (see `.gitattributes`). On Windows, set
`core.autocrlf` to `input` or `false` so working copies stay LF.

Gateway browser access: see docs/ci-cd/gateway-access.md.

## Merged gateway access

Gateway is public on EC2 TCP 8080; backends bind to localhost. See
[Gateway access](docs/ci-cd/gateway-access.md). The real Discovery Service from
the uploaded code is enabled in CD with its Oracle and shared media configuration.

## Current Docker deployment

Terraform and parallel image builds → idempotent host preparation → one Compose stack deployment through SSM: Registry first, backend group next, API Gateway last → functional tests and DAST. One release manifest records the image set and drives rollback. Ansible is used only for host preparation and optional Oracle configuration, sharing one CD installation. See [Docker CD process](docs/ci-cd/docker-deployment.md).

### Current deployment configuration

See [the main deployment update](docs/ci-cd/login-access.md) before running CD:
User Access uses a persistent Oracle container with automatically generated credentials
and JWT keys. Database credentials need no GitHub secret, but authenticated verification creates and removes a dedicated account per run unless credentials are supplied in `MMIV_RUNTIME_CONFIG`. The same secret also supports an external User Access database and
explicit Integrity settings. Integrity keeps its current database configuration. All seven services publish to RabbitMQ; Reporting stores service logs and migration reports in MongoDB.

## Login and Transfer access

Gateway root (`http://EC2_PUBLIC_IP:8080/`) opens the login page. After login,
`/pages/home.html` opens. Gateway owns the frontend, browser session and CSRF
checks; Transfer continues to own uploads and file operations. Reporting is
routed directly through Gateway, so reports remain available during a Transfer
outage. Browser sessions use HttpOnly cookies; API clients use Bearer headers.
See [login setup](docs/ci-cd/login-access.md).

Signup is available through **Create an account** on login, or `/signup.html`. New accounts persist in Oracle and initially have no destination access. An administrator must grant destination READ or WRITE permission before the account can read or upload files.

Administrator password login, default-account setup, and external invitations are documented in [Administrator access](docs/admin/administrator-access.md).
