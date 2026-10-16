# Generic microservice deployment role (manual/legacy)

This role is retained for manual deployments and migration tests. Automatic CD uses Docker over SSM; see [the current process](../../../../ci-cd/docker-deployment.md).

Ansible handles ECR authentication, remote execution, health checks, and rollback.
Docker Compose defines the container, ports, media mount, and shared network.
Run `setup-host.yml` first; this role does not install host components.

Each service has its own Compose project (`mmiv-<service>`) and configuration
under `/opt/mmiv/<service>`, so parallel backend deployments do not modify one
another. Backend ports bind to localhost; only the gateway binds to all interfaces.

`docker_compose_v2` uses `pull: missing` and automatic reconciliation: an unchanged
image/configuration retains the existing container. A stopped container is started.
An image or Compose configuration change recreates that service. Changes to the
Integrity environment file explicitly recreate Integrity, because Compose does not
reliably detect changes in env-file contents. ECR tags must be immutable.

Containers deployed by the old `docker run` role are migrated once. The replacement
image is pulled before the old container is removed. A container owned by another
Compose project is rejected rather than deleted. Migration and subsequent updates
can briefly interrupt the selected service; this is not a zero-downtime rollout.

Before a changed deployment, the role saves the previous image, Compose file, and
Integrity environment. It restores them if the health check fails. `rollback.yml`
uses the same role for failures in later smoke/DAST checks. Unchanged deployments
preserve existing rollback backups and report `deployment_changed=false` to GitHub
Actions, preventing a later check failure from rolling back an unchanged service.

Run regression checks from the repository root with Python, Ansible, the required
collections, Python `requests` in the Ansible interpreter, and a running Docker daemon:

```sh
python tests/ci/test-deployment-config.py
python tests/ci/test-compose-runtime.py
```

The runtime test creates isolated test images, containers, and a network, then
cleans them up. It checks repeat deployments, image changes, explicit rollback,
automatic rollback after a health failure, and one-time legacy migration.
