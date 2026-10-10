# Docker deployment and verification phases

The service workflow deploys Docker images directly through AWS Systems Manager Run Command. It does not invoke the Ansible microservice deployment role.

## Process

1. Existing quality gates and Terraform run first. Terraform reuses the managed EC2 host.
2. Ansible `setup-host.yml` reconciles Docker, AWS CLI, Python support, the pinned Compose plugin, media directory and shared Docker network.
3. Registry image deploys on 8761 and must be healthy.
4. Enabled backend images deploy in parallel on 8081–8085. Each must be healthy and registered UP in Eureka. Functional tests and DAST do not run inside these deployment jobs.
5. API Gateway image deploys last on 8080. It must be healthy, registered, and able to route the upload page, files page and a read-only file API request to Transfer.
6. A separate verification workflow downloads this run's deployment state, verifies that every enabled deployment is present, and then runs functional tests and DAST for all enabled services and the registry. Each service gets its own report directory. Gateway verification runs last. Scans are sequential to prevent overlapping active scans and verification rollback.
7. After failed verification, restore changed deployments that have a previous version: Gateway first, backend services next, registry last. Unchanged deployments and services already restored locally are skipped. A failed Docker rollout restores its previous version immediately, before downstream deployment can continue. If a backend/Gateway deployment job fails, the verification workflow skips scanning and recovers the available changed deployment states instead.

## Enabled services

Current AWS deployment enables registry 8761, Transfer 8081, Integrity 8082, and Gateway 8080. User Access 8083, Reporting 8084 and Discovery 8085 retain their commented matrix entries. The verification workflow reads the active matrix, so an enabled service automatically joins the readiness barrier and verification list.

Discovery currently requires Oracle/media settings and lacks Eureka integration. Configure those before enabling it. The Docker lifecycle currently supplies common AWS/Eureka/media settings and optional Integrity Oracle configuration; additional service-specific settings must be added deliberately before enabling a service that requires them. Gateway routing tests currently cover Transfer.

## Idempotency

- ECR service tags include the full commit SHA and the repository enforces immutable tags. An existing tag is pulled/reused, not overwritten. Maven tests can still run on a repeat workflow without changing runtime state.
- EC2 pulls a missing image before touching the running service or rollback backups.
- Each service has a separate `mmiv-<service>` Compose project, `/opt/mmiv/<service>/compose.yaml`, and a localhost port binding. Gateway alone binds to all interfaces on 8080.
- `docker compose up -d --no-build --pull missing` reconciles the service. An unchanged image, Compose file and environment preserve the container and rollback backups. An exited container is started.
- Image/configuration changes capture the current runtime image and prior Compose/environment files before replacing that service. A changed Integrity environment forces only Integrity to recreate; unchanged settings do not.
- Old unmanaged containers migrate once, after the new image is available. Containers owned by another Compose project are rejected.
- Oracle secrets are optionally staged by Ansible into a private next file. The active environment is changed by the Docker lifecycle only after it has saved rollback state. Credentials are not embedded in SSM commands or deployment-state artifacts. If all Oracle secrets are empty, Integrity uses H2.
- These are in-place rollouts, not zero-downtime deployment. A first deployment has no previous version: its verification failure is reported as NO_PREVIOUS_DEPLOYMENT rather than pretending rollback succeeded.

## Files and evidence

- `.github/workflows/deploy-service.yml`: build/reuse image, scan/push it, Docker rollout, readiness, deployment-state artifact.
- `.github/workflows/verify-services.yml`: barrier, functional tests, DAST and coordinated recovery.
- `scripts/cd/docker-service-ssm.py`: runner-side SSM request and result polling.
- `scripts/cd/docker-service-remote.py`: EC2-side Docker reconciliation and rollback.
- `scripts/cd/verify-deployed-services.py`: required-service checks, scan sequencing and rollback sequencing.
- `infrastructure/ansible/stage-integrity.yml`: optional protected Oracle configuration staging.

Download `verification-evidence-<commit>` after the workflow. It contains per-service logs, functional results and ZAP reports beneath `.ci/reports/verification/<service>/`, plus verification and rollback summaries. Deployment artifacts retain their image reference, SSM command IDs/results and runtime state. Re-running an unchanged commit reports no runtime configuration change and skips unrelated rollback.

## Validation performed

Local regression checks cover simulated Docker rollouts, current-container registration, rollback, SSM result polling, workflow dependencies, scanner preparation, HTTP endpoint evidence and upload-page behavior. CI also retains checks for the optional manual Ansible role. Static YAML, JSON, Maven XML, Python and shell checks pass.

Live AWS/SSM deployment, actual Docker/Compose runtime, Terraform, Ansible and Maven execution were not available here. Their workflow gates remain in place. Deploy this new commit to validate the live integration; re-running the old commit uses the old workflows.

### SSM and scanner preparation

The main workflow checks SSM connectivity and waits for cloud-init only in
`setup-host`. Its deployment jobs pass `host-ready: true` after that job succeeds.
Standalone callers default to checking the host themselves. Each Docker deployment
uses one Run Command for container health, Eureka registration and Gateway routes;
registration failures restore a previous image when one exists. Command results
use fast initial polling followed by a five-second interval, with an 1850-second
overall deadline and retries for invocation visibility.

Verification prepares Session Manager and the pinned ZAP image once on its runner.
All service scans inherit `SCANNER_TOOLS_PREPARED=true` and inspect the local image
instead of pulling again. An individual manual scan prepares missing tools and
reuses the image if it already exists. Separate port-forwarding sessions remain
necessary for the different service ports. Ansible installation counts are unchanged.


## Cleanup and review fixes

- Removed `scripts/cd/smoke-test-service.sh` and `scripts/cd/wait-for-registration.sh`.
  Their checks now execute inside the Docker deployment command; they had no remaining workflow callers.
- Removed the duplicate root `DOCKER-CD-UPDATE.md`; this document describes the current process.
- Registration checks require the current container's network address and service port,
  so an old UP entry cannot satisfy readiness. Health responses must be JSON objects.
- Host Docker operations and readiness loops have time limits. SSM allows 1800 seconds
  for deployment plus recovery, and runner polling allows 50 seconds of additional time.
  Verification allows 180 minutes for scans and 150 minutes for recovery within a 360-minute job.
- Temporary staged Oracle secrets are removed even when image pull or ownership checks fail.
- Functional JSON assertions distinguish booleans from numeric values. A response of
  `success: 1` cannot satisfy `success: true`.
- Scan cleanup clears historical tunnel IDs before checking connectivity.

For an existing checkout, remove the obsolete files above and those recorded in
`CLEANUP-REVIEW.md`, copy the complete updated tree, review changes, and commit to the
intended branch. Terraform and host setup still precede service deployment; Gateway
remains last; tests and DAST still wait for the complete readiness barrier.
