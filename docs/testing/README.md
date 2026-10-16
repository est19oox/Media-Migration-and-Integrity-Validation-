# Testing and formatting

[English](README.md) | [简体中文](README.zh-CN.md)

## Maven services

Each service is an independent Maven project. Build and test one service from
the repository root with Java 25:

```sh
./services/user-access-service/mvnw -B -f services/transfer-service/pom.xml clean verify
```

Replace `transfer-service` with the target directory. The repository test
helper runs all service Maven projects and validates that test reports exist:

```sh
bash scripts/ci/run-tests.sh .
```

The helper requires Maven and Python 3. User Access Oracle integration tests
are opt-in through `MMIV_USER_ACCESS_ORACLE_IT=true` and require the separate
Oracle test configuration and database; the ordinary unit-test run does not
enable that profile.

Transfer's S3 unit tests use a mocked `S3Client`; its Feign contract test uses
a local HTTP server to exercise request/response encoding and service
discovery. These tests do not perform live S3 transfers. See
[Transfer-to-Integrity validation](../services/transfer-integrity.md)
for the covered behavior and commands.

## Other test layers

- `tests/api/endpoints.json` is the endpoint-test input used by
  `scripts/cd/test-endpoints.py`.
- Python tests under `tests/ci/` validate deployment helpers, CI contracts,
  and runtime configuration.
- Browser/page tests live under `tests/`; they use Node's built-in test
  runner where configured.
- Integrated acceptance scripts under `tests/api/` need the running services
  and their supporting infrastructure.
- GitHub Actions uses separate validation and release workflows. The release
  verification path includes deployed-service checks and security scanning.

See [API and DAST validation](api-validation.md) for the endpoint contract and
the evidence produced by deployed checks.

## Formatting

Install the root pinned Prettier dependency and run its check:

```sh
npm ci
npm run format:check
```

This checks repository HTML, CSS, JavaScript, JSON, Markdown, YAML, and YML
files. The full formatting check also runs Spotless for every Maven service
and `terraform fmt -check` for infrastructure Terraform. On Windows:

```powershell
.\scripts\format\check-all.ps1
```

On Linux, macOS, or WSL:

```sh
bash scripts/format/check-all.sh
```

These checks do not modify files. To apply formatting, use
`.\scripts\format\format-all.ps1` on Windows or
`bash scripts/format/format-all.sh` on Linux, macOS, or WSL.
