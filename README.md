# Media Migration and Integrity Validation (MMIV)

[English](README.md) | [简体中文](README.zh-CN.md)

MMIV is a Java/Spring Boot system for discovering media, authorizing migration destinations, transferring files to Amazon S3, validating stored content, and building migration reports. The application is split into independently built services; the API Gateway serves the browser frontend and routes API requests.

## Architecture

The seven Spring Boot services are registered with Eureka. The Gateway is the browser entry point; service-to-service calls use explicit HTTP APIs and RabbitMQ carries lifecycle and operational events. Reporting projects business events and service logs into MongoDB. User Access and Discovery use Oracle. Transfer and Integrity access S3 for file operations.

| Service          | Port | Responsibility                                                  |
| ---------------- | ---: | --------------------------------------------------------------- |
| Service Registry | 8761 | Eureka registration and service discovery                       |
| API Gateway      | 8080 | Serves the frontend, browser sessions, and explicit API routes  |
| Transfer         | 8081 | Executes metadata-authorized uploads and coordinates validation |
| Integrity        | 8082 | Reads stored objects and validates their content                |
| User Access      | 8083 | Authentication, accounts, and destination permissions           |
| Reporting        | 8084 | Consumes events and serves owner-scoped reports and admin views |
| Discovery        | 8085 | Discovers source media and manages migration metadata           |

The service ports above are application defaults. In the integrated Compose stack, only the Gateway is published on all host interfaces; the other application ports are bound to loopback.

## Technology

| Area                  | Technologies                                                |
| --------------------- | ----------------------------------------------------------- |
| Backend               | Java 25, Spring Boot 4, Spring Cloud, Maven                 |
| Service communication | Eureka, HTTP, RabbitMQ                                      |
| Persistence           | Oracle, MongoDB, and H2 for Integrity's default local mode  |
| Media storage         | Amazon S3                                                   |
| Frontend              | Static HTML/CSS/JavaScript, Vite for local development      |
| Deployment            | Docker Compose, GitHub Actions, Terraform, Ansible, AWS SSM |

## Repository Layout

```text
common/             Shared Java event code and resources
contracts/          Machine-readable event schema and examples
database/           Local Oracle setup scripts
docs/               Developer, service, testing, and operations documentation
frontend/           Browser frontend and Vite development configuration
infrastructure/     Docker Compose, Terraform, and Ansible deployment code
services/           Seven independent Maven services
scripts/            Formatting, CI, deployment, and administration helpers
tests/              API, browser, integration, and CI contract tests
```

## Prerequisites

- **JDK 25** for building and testing the services
- **Maven 3.9.16** - A Maven Wrapper is included at `services/user-access-service/mvnw` and `mvnw.cmd`
- **Node.js 22.12+** and npm for the frontend development server and repository formatting tools (Vite also supports Node.js 20.19+)
- **Docker Compose v2** for local Oracle, supporting services, and integrated runtime tests
- **Python 3** for selected CI and deployment validation scripts

Some runtime operations also require configured Oracle schemas, RabbitMQ, MongoDB, AWS credentials, and access to the configured S3 bucket. Do not put credentials in tracked files. See the [database setup](database/README.md) and [deployment guide](docs/ci-cd/docker-deployment.md) before running the integrated application.

## Quick Start

### Build and Test an Individual Service

From the repository root:

```sh
./services/user-access-service/mvnw -B -f services/transfer-service/pom.xml clean verify
```

On Windows PowerShell:

```powershell
.\services\user-access-service\mvnw.cmd -B -f services\transfer-service\pom.xml clean verify
```

### Run the Frontend Development Server

```sh
npm ci --prefix frontend
npm --prefix frontend run dev
```

Vite serves the static frontend locally and proxies API requests to `http://localhost:8080`. The API-backed flows therefore need a running Gateway and its required services.

### Run Repository Formatting Checks Only

```sh
npm ci
npm run format:check
```

## Testing and Formatting

- Run one Maven service's unit and integration-test lifecycle with `mvn -B -f services/<service>/pom.xml clean verify`
- Run the repository test script with `bash scripts/ci/run-tests.sh .` on Linux, macOS, or WSL (requires Maven and Python 3)
- Run the Prettier check with `npm run format:check`
- The repository-wide formatting scripts also check Maven Spotless and Terraform formatting. See [verification evidence and scope](docs/verification/README.md).

## Documentation

| Topic             | Guide                                                                                                                                                                                                  |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Architecture      | [Overview](docs/architecture/architecture.md), [aggregate design](docs/architecture/aggregate-design.md), [service communication](docs/architecture/service-communication.md)                          |
| API contracts     | [HTTP and event contracts](docs/api/api-contracts.md)                                                                                                                                                  |
| Services          | [Service Registry](services/service-registry/README.md), [API Gateway](services/api-gateway/README.md), and the service guides in `services/`                                                          |
| Event contract    | [Event v2 guide](contracts/events/v2/README.md) and [JSON Schema](contracts/events/v2/mmiv-event.schema.json)                                                                                          |
| Development       | [Frontend](frontend/README.md), [database](database/README.md), and [API tests](tests/api/README.md)                                                                                                   |
| Deployment        | [Docker deployment](docs/ci-cd/docker-deployment.md), [Gateway access](docs/ci-cd/gateway-access.md), [Ansible](infrastructure/ansible/README.md), and [Terraform](infrastructure/terraform/README.md) |
| CI/CD             | [Workflow overview](docs/ci-cd/ci-cd.md)                                                                                                                                                               |
| Administration    | [Administrator access](docs/admin/administrator-access.md) and [folder workspace](docs/admin/folder-workspace.md)                                                                                      |
| Verification      | [Verification evidence and scope](docs/verification/README.md)                                                                                                                                         |
| Optional local CI | [Jenkins setup](docs/jenkins/local-setup.md)                                                                                                                                                           |

GitHub Actions runs the hosted validation and deployment workflows. Jenkins is an optional local demonstration environment.

## Contributing

Keep service changes within the relevant Maven project unless a shared contract or common event implementation is involved. Update documentation when behavior, configuration, or developer commands change. Run the checks relevant to the change before opening a pull request, and never commit local credentials, environment files, or generated Terraform state.

## Project Status

**Project complete and public.** The repository contains seven Spring Boot services, the browser frontend, deployment automation, tests, and operational documentation. Runtime deployment still requires environment-specific infrastructure, credentials, and service configuration; the verification evidence describes its scope and limitations.
