# API and DAST validation

[English](api-validation.md) | [简体中文](api-validation.zh-CN.md)

## Endpoint tests

`scripts/cd/test-endpoints.py` reads the machine-readable cases from
`tests/api/endpoints.json`. The deployed verification workflow runs the
configured endpoint cases against the services it has made available. Keep
the fixture data aligned with implemented routes and response contracts.

The integrated flow uses the Gateway's explicit routes. Transfer operations
are metadata-authorized; use the current
[service integration contract](../services/integration.md) rather than legacy
examples that accept arbitrary source paths or destination keys.

## DAST

The release verification workflow runs functional checks and OWASP ZAP scans
after the deployed readiness barrier. It uses baseline scans for the other
services and an API scan for Transfer. The Transfer API scan imports
`tests/api/transfer-openapi.json`; its hook performs an authenticated multipart
fixture upload before the scan, checks integrity and checksum, then seeds
content, download, and byte-range reads using the returned file ID. It saves
`upload-seed.json` as evidence. A missing or unsuccessful seed fails the
Transfer API scan.

These upload-oriented checks create objects in S3. Use the workflow's
dedicated verification account, reserved destination, run tag, and cleanup
process; do not point active scans at a production bucket without an approved
cleanup plan. The scan reports, upload seed evidence, and deployment
verification logs are retained as workflow artifacts.

A finding report is not proof of complete coverage or a clean security
assessment; review the workflow result and scan logs.

## Local verification limits

Unit tests do not prove live EC2, SSM, AWS IAM, S3, or ZAP behavior. Integrated
acceptance tests require the local application stack and external-service
fixtures described by their scripts. For the full deployment readiness and
rollback sequence, see [Docker release deployment](../deployment/docker-release.md).
