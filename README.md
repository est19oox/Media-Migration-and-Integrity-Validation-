# Media Migration and Integrity Validation

[English](README.md) | [简体中文](README.zh-CN.md)

MMIV is a microservice-based system for discovering, transferring, validating, and reporting media migrations with access control.

## Services

| Service | Port | Responsibility |
| --- | ---: | --- |
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
