# Media Migration and Integrity Validation (MMIV)

[English](README.md) | [简体中文](README.zh-CN.md)

MMIV is a Java/Spring Boot system for discovering media, authorizing migration
destinations, transferring files to Amazon S3, validating stored content, and
building migration reports. The application is split into independently built
services; the API Gateway serves the browser frontend and routes API requests.

## System overview

MMIV consists of seven independently built Spring Boot services. Eureka handles
service discovery, HTTP and RabbitMQ support service communication, Oracle and
MongoDB store application data, and Amazon S3 stores media. The API Gateway
serves the browser application. See the [service catalog](docs/services/catalog.md)
for service responsibilities and ports.

## Prerequisites

- JDK 25 for building and testing the services.
- Maven 3.9.16. A Maven Wrapper is included at
  `services/user-access-service/mvnw` and `mvnw.cmd`.
- Node.js 22.12+ and npm for the frontend development server and repository
  formatting tools. Vite also supports Node.js 20.19+.
- Docker Compose v2 for local Oracle, supporting services, and integrated
  runtime tests.
- Python 3 for selected CI and deployment validation scripts.

Integrated runtime operations also require configured Oracle, RabbitMQ,
MongoDB, AWS credentials, and S3 access. Never put credentials in tracked files.
See [local development](docs/development/local-setup.md) before running the
integrated application.

## Quick start

Build and test an individual service from the repository root:

```sh
./services/user-access-service/mvnw -B -f services/transfer-service/pom.xml clean verify
```

On Windows PowerShell:

```powershell
.\services\user-access-service\mvnw.cmd -B -f services\transfer-service\pom.xml clean verify
```

Run the frontend development server:

```sh
npm ci --prefix frontend
npm --prefix frontend run dev
```

Vite serves the static frontend locally and proxies API requests to
`http://localhost:8080`. The API-backed flows therefore need a running Gateway
and its required services. To run only repository formatting checks:

```sh
npm ci
npm run format:check
```

## Documentation

Start with the [English](docs/README.md) or
[Simplified Chinese](docs/README.zh-CN.md) documentation index. It links to
maintained guides and their translations. The Jenkins guide covers an optional
local controller; hosted CI/CD uses GitHub Actions.

## Contributing

Keep service changes within the relevant Maven project unless a shared contract
or common event implementation is involved. Update relevant documentation when
behavior, configuration, or commands change. Run the applicable checks before
opening a pull request; never commit credentials, environment files, or
generated Terraform state.
