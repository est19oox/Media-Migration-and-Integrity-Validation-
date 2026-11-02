# Current integration

RabbitMQ, MongoDB, and all seven services are enabled. See [the integration guide](../integration/services.md) for the current logging and destination authorization contracts. RabbitMQ carries business events; SQS remains in use for Transfer file logs and retained legacy queues.

# Docker release deployment

The GitHub pipeline builds all enabled images in parallel and deploys one Compose
stack through one SSM Run Command. The Service Registry is included. Gateway deploys
last, after backend container health passes. Eureka registration and Gateway routes
must also pass before tests start.

## Pipeline

1. Terraform formatting/validation runs first on an isolated runner, sharing one
   provider cache across its three roots. CI/security gates then pass on their own
   runner with Oracle and scanners. Terraform apply runs on every pipeline. Image builds
   run in parallel with Terraform after the gates; each image is tested, scanned,
   pushed or reused by its immutable commit tag, and recorded in an image artifact.
2. `setup-host` waits for Terraform and all image builds. Ansible reconciles host
   packages, Docker, the pinned Compose plugin, media storage and the shared network.
   The same Ansible installation stages optional Oracle settings if supplied.
3. `deploy-stack` downloads the complete image set and creates one release manifest.
   Missing, stale, duplicate or mismatched images fail before service deployment.
4. One SSM command downloads missing images with bounded parallelism, captures the
   prior release, and reconciles `/opt/mmiv/stack/compose.yaml` under `mmiv-stack`:
   - One `docker compose up -d --no-build --pull never` starts the whole release.
   - Registry and managed Oracle start first. Oracle has a persistent external volume.
     Its startup hook reclaims unused User Access datafile/TEMP space before readiness;
     this runs inside that startup, with no additional forward Compose invocation.
   - Backends depend on Registry health; User Access also waits for Oracle/account initialization.
   - Gateway depends on all enabled backends being healthy. Current-container Eureka
     registration, Gateway pages and routed requests must pass before the release becomes ready.
5. Verification downloads `release-state-<commit>`, checks the complete readiness
   barrier, prepares Session Manager and pinned ZAP once, and runs functional tests
   and DAST. Each service has its own reports. Gateway is scanned last.
6. Scans finish before coordinated rollback. Failed rollout recovery happens inside
   the original deployment command. A verification failure uses one additional SSM
   command to restore changed services from the release snapshot.

Compose uses health checks and `depends_on` instead of separate deployment phases.
One forward invocation reconciles the release. The host performs final HTTP health,
Eureka registration and Gateway route checks before scanning. Rollback is a separate
recovery action and may invoke Compose for individual changed application services,
using `--no-deps` to preserve unrelated containers. Functional tests and ZAP run on
the GitHub verification runner with separate SSM tunnels for each service port.

## Enabled services

| Service                     |          Host port | Deployment phase |
| --------------------------- | -----------------: | ---------------- |
| Oracle (User Access schema) | Internal 1521 only | First            |
| Service Registry            |               8761 | First            |
| Transfer                    |               8081 | Backend group    |
| Integrity                   |               8082 | Backend group    |
| User Access                 |               8083 | Backend group    |
| Reporting                   |               8084 | Backend group    |
| Discovery                   |               8085 | Backend group    |
| API Gateway                 |               8080 | Last             |

All seven application services are included in the release. User Access uses a private immutable Oracle/JWT configuration bundle. Reporting consumes RabbitMQ events; retained SQS queues are legacy storage.
Gateway readiness checks its Transfer routes; each enabled backend must also pass
its health and current-instance Eureka registration checks.

## Idempotency and recovery

- ECR tags include the commit; existing images are scanned and reused. EC2 downloads
  only missing images and completes all pulls before modifying service state.
- One Compose file is generated from the release manifest. Unchanged service image,
  configuration and environment preserve containers. Explicit changes to an existing
  Integrity environment are detected by a configuration fingerprint, without a second
  deployment invocation. Without an explicit update, its existing environment stays unchanged.
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
  cleans up a failed first application deployment. Managed Oracle and its volume are
  retained, along with their original credentials and signing keys.
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
- `scripts/cd/docker-stack-remote.py`: one deployment invocation, managed Oracle and release recovery.
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

## Transfer log queue

Terraform provisions the encrypted SQS file-log queue and DLQ. Its queue URL is
passed through Terraform job outputs and setup-host into the release manifest and
Transfer container. Apply the updated bootstrap once to grant queue provisioning
permissions. Host setup creates a private persistent Transfer log outbox without
another Ansible install. See [Transfer SQS logging](../../services/transfer-service/SQS-LOGGING.md)
for timestamp meanings and producer configuration. Reporting consumes business events through RabbitMQ; the SQS log queue is a separate operational channel.
