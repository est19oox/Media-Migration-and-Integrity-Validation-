# Redis deployment

The workflow now runs:

Terraform -> redis_setup -> sequential microservice deployments -> per-service health check / DAST / rollback on failure.

The `redis_setup` job waits for SSM and cloud-init, then runs `redis.yml` once. Its EC2 instance ID is passed to every service job so all services deploy to the prepared host. Ansible creates the Docker network, named volume, credentials and Redis container, then verifies authenticated PING from a separate container on the same network. A failed readiness check blocks the service matrix.

## Configuration

- Image: `redis:8.10.1` (override `mmiv_redis_image` to use a tested image/digest).
- Container: `mmiv-redis`; network alias: `redis`.
- Docker network: `mmiv-backend`.
- Port: 6379 inside the network only; no published host port or security-group change.
- Persistence: `mmiv-redis-data` volume, AOF enabled with fsync every second.
- Redis maxmemory: 128 MB, `noeviction`; container limit: 256 MB.
- Environment: `/etc/mmiv/redis.env`, root-owned and mode 0600 under a 0700 directory.
- Redis configuration: `/etc/mmiv/redis.conf`, mounted read-only into Redis. The file is readable inside the container; its host parent directory is restricted to root.

The password is randomly generated once on each host and reused on subsequent deployments. No new GitHub secret is required. Credential-related Ansible tasks use `no_log`. Docker administrators can access container environment variables; this setup is for trusted application containers on one development host.

Application and rollback containers receive:

```
SPRING_DATA_REDIS_HOST=redis
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=<generated on EC2>
```

Both application deployment and rollback use the same shared network and environment file. Run the dependency playbook before manual deployment:

```bash
cd infrastructure/ansible
ansible-galaxy collection install -r requirements.yml
ansible-playbook -i inventory/ci.generated.ini redis.yml
```

Use a populated inventory for your EC2 instance; the workflow generates it automatically. The file `inventory/group_vars/mmiv.yml` supplies the shared network and environment-file paths to all plays.

## Scope and recovery

This change installs Redis and supplies connection settings. It does not add Spring Redis dependencies, permission-version reads, User Access Service synchronization, RabbitMQ, or token revocation logic. The existing Transfer Service will not start using Redis merely because these environment variables exist; application integration remains separate.

The Redis volume survives container restart/recreation on the SAME EC2 host. It does NOT survive the planned EC2 replacement automatically. AOF is not an off-host backup. User Access Service must be able to repopulate authorization versions from its authoritative store before Redis-backed authorization is enabled. Redis PING confirms infrastructure readiness, not that permission data is populated. Missing authorization versions must not automatically allow a request.

`noeviction` makes Redis reject writes at its memory limit rather than silently evicting authorization records. Size the limits for actual usage. Changing credentials requires coordinating Redis and all application containers; routine deployment does not rotate them.

## Verification

Locally verified YAML parsing, workflow dependency ordering, Docker arguments in deploy and both rollback paths, credential generation/reuse and configuration template checks. Docker, Ansible and AWS were not available for live Redis startup or deployment checks. No Terraform configuration was changed for Redis; the planned EC2 replacement and ECR AES256 fix remain intact.
