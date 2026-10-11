# SQS provisioning permission repair

The full bootstrap job is disabled, so its existing Terraform-role SQS policy was
not applied. Added an idempotent pre-plan IAM policy reconciliation step and a
shared JSON policy template used by both the script and bootstrap. Policy resources
are limited to mmiv-file-logs and mmiv-file-logs-dead-letter. No new GitHub secrets.

Changed workflow, bootstrap policy/template and documentation, added the CD helper
and CI regression tests. Queue definitions, EC2, services, Oracle and Integrity
remain unchanged. Seven permission-helper tests plus workflow contract checks pass.
No live IAM policy or AWS resource was changed. An IAM administrator must intervene
if the Terraform role cannot read/write its named inline policy. IAM propagation
can delay SQS permission availability.

The supplied apply log reports successful EC2 creation followed by queue 403.
Preserve Terraform state and generate a new plan when retrying; do not delete state
or reuse the saved plan from the failed apply.

---

# Oracle startup memory headroom

Raised managed Oracle and CI Oracle container memory/combined-swap limits from
2 GiB to 3 GiB. Updated lifecycle/contract assertions and the actual CI Oracle
storage test's resource-budget check. Current deployment documentation reflects
3 GiB while historical change notes retain the prior configuration.

The uploaded log has a 1.49 GiB SGA followed by ORA-03113 after mounting. Memory
pressure under the 2 GiB container cap is suspected; no OOM event or Oracle alert
log has been supplied to confirm it. Local lifecycle/contract checks pass. Real
Oracle startup must be rerun in CI; this update does not claim a confirmed live fix.
User Access 512 MiB, file/schema quotas, faststart, Integrity, EC2 8 GiB RAM/30 GiB
disk, and the single forward deployment remain unchanged. No AWS apply was run.

---

# GitHub workflow context correction

Moved TF_PLUGIN_CACHE_DIR from terraform-validation job env to the Terraform
validation step env. runner.temp is valid at step scope, but unavailable in
job-level env; the previous placement caused GitHub to reject the entire workflow.
The shared provider cache and isolated Terraform runner are retained.

Added regression coverage rejecting runner context in workflow/job env and checking
the step-scoped cache value. Local workflow contract/cache checks and YAML parsing
passed. GitHub plugin returned 404 for the requested repository/run, so this update
is delivered in the ZIP; no remote commit or workflow rerun was performed.

---

# Root disk budget reduced to 30 GiB

Changed infrastructure/terraform/main.tf root_block_device.volume_size from 40 to
30 GiB. EC2 stays t3.large with 8 GiB RAM. Oracle faststart, resource/storage budgets,
persistent data, Integrity and deployment ordering are unchanged.

30 GiB is a light-load demo planning budget; actual host usage was not measured.
Existing AWS disk state was last reported at 8 GiB. If it is already above 30 GiB,
retain its actual larger size in Terraform because EBS cannot shrink in place.
No AWS apply or volume migration was performed.

---

# Resource budgets for a 20,000-record demo

Managed Oracle now has a 2 GiB container memory limit. User Access has a 512 MiB
container limit, 256 MiB maximum JVM heap, four JDBC connections and 24 Tomcat
threads. Only these two containers receive resource changes.

The existing reclaim routine now applies a 128 MiB USER_ACCESS schema quota,
removes a direct UNLIMITED TABLESPACE grant if present, and limits USERS/TEMP file
growth to 512/128 MiB per file in 8 MiB increments. Allocated data is preserved;
existing oversized files retain enough room, while an existing schema allocation
over quota fails rollout before resizing. There is no hard 20,000-row limit.

CI runs Oracle at 2 GiB and validates 20,000 indexed sample rows, quotas, retained
system files, data reclamation and repeat execution. Local deployment lifecycle
and contract checks pass; actual Oracle SQL and memory-constrained startup require
CI/deployment verification. No live AWS or database changes were applied.

Faststart, Integrity, one forward Docker deployment, and EC2 8 GiB RAM/40 GiB disk
remain unchanged. Total Oracle disk usage still includes its system files/image.

---

# Latest fix: Oracle destination-assignment integration assertions

The uploaded CI log shows successful Oracle connections and three passing login
integration tests. Two repository integration tests failed because DestinationAccess
uses object-identity equality, while database reads construct different objects.

Changed only the assertions in
`services/user-access-service/src/test/java/mmiv/user_access_service/repository/OracleUserRepositoryIT.java`:
compare destination/permission pairs by value, independent of ordering, and assert
the exact assignment counts. Authorization allow/deny checks remain intact.
Production service code, Integrity and deployment configuration are unchanged.

A Java compiler regression check using the actual model classes and the updated
comparison helper reproduced the old identity failure, passed reloaded/reordered
assignments, and rejected incorrect destinations and permissions. Repository checks
passed. The full Oracle-backed integration suite was not rerun locally and must
run in CI. Mockito/Caffeine warnings in the log are separate from these failures.

---

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
