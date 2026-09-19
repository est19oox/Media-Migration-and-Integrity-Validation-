# Media-Migration-and-Integrity-Validation-

Media Migration and Integrity Validation is a Java 25 / Maven / Spring Boot service for transferring media files and validating their integrity with SHA-256 checksums.

## Project structure

```text
Media-Migration-and-Integrity-Validation-/
├── .github/workflows/
├── infrastructure/
│   ├── ansible/
│   └── terraform/
├── src/
│   ├── main/java/com/media/migration/
│   │   ├── MediaMigrationApplication.java
│   │   └── transfer/
│   └── test/java/com/media/migration/
├── pom.xml
└── README.md
```

## Transfer API

`POST /api/v1/transfers`

```json
{
  "sourcePath": "/data/source/video.mp4",
  "destinationPath": "/data/migrated/video.mp4"
}
```

The service copies the media file and calculates SHA-256 for the source and destination. The response reports the byte count and whether the checksums match.

## Build

Requires Java 25 and Maven.

```bash
mvn clean verify
mvn spring-boot:run
```

The transfer microservice listens on port `8081` by default.

## Transfer Service microservice

The media transfer capability is deployed as an independent Spring Boot microservice under `services/transfer-service`.

- Java 25 + Maven + Spring Boot
- Default port: `8081`
- API: `POST /api/v1/transfers`
- Performs media file copying and SHA-256 source/destination integrity validation
- Built artifact: `services/transfer-service/target/transfer-service.jar`

The infrastructure deployment in `infrastructure/ansible` builds and runs only this microservice. Terraform exposes TCP port 8081 for the transfer service.

## Health checks, smoke tests and rollback

The transfer microservice exposes two lightweight checks:

- `GET /actuator/health` — Spring Boot health endpoint used by deployment health checks.
- `GET /api/v1/health` — application-level smoke-test endpoint.

Ansible deploys each build as an immutable release under `/opt/transfer-service/releases/<release-id>` and keeps `/opt/transfer-service/current` as the active symlink. Before switching releases, the previous release is recorded in `/opt/transfer-service/previous_release`.

If deployment or the post-deployment smoke test fails in CI/CD, the pipeline runs `infrastructure/ansible/rollback.yml`, which points the `current` symlink back to the previous release, restarts the service and verifies the health endpoint.

Manual rollback:

```bash
cd infrastructure/ansible
ansible-playbook rollback.yml
```


## Microservice structure

The project currently contains a working `transfer-service` and a structural `validation-service` placeholder. Both are represented in the centralized Ansible project. The Validation Service is disabled until executable code and a deployable artifact are added.
