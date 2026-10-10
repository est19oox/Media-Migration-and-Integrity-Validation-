# MMIV host preparation and optional configuration

The automatic GitHub Actions CD workflow uses Docker images and Docker Compose over SSM for service deployment and rollback. Ansible is limited to necessary host preparation (`setup-host.yml`) and protected optional Oracle secret-file staging (`stage-integrity.yml`). H2 service deployments do not install or run Ansible on their service runners.

`setup-host.yml` reconciles installed Docker/AWS CLI/Python packages, the Docker service, the shared media directory and network, and the checksum-verified Compose plugin. Repeated setup does not reinstall present packages or replace an unchanged Compose version.

`stage-integrity.yml` is used only when Oracle settings are supplied. It validates complete settings, writes a private next configuration file without changing the active environment, and hides credentials from task logs. The Docker lifecycle detects configuration changes, captures the previous configuration, applies the new environment, and restores it on rollback.

The SSM inventory is generated at runtime. No public SSH or backend port is required. The S3 bucket is used for temporary Ansible module transfer.

The generic `microservice` role, `playbook.yml` and `rollback.yml` remain available as a manual/legacy path, including their existing runtime tests. The automatic service workflow no longer invokes them. See [Docker CD process](../../docs/ci-cd/docker-deployment.md) for the current pipeline.
