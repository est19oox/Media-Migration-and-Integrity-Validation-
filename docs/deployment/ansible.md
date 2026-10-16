# Ansible infrastructure

[English](ansible.md) | [简体中文](ansible.zh-CN.md)

The hosted deployment workflow uses Ansible for EC2 host preparation and
protected configuration staging. It stages optional Integrity Oracle settings
with `stage-integrity.yml`, and User Access database settings and signing keys
with `stage-user-access.yml`. The application release and recovery are
performed by the Docker stack scripts over AWS Systems Manager, not by the
legacy per-service deployment role.

`setup-host.yml` reconciles the host prerequisites, Docker Compose plugin,
shared media directory, and network. The SSM inventory is generated during the
workflow; the deployment does not require public SSH or public backend ports.

The `microservice` role, `playbook.yml`, and `rollback.yml` remain available
for manual deployment and regression tests. They are not the automated
seven-service release path. See [Docker release deployment](docker-release.md)
for the active workflow and recovery behavior.
