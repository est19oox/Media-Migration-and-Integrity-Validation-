# Corrected project from 20261008-090353 ZIP

## Repairs

- Resolved merge/stash conflicts in Discovery configuration, Integrity configuration and the microservice Ansible role.
- Corrected Integrity's Spring configuration and retained its JPA audit repository.
- Restored missing runnable API Gateway and Service Registry code.
- Restored active Eureka registration, correct service names and Spring Cloud BOMs for all backend services.
- Kept Integrity on Spring Boot 4.1.1 and added compatible Spring Cloud 2025.1.3; the Boot 3.5 services use Cloud 2025.0.3.
- Restored explicit HttpCore 5.4.3 and Bouncy Castle 1.85 dependency overrides.
- Removed the duplicate test-source IntegrityValidationService implementation. The current validation and audit code remains intact.
- Moved Integrity's S3 configuration to its package directory and set an explicit AWS region default.
- Disabled external Eureka in context tests; Integrity uses H2 and a mocked S3 client in context tests.
- Made Oracle optional: without database secrets Integrity uses embedded H2; both URL and password activate the Oracle profile through a private environment file on EC2.
- Restored bounded SSM connection settings.
- Added a repository validation guard that fails CI if unresolved Git conflict markers are committed.

## Deployment requirements

INTEGRITY_DB_URL and INTEGRITY_DB_PASSWORD are no longer required. With neither configured, Integrity starts using embedded in-memory H2 and creates its audit table automatically. Audit records are lost on restart; this mode does not provide durable production audit storage.

Oracle remains optional. Providing both secrets activates the oracle profile; INTEGRITY_DB_USERNAME defaults to INTEGRITY_SERVICE. Oracle must then be reachable from the container and contain the INTEGRITY_SERVICE schema and INTEGRITY_VALIDATION_LOG table (ddl-auto=validate). A partial secret pair does not activate Oracle. For direct runs, use SPRING_PROFILES_ACTIVE=oracle with the database settings.

This restores deployment without required database secrets while preserving the latest checksum validation code, folder transfer and earlier conflict repairs. It does not revert Integrity to the older placeholder service.

Ports: Gateway 8080, Transfer 8081, Integrity 8082, User Access 8083, Reporting 8084, Discovery 8085, Registry 8761. EC2 remains t3.medium. Deployment order is Terraform, shared setup, registry, backends, gateway.

## Apply

Use the full corrected ZIP in a clean directory. It contains the latest submitted business code with these repairs. Do not combine unresolved conflict versions by blindly selecting one side of a merge.

If applying the changed-files ZIP over an existing checkout, delete these obsolete files:

- services/integrity-service/src/test/java/mmiv/service/IntegrityValidationService.java
- services/integrity-service/src/main/java/config/S3Config.java

The replacement S3Config is under src/main/java/mmiv/config.

## Verification

Configuration checks, conflict-marker scan, XML parsing and deployment template rendering were run for this revision. Full Maven tests and a live container deployment were not run: this environment has Java 17 and no Maven, while Integrity targets Java 25. CI must verify the application build and runtime.

## 2026-10-09 deployment, rollback and coverage repairs

- Decoupled mmiv-backend networking from Redis; rollout, Ansible rescue and explicit rollback all set the shared network and EUREKA_DEFAULT_ZONE=http://service-registry:8761/eureka/.
- Fixed rollback image argument separation and moved changed_when outside the shell script.
- All published service ports use 127.0.0.1 binding. Browser access is available through scripts/cd/open-gateway-tunnel.sh and the PowerShell command in docs/ci-cd/gateway-access.md. Terraform keeps authenticated SSM access with no public ingress; EC2 remains t3.medium.
- Commented Redis role YAML, standalone playbook, inventory options and setup invocation. No Redis environment files are attached to application containers. Existing Redis containers are not deleted.
- Restored PPT/PPTX/DOC/DOCX uploads, server-side transfers and MIME types; Office file reads use attachment disposition. Preserved the uploaded project's folder endpoint /api/v1/transfers/folder, 500 files and 100 MiB total.
- Registry functional tests remain configured. Gateway deployed tests now exercise Transfer through discovery, including file upload/read/ranges, Word upload/download and an Office folder batch. ZAP baseline scans start at the Registry dashboard and Gateway upload page, while readiness is checked independently at /actuator/health. Registry/Gateway use passive baseline scans, not active API scans.
- Added a Docker command regression test, invoked in GitHub CI after Ansible installation. CI triggers include tests/api and helper changes. ZIP shell scripts retain executable permissions; files/directories avoid world-writable modes so Ansible loads its configuration.

Validation: Ansible syntax checks, Docker command execution against a mocked CLI, configuration/syntax checks, CI helper runtime tests and local Java Office-validator execution. Full Maven/JUnit, live AWS/S3/Eureka and live ZAP scans must be verified in CI. This ZIP does not claim an error-free live deployment.
