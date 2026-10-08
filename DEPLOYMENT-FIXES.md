# Deployment corrections

This package fixes the ECR error and the deployment defects from the supplied project and Terraform plan. Per your instruction, the planned EC2 replacement is retained. It has not been applied to AWS.

## Changed files

| File | Change |
| --- | --- |
| `.github/workflows/cicd.yml` | Serializes the service matrix with `max-parallel: 1`; waits for SSM and cloud-init; enables the existing ZAP baseline DAST step against `/actuator/health` before rollback. |
| `.gitignore` | Excludes `tfplan` and `tfplan.*` saved plans. |
| `infrastructure/ansible/rollback.yml` | Decodes the previous image without deleting `n` characters from its name. |
| `infrastructure/terraform/main.tf` | Matches ECR's existing AES256 encryption and prevents ECR destruction. Leaves the original EC2 configuration unchanged: the planned replacement for EBS optimization and root encryption is intentional. |
| `infrastructure/terraform/modules/s3/main.tf` | Reads the root-owned bucket through a data source; retains folder-object creation. Uses `removed` blocks with `destroy = false` for five legacy bucket resources. |
| `infrastructure/terraform/modules/s3/outputs.tf` | Reads bucket outputs from the data source. |
| `infrastructure/terraform/modules/s3/versions.tf` (new) | Requires Terraform >= 1.7 for the safe ownership migration blocks. |
| `scripts/cd/wait-for-ssm.sh` (new) | Waits for a connected SSM agent, then runs and checks `cloud-init status --wait` through SSM. Uses permissions already declared for the deployment roles. |
| `DEPLOYMENT-FIXES.md` (new) | Change list, operating instructions and verification limitations. |
| `infrastructure/terraform/environments/dev/tfplan` (removed) | Deletes the stale saved plan from the source package. |

## Before deployment

1. Commit these changes. A push to main triggers the existing workflow. To inspect the plan before the workflow applies it, run Terraform manually from `infrastructure/terraform` with the same AWS account, region and backend first. Always generate a fresh plan; do not reuse a plan from the old ZIP.
2. Confirm the new root plan does not replace ECR. ECR has `prevent_destroy`; EC2 does not. EC2 replacement remains enabled as requested. Preserve required local media and rollback records before applying: replacement does not migrate `/var/lib/mmiv` to the new host. A failed prior apply may have completed some changes; the current state is authoritative.
3. Root Terraform owns the landing bucket. If the separate dev configuration was previously applied, update it to this version and run `terraform init`, `terraform plan -out=tfplan`, review, then `terraform apply tfplan` from `infrastructure/terraform/environments/dev`. Supply its existing input values. The five `removed` blocks forget old bucket ownership without deleting the AWS resources. Folder objects remain managed by dev. Do not run an old dev checkout against that state afterward. Avoid overlapping root/dev applies during migration.
4. No IAM changes are needed for the new readiness script: it uses the existing `ssm:GetConnectionStatus`, `ssm:SendCommand` and `ssm:GetCommandInvocation` permissions. Bootstrap remains commented out as in the original workflow.

## Deliberate behavior

- EC2 configuration is identical to the original uploaded project. Terraform may replace the instance for EBS optimization, root disk encryption or an AMI change. The requested replacement is not blocked or ignored. The new host uses encrypted root storage and optimized EBS. This package does not migrate local media or rollback records and cannot recover data from an already destroyed host.
- ECR remains encrypted with AES256. Migrating to KMS requires a separate repository/image migration; force deletion is not enabled.
- The root S3 encryption/versioning/lifecycle settings are unchanged by this patch. Only the duplicate dev bucket ownership is removed.
- DAST uses the existing ZAP baseline script, with its existing warning policy (`-I`). It is a baseline scan of the reachable health endpoint, not authenticated coverage of the transfer API. ZAP failures or execution errors fail the step and activate the existing rollback step after a successful Ansible deployment.
- The service matrix is serialized to avoid concurrent host package installation and to reduce scan contention. This increases workflow duration.

## Verification

Passed locally:

- Bash syntax checks for all project `.sh` scripts.
- YAML parsing for Ansible and GitHub workflow files.
- Execution of the corrected image-decoding line, confirming an image name containing `transfer-service-main` is unchanged.
- Mocked SSM script runs covering connection becoming available, connection timeout, cloud-init failure, and AWS permission failure.
- Workflow checks for serial deployment, readiness before Ansible, and DAST before rollback.

Not run: Terraform fmt/validate/plan (Terraform is unavailable in the editing environment), Ansible execution, AWS deployment, Docker image pulls, live ZAP scans, or Java builds. This is a scoped deployment correction, not a complete project audit.

## Database follow-up correction

- Replaced `database/docker-compose.yml` with Oracle Compose YAML, a required password, localhost port 1521 and the existing named persistent volume.
- Updated `database/setup-db.ps1` and `database/setup-db.sh` to use the same Compose file from any working directory and check command failures.
- Added `database/README.md` with startup and existing-container migration instructions.
- YAML parsing, Bash syntax and mocked Bash setup checks passed. Docker/Compose runtime and PowerShell execution were unavailable here, so a real Oracle startup was not tested.
- The requested EC2 replacement and previous ECR fix remain unchanged.

## Redis setup follow-up

Changed files:

- `.github/workflows/cicd.yml`: adds one Redis setup job after Terraform; service deployments depend on it and reuse its instance ID.
- `infrastructure/ansible/requirements.yml`: adds `community.docker`.
- `infrastructure/ansible/roles/microservice/tasks/main.yml`: adds shared networking and Redis environment to deployment and rescue rollback.
- `infrastructure/ansible/rollback.yml`: keeps the same Redis settings during explicit rollback.
- `DEPLOYMENT-FIXES.md`: records the changes.

New files:

- `infrastructure/ansible/redis.yml`
- `infrastructure/ansible/inventory/group_vars/mmiv.yml`
- `infrastructure/ansible/roles/redis/defaults/main.yml`
- `infrastructure/ansible/roles/redis/tasks/main.yml`
- `infrastructure/ansible/REDIS.md`

See `infrastructure/ansible/REDIS.md` for credentials, persistence, application integration boundaries and EC2 replacement recovery.
