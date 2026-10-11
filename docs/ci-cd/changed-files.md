# Latest revision: persistent Oracle for User Access

## Changes

One managed Oracle container owns a persistent external volume and a dedicated
USER_ACCESS schema. CD generates/reuses its administrator/application passwords
and JWT keys on EC2; the runtime GitHub secret is optional. Existing signing keys
are retained when switching from an external database. Integrity production files
are byte-for-byte unchanged and its existing environment is preserved.

One forward Compose invocation uses health dependencies to start Registry/Oracle,
backends and then Gateway. Recovery retains Oracle data and credentials. Terraform
requests 8 GiB RAM and a 40 GiB encrypted gp3 disk; no AWS apply was performed.

## Modified files

- `.github/workflows/cicd.yml`
- `README.md`
- `docs/ci-cd/changed-files.md`
- `docs/ci-cd/docker-deployment.md`
- `docs/ci-cd/main-update.md`
- `infrastructure/ansible/setup-host.yml`
- `infrastructure/terraform/main.tf`
- `scripts/cd/docker-stack-remote.py`
- `scripts/cd/docker-stack-ssm.py`
- `scripts/cd/load-runtime-config.py`
- `tests/ci/test-cd-contract.py`
- `tests/ci/test-runtime-config.py`
- `tests/ci/test-stack-lifecycle.py`

## Validation

58 targeted Python tests and one upload-page test passed. These cover real RSA key
generation, credential/key reuse, retained keys when changing database hosts,
Oracle login failure gating, rollback and volume retention, one forward deployment,
Integrity configuration preservation, scanner reuse and tunnel-port handling.
The private configuration staging tests used real Ansible. Host setup syntax,
workflow/Compose YAML, Python syntax, POM XML and repository policy checks passed.
The CI shell runtime fixture suite passed; executable shell-script modes are retained
in the ZIP.

No production Java code changed. Docker/Oracle startup, live AWS/SSM, Terraform
apply and DAST were not executed locally; CI runs Oracle-backed integration tests
against the same supported Oracle image used by CD.
