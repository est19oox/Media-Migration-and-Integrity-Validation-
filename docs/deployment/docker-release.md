# Docker release deployment

[English](docker-release.md) | [简体中文](docker-release.zh-CN.md)

The active hosted release workflow is `.github/workflows/cicd.yml`. It builds
the seven application service images, prepares the AWS host, deploys a
Docker Compose release through AWS Systems Manager, and runs deployed-service
verification. GitHub Actions is the hosted CI/CD path; the optional Jenkins
controller is a local demonstration setup.

## Release flow

1. CI validation and security checks run before release deployment. The
   reusable `build-service.yml` workflow builds, tests, scans, and publishes
   service images tagged for the source commit.
2. Terraform and host preparation configure the deployment infrastructure.
   Ansible is used for host preparation and protected optional configuration
   staging; application deployment and rollback are handled by the Docker
   stack scripts over SSM.
3. The release job validates a complete image manifest and sends the selected
   image set to the host. One SSM deployment command reconciles the release
   under the `mmiv-stack` Compose project.
4. Compose starts the Service Registry and managed supporting services before
   dependent backends. The backends must become healthy and register with
   Eureka before the Gateway readiness checks can pass.
5. The verification workflow checks service readiness and Gateway routes,
   then runs the configured functional checks and DAST scans. It records
   per-service evidence in workflow artifacts.

The deployed release contains all seven application services: Service
Registry, API Gateway, Transfer, Integrity, User Access, Reporting, and
Discovery. Oracle, RabbitMQ, and MongoDB are supporting services. Reporting
uses RabbitMQ events and MongoDB; SQS log queue notes in older documents no
longer describe the active Reporting path.

## Network exposure

The Gateway listens on host port 8080 and is the public application entry
point. The Registry and backend application ports bind to loopback on the
host; the containers communicate over the shared Compose network. Restrict
Gateway ingress to approved client networks when configuring the deployment.
See [Gateway access](gateway-access.md) for browser and SSM tunnel access.

## Recovery and operational limits

The deployment records release state and recovery progress on the host.
Failed rollouts restore changed services from the previous release snapshot;
verification failures invoke the recovery path through SSM. Unchanged services
are preserved, and managed Oracle data and persistent volumes are not removed
by application rollback.

This is an in-place Compose deployment and does not promise zero downtime.
Recovery behavior is not a substitute for a separately tested database
backup/restore procedure. Follow the workflow evidence and operator
instructions before retrying a failed release; do not delete Terraform state,
database volumes, or release data to bypass a failed check.

## Implementation and evidence

- `.github/workflows/cicd.yml` — release orchestration.
- `.github/workflows/build-service.yml` — reusable service build and image job.
- `.github/workflows/verify-services.yml` — readiness, functional checks, and
  deployment recovery.
- `.github/workflows/validate.yml` — pull-request validation.
- `scripts/cd/docker-stack-ssm.py` — image manifest validation and SSM
  orchestration.
- `scripts/cd/docker-stack-remote.py` — host-side stack deployment and
  recovery.
- `scripts/cd/verify-deployed-services.py` — deployed readiness and
  verification workflow.
- `infrastructure/service-registry/compose.yml` — integrated service
  definition.

The exact deployment outcome and attached reports are available from the
corresponding GitHub Actions run. See [testing and formatting](../testing/README.md)
for local test scope and [login and protected access](login-access.md) for the
browser authentication model.
