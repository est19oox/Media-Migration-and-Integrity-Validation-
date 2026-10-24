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

Some runtime operations also require configured Oracle schemas, RabbitMQ, MongoDB, AWS credentials, and access to the configured S3 bucket. Do not put credentials in tracked files. See [local development](docs/development/local-setup.md) before attempting to run the integrated application.

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
- The repository-wide formatting scripts also check Maven Spotless and Terraform formatting. See [testing and formatting](docs/testing/README.md)

## Documentation

Start with the [English documentation index](docs/README.md) or the [Simplified Chinese index](docs/README.zh-CN.md). Each guide is linked directly below; Chinese links are provided where a paired translation is available.

| Topic                 | English                                                             | 简体中文                                                           |
| --------------------- | ------------------------------------------------------------------- | ------------------------------------------------------------------ |
| Architecture          | [Architecture](docs/architecture.md)                                | [架构](docs/architecture.zh-CN.md)                                 |
| Development           | [Local setup](docs/development/local-setup.md)                      | [本地开发](docs/development/local-setup.zh-CN.md)                  |
| Development           | [Local Oracle database](docs/database/README.md)                    | [本地 Oracle 数据库](docs/database/README.zh-CN.md)                |
| Services              | [Service catalog](docs/services/catalog.md)                         | [服务目录](docs/services/catalog.zh-CN.md)                         |
| Services              | [API Gateway](docs/services/api-gateway.md)                         | [API Gateway](docs/services/api-gateway.zh-CN.md)                  |
| Services              | [Reporting](docs/services/reporting.md)                             | [Reporting](docs/services/reporting.zh-CN.md)                      |
| Services              | [Transfer and Integrity](docs/services/transfer-integrity.md)       | [Transfer 与 Integrity](docs/services/transfer-integrity.zh-CN.md) |
| Services              | [Integrated service flow](docs/services/integration.md)             | [服务集成流程](docs/services/integration.zh-CN.md)                 |
| Services              | [Circuit breakers](docs/services/circuit-breakers.md)               | [服务熔断器](docs/services/circuit-breakers.zh-CN.md)              |
| Testing               | [Testing and formatting](docs/testing/README.md)                    | [测试与格式化](docs/testing/README.zh-CN.md)                       |
| Testing               | [API and DAST validation](docs/testing/api-validation.md)           | [API 与 DAST 验证](docs/testing/api-validation.zh-CN.md)           |
| Deployment            | [Docker release](docs/deployment/docker-release.md)                 | [Docker 发布部署](docs/deployment/docker-release.zh-CN.md)         |
| Deployment            | [Gateway access](docs/deployment/gateway-access.md)                 | [Gateway 访问](docs/deployment/gateway-access.zh-CN.md)            |
| Deployment            | [Login and protected access](docs/deployment/login-access.md)       | [登录与受保护访问](docs/deployment/login-access.zh-CN.md)          |
| Deployment            | [Ansible](docs/deployment/ansible.md)                               | [Ansible](docs/deployment/ansible.zh-CN.md)                        |
| Deployment            | [Manual Ansible role](docs/deployment/ansible-manual-role.md)       | [手动 Ansible role](docs/deployment/ansible-manual-role.zh-CN.md)  |
| Deployment            | [Terraform](docs/deployment/terraform.md)                           | [Terraform](docs/deployment/terraform.zh-CN.md)                    |
| Deployment            | [Optional local Jenkins](docs/jenkins/local-setup.md)               | [可选本地 Jenkins](docs/jenkins/local-setup.zh-CN.md)              |
| Administration        | [Administrator access](docs/admin/administrator-access.md)          | English only                                                       |
| Administration        | [Folder workspace](docs/admin/folder-workspace.md)                  | English only                                                       |
| Historical operations | [Bootstrap recovery notes](docs/infrastructure/bootstrap/README.md) | English only; verify current AWS state before use                  |
| Event contract        | [Event v2 guide](docs/contracts/events/v2/README.md)                | [事件契约 v2](docs/contracts/events/v2/README.zh-CN.md)            |
| Event contract        | [JSON Schema](contracts/events/v2/mmiv-event.schema.json)           | Language-neutral schema                                            |

The optional Jenkins guide documents a local demonstration controller. GitHub Actions automatically runs the repository's hosted CI/CD workflows.

## Contributing

Keep service changes within the relevant Maven project unless a shared contract or common event implementation is involved. Update documentation when behavior, configuration, or developer commands change. Run the checks relevant to the change before opening a pull request, and never commit local credentials, environment files, or generated Terraform state.

## Project Status

✅ **Production Ready** - The MMIV system is fully implemented and tested. All seven microservices are operational with complete integration, deployment automation, and comprehensive documentation.
