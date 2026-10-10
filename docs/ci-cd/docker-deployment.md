# Docker release deployment

The GitHub pipeline builds all enabled images in parallel and deploys one Compose
stack through one SSM Run Command. The Service Registry is included. Gateway deploys
last, after backend health and Eureka registration pass.

## Pipeline

1. Existing CI/security gates pass. Terraform runs on every pipeline. Image builds
   run in parallel with Terraform after the gates; each image is tested, scanned,
   pushed or reused by its immutable commit tag, and recorded in an image artifact.
2. `setup-host` waits for Terraform and all image builds. Ansible reconciles host
   packages, Docker, the pinned Compose plugin, media storage and the shared network.
   The same Ansible installation stages optional Oracle settings if supplied.
3. `deploy-stack` downloads the complete image set and creates one release manifest.
   Missing, stale, duplicate or mismatched images fail before service deployment.
4. One SSM command downloads missing images with bounded parallelism, captures the
   prior release, and reconciles `/opt/mmiv/stack/compose.yaml` under `mmiv-stack`:
   - Registry starts on 8761 and must become healthy.
   - Enabled backends start together; their health and current-container Eureka
     registration checks run concurrently.
   - Gateway starts on 8080 last. Its health, registration, pages and routed file
     request must pass before the release becomes ready.
5. Verification downloads `release-state-<commit>`, checks the complete readiness
   barrier, prepares Session Manager and pinned ZAP once, and runs functional tests
   and DAST. Each service has its own reports. Gateway is scanned last.
6. Scans finish before coordinated rollback. Failed rollout recovery happens inside
   the original deployment command. A verification failure uses one additional SSM
   command to restore changed services from the release snapshot.

Compose is invoked for each phase inside the same remote command. Explicit host-side
checks gate later phases, including Eureka registration; container startup alone is
not treated as readiness. `--no-deps` prevents an individual phase from starting
Gateway early. Functional tests and ZAP remain on the GitHub verification runner.
Separate port-forwarding sessions are retained for the different service ports.

## Enabled services

| Service | Host port | Deployment phase |
| --- | ---: | --- |
| Service Registry | 8761 | First |
| Transfer | 8081 | Backend group |
| Integrity | 8082 | Backend group |
| API Gateway | 8080 | Last |

User Access 8083, Reporting 8084 and Discovery 8085 retain commented entries in the
`build-images` matrix. Enabling a configured service adds its image, rollout and
verification requirements automatically. Discovery still needs Oracle/media settings
and Eureka integration before it is enabled. Gateway readiness currently checks its
Transfer routes. The additional service-specific settings must be implemented before
enabling services that require them.

## Idempotency and recovery

- ECR tags include the commit; existing images are scanned and reused. EC2 downloads
  only missing images and completes all pulls before modifying service state.
- One Compose file is generated from the release manifest. Unchanged service image,
  configuration and environment preserve containers. An Oracle environment change
  forces only Integrity to recreate.
- The release ID includes commit, workflow run and attempt. A repeated command for
  the same successful release preserves its original snapshot. An unchanged new run
  records no changed services and does not restart containers or trigger rollback.
- Release snapshots live under `/opt/mmiv/stack/releases/<release-id>/`. They capture
  prior Compose configuration, runtime image versions, change flags and a private
  copy of the prior Integrity environment. Credentials are absent from SSM command
  parameters and downloaded artifacts.
- Oracle staging uses `/etc/mmiv/next-integrity-<release-id>.env`, with protected
  permissions and hidden Ansible logs. Release-specific staging prevents another
  attempt's cleanup from deleting a newer staged file.
- A journal records touched services before each mutation. Recovery restores only
  changed services that were touched, preserving untouched containers. New services
  with no prior container are removed to restore their prior absence. This also
  cleans up a failed first deployment.
- Recovery orders Gateway first, backends next, Registry last, then checks restored
  health/registration and Gateway routes. It resumes recorded recovery work after
  interruption. A subsequent release first recovers an unfinished earlier rollout;
  it never records a partially deployed stack as the previous release.
- A host lock prevents simultaneous stack operations. A stale release cannot roll
  back a newer release. Workflow concurrency still serializes deployments.
- Earlier per-service `mmiv-<service>` Compose containers migrate once to the shared
  project, with prior configuration captured. Foreign Compose ownership is rejected.
  Migration recreates containers and may interrupt service. These are in-place
  deployments; no zero-downtime guarantee is made.
- Backends and Registry bind to localhost. Gateway alone publishes on all interfaces,
  subject to the Terraform security group. The shared external network is retained.

## Files and evidence

- `.github/workflows/build-service.yml`: reusable build/test/scan/push and image artifact.
- `.github/workflows/cicd.yml`: parallel image matrix, Terraform, host setup and stack rollout.
- `.github/workflows/verify-services.yml`: readiness barrier, scanner preparation and recovery.
- `scripts/cd/docker-stack-ssm.py`: image-set validation, release assembly and SSM polling.
- `scripts/cd/docker-stack-remote.py`: one Compose stack, phase ordering and release recovery.
- `scripts/cd/verify-deployed-services.py`: all-service verification and one-command recovery.
- `infrastructure/ansible/stage-integrity.yml`: optional protected Oracle staging.

Download `release-state-<commit>`, `release-deployment-evidence-<commit>` and
`verification-evidence-<commit>` for manifests, command IDs/results, per-service logs,
functional results and ZAP reports. Failed rollout output preserves its recovery
status even though deployment remains failed. Interrupted commands can be recovered
from their host journal using the same release manifest and release ID.

The SSM execution limit is 3600 seconds; result polling allows an additional 50
seconds. Polling starts quickly and backs off. Host Docker commands, image pulls,
health and registration checks have separate bounds. Normal completion returns
immediately; these limits do not impose fixed waits.

## Validation and applying the update

Each DAST scan selects an available runner-local tunnel port automatically;
`DAST_LOCAL_PORT=0` also requests automatic selection. Set `DAST_LOCAL_PORT` to
a specific port only when required. An occupied explicit port produces a clear
error without stopping the unrelated listener. Readiness requires the plugin's
port-open acknowledgement before sending HTTP requests. Cleanup terminates the
owned tunnel process group and forcibly stops any remaining plugin child after
a bounded wait, so sequential scans do not depend on reusing port 18081.

Local tests exercise phase order, idempotency, partial updates, Oracle changes,
failed rollout recovery, first-release cleanup, migration, interrupted release
recovery, stale rollback rejection, manifest completeness, SSM eventual visibility,
scan sequencing and scanner image reuse. Static configuration and existing shell
runtime checks also run. Actual AWS/SSM, Docker/Compose, Ansible, Terraform, Maven
and live ZAP execution are not available in this editing environment; run the
workflow on the new commit to validate that integration.

Copy the complete updated tree into your checkout and remove the superseded files:

- `.github/workflows/deploy-service.yml`
- `scripts/cd/docker-service-remote.py`
- `scripts/cd/docker-service-ssm.py`
- `tests/ci/test-docker-lifecycle.py`

Earlier cleanup removals are recorded in `CLEANUP-REVIEW.md`. The optional legacy
Ansible deployment role remains for separate/manual deployments; use stack release
recovery for hosts migrated to `mmiv-stack`. Neither the package nor local tests
prove the runtime cause of the earlier Office folder-upload failure; its diagnostic
fields and integrity assertions remain intact for the next live report.
