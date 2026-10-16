# Historical project corrections

The current deployment process is documented in [Docker deployment](ci-cd/docker-deployment.md). Entries below record earlier repairs and validation at the time they were made.

## Repairs

- Resolved merge/stash conflicts in Discovery configuration, Integrity configuration and the microservice Ansible role.
- Corrected Integrity's Spring configuration and retained its JPA audit repository.
- Restored missing runnable API Gateway and Service Registry code.
- Restored active Eureka registration, correct service names and Spring Cloud BOMs for all backend services.
- Kept Integrity on Spring Boot 4.1.1 and added compatible Spring Cloud 2025.1.3; all services now use Boot 4.1.1 and Cloud 2025.1.3 (see the Spring SCA correction below).
- Restored explicit HttpCore 5.4.3 and Bouncy Castle 1.85 dependency overrides.
- Removed the duplicate test-source IntegrityValidationService implementation. The current validation and audit code remains intact.
- Moved Integrity's S3 configuration to its package directory and set an explicit AWS region default.
- Disabled external Eureka in context tests; Integrity uses H2 and a mocked S3 client in context tests.
- Made Oracle optional: without database secrets Integrity uses embedded H2; both URL and password activate the Oracle profile through a private environment file on EC2.
- Restored bounded SSM connection settings.
- Added a repository validation guard that fails CI if unresolved Git conflict markers are committed.

## Deployment requirements

INTEGRITY_DB_URL and INTEGRITY_DB_PASSWORD are no longer required. With neither configured, Integrity starts using embedded in-memory H2 and creates its audit table automatically. Audit records are lost on restart; this mode does not provide durable production audit storage.

Oracle remains optional. Providing both secrets activates the oracle profile; INTEGRITY_DB_USERNAME defaults to INTEGRITY_SERVICE. Oracle must then be reachable from the container and contain the INTEGRITY_SERVICE schema and INTEGRITY_VALIDATION_LOG table (ddl-auto=validate). The current deployment rejects partial Oracle settings before rollout. For direct runs, use SPRING_PROFILES_ACTIVE=oracle with the database settings.

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
- Backend and Registry ports use 127.0.0.1 binding; Gateway uses 0.0.0.0 on 8080, with Terraform security groups controlling ingress. Browser access is available through scripts/cd/open-gateway-tunnel.sh and the PowerShell command in docs/ci-cd/gateway-access.md. Terraform keeps authenticated SSM access with no public ingress; EC2 remains t3.medium.
- Commented Redis role YAML, standalone playbook, inventory options and setup invocation. No Redis environment files are attached to application containers. Existing Redis containers are not deleted.
- Restored PPT/PPTX/DOC/DOCX uploads, server-side transfers and MIME types; Office file reads use attachment disposition. Preserved the uploaded project's folder endpoint /api/v1/transfers/folder, 500 files and 100 MiB total.
- Registry functional tests remain configured. Gateway deployed tests now exercise Transfer through discovery, including file upload/read/ranges, Word upload/download and an Office folder batch. ZAP baseline scans start at the Registry dashboard and Gateway upload page, while readiness is checked independently at /actuator/health. Registry/Gateway use passive baseline scans, not active API scans.
- Added a Docker command regression test, invoked in GitHub CI after Ansible installation. CI triggers include tests/api and helper changes. ZIP shell scripts retain executable permissions; files/directories avoid world-writable modes so Ansible loads its configuration.

Validation: Ansible syntax checks, Docker command execution against a mocked CLI, configuration/syntax checks, CI helper runtime tests and local Java Office-validator execution. Full Maven/JUnit, live AWS/S3/Eureka and live ZAP scans must be verified in CI. This ZIP does not claim an error-free live deployment.

## Semgrep startup ImportError repair

Reproduced the supplied eval_type_backport ImportError in an isolated installation of semgrep==1.138.0. Its package metadata pins mcp==1.12.2; MCP imports eval_type_backport, which Pydantic 2.14 removed. Pinned the Semgrep environment to pydantic==2.13.5 and isolated Checkov in its own environment. Applied the same setup to both GitHub Actions workflows (cicd.yml and validate.yml) and the Jenkins Dockerfile.

Scanner-only command wrappers are placed on PATH so later Python/Ansible installations do not modify the scanner environments. Both installers run pip check, the FastMCP import and scanner version commands before scanning. The Terraform update notice in the supplied log is informational.

Local validation: reproduced the failure before the pin; tested patched scanner startup and pip dependency checks. Live GitHub Actions and rebuilding the Jenkins image remain to be verified.

## Spring SCA correction (2026-10-09)

- The six Boot 3 services explicitly pinned Spring Framework 6.2.19. Spring's advisories for CVE-2026-47890 and CVE-2026-47892 list 7.0.9 as the open-source fix; 6.2.20 requires enterprise support and is absent from Maven Central.
- Upgraded those services to Spring Boot 4.1.1 and Spring Cloud 2025.1.3, with Framework 7.0.9. Integrity already used this Boot/Cloud pair. Boot 4.1.1's public dependency BOM manages Framework 7.0.9.
- Replaced the Tomcat 10 pins with 11.0.26 for Servlet 6.1, retained patched Jackson 2 at 2.21.7 under Boot 4's `jackson-2-bom.version`, and configured Jackson 3 at 3.1.7. Removed Boot 3 Micrometer/Netty pins so Boot manages matching versions (Micrometer 1.17.1 and Netty 4.2.17.Final).
- Used Boot 4 Web MVC/WebFlux main/test starters and moved Gateway/Access/Registry test imports to their new packages. Registry explicitly opts into TestRestTemplate support with the restclient test starter and annotation.
- Added `--skip-version-check` to SCA to suppress the informational Trivy update notice. Vulnerabilities still fail the gate; no CVE suppressions were added. Removed the obsolete Spring 6.2.19 CVE-2026-47884 exception now that Framework 7.0.9 fixes it.
- Advisory references: https://spring.io/security/cve-2026-47890/ and https://spring.io/security/cve-2026-47892/ .
- Validation: all seven services compiled and ran 109 tests successfully with Maven 3.9.11 and a local `-Djava.version=17` override. User Access and Integrity used a command-line Mockito startup agent because this execution environment blocks Mockito self-attachment. Production POMs and CI still use Java 25; Java 25/live deployment were not exercised here. CI runtime fixtures, deployment configuration tests, POM XML and shell syntax checks also passed.
- Full Trivy verification is unresolved locally: the initial scan was stopped by a Maven Central HTTP 429 dependency lookup; after Maven populated the dependency cache, the retry reached all seven POMs but Trivy 0.74.0 crashed with SIGBUS while reading its vulnerability database. Neither attempt yielded a completed findings report. Rerun the normal CI SCA gate on the updated project.
