# MMIV documentation

[English](README.md) | [简体中文](README.zh-CN.md)

Use this index to open maintained guides. English and Simplified Chinese links
are paired for every guide.

## Architecture

- [Architecture](architecture.md) | [简体中文](architecture.zh-CN.md) — services,
  request flow, data stores, and deployment boundary.
- [Event v2 contract](contracts/events/v2/README.md) |
  [简体中文](contracts/events/v2/README.zh-CN.md) — publisher rules and schema.
- [Event JSON Schema](../contracts/events/v2/mmiv-event.schema.json) — machine-
  readable contract.

## Development

- [Local setup](development/local-setup.md) |
  [简体中文](development/local-setup.zh-CN.md) — prerequisites, frontend,
  service tests, and integrated runtime configuration.
- [Local Oracle database](database/README.md) |
  [简体中文](database/README.zh-CN.md) — local database container,
  connection details, and volume safety.

## Services

- [Service catalog](services/catalog.md) |
  [简体中文](services/catalog.zh-CN.md) — service responsibilities, ports, and
  dependencies.
- [API Gateway](services/api-gateway.md) |
  [简体中文](services/api-gateway.zh-CN.md) — authentication, routes,
  packaging, and acceptance checks.
- [Reporting](services/reporting.md) |
  [简体中文](services/reporting.zh-CN.md) — event processing, access model,
  persistence, and smoke tests.
- [Transfer and Integrity](services/transfer-integrity.md) |
  [简体中文](services/transfer-integrity.zh-CN.md) — transfer contract,
  retries, cleanup, and validation.
- [Integrated service flow](services/integration.md) |
  [简体中文](services/integration.zh-CN.md) — authorization, metadata,
  RabbitMQ, and reports.
- [Circuit breakers](services/circuit-breakers.md) |
  [简体中文](services/circuit-breakers.zh-CN.md) — covered calls and outage
  behavior.

## Testing

- [Testing and formatting](testing/README.md) |
  [简体中文](testing/README.zh-CN.md) — Maven, CI, test layers, and formatters.
- [API and DAST validation](testing/api-validation.md) |
  [简体中文](testing/api-validation.zh-CN.md) — endpoint fixtures, deployed
  scans, and evidence.

## Deployment and operations

- [Docker release](deployment/docker-release.md) |
  [简体中文](deployment/docker-release.zh-CN.md) — hosted release, readiness,
  and rollback.
- [Gateway access](deployment/gateway-access.md) |
  [简体中文](deployment/gateway-access.zh-CN.md) — browser and SSM tunnel
  access.
- [Login and protected access](deployment/login-access.md) |
  [简体中文](deployment/login-access.zh-CN.md) — browser sessions and
  authorization.
- [Ansible host preparation](deployment/ansible.md) |
  [简体中文](deployment/ansible.zh-CN.md) — active host preparation and
  protected configuration staging.
- [Manual Ansible role](deployment/ansible-manual-role.md) |
  [简体中文](deployment/ansible-manual-role.zh-CN.md) — retained manual
  deployment and regression-test behavior.
- [Terraform](deployment/terraform.md) |
  [简体中文](deployment/terraform.zh-CN.md) — active Terraform root and
  existing EC2 protection.
- [Optional local Jenkins](jenkins/local-setup.md) |
  [简体中文](jenkins/local-setup.zh-CN.md) — local demonstration controller;
  GitHub Actions remains the hosted CI/CD path.
## Administration

- [Administrator access](admin/administrator-access.md) |
  [简体中文](admin/administrator-access.zh-CN.md) — bootstrap, invitations,
  password resets, and destination permissions.
- [Folder workspace](admin/folder-workspace.md) |
  [简体中文](admin/folder-workspace.zh-CN.md) — browser workflows, permissions,
  file operations, and verification cleanup.
