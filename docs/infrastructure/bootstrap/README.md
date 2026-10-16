# Bootstrap configuration and recovery

> **Review before use:** This contains incident-specific historical recovery
> notes and resource identifiers. It is not a verified description of the
> current AWS account or Terraform state. Confirm the live workflow, state
> backend, IAM permissions, and resources with an authorized operator before
> following any recovery steps.

GitHubActions-MMIV-Terraform must already exist and trust the repository. It must have permissions to manage the bootstrap resources and access their remote state.

bootstrap/main.tf defines the Terraform state bucket and its public access block, encryption and versioning. bootstrap/github.tf defines GitHub OIDC and deployment roles/policies. IAM user/group configuration remains in infrastructure/terraform/modules/iam/main.tf and must not be copied into the bootstrap root module.

The uploaded bootstrap/main.tf incorrectly contained IAM user module code. This removed aws_s3_bucket.terraform_state and referenced undeclared module variables. Restoring the state-bucket configuration fixes the reported import address failure. All copied IAM resource definitions were confirmed present in the existing IAM module; that module was not changed.

The recovery workflow now runs terraform validate after init and before imports, so missing variable declarations and other configuration errors stop before any state writes.

## SQS provisioning while bootstrap is disabled

The current cicd.yml leaves the full bootstrap job commented out. Its SQS policy
therefore was not applied by normal deployment, causing the reported CreateQueue 403. Before application Terraform planning, the pipeline now reconciles only
MMIV-File-Log-Queue-Provisioning on the existing GitHubActions-MMIV-Terraform role.
The script and this bootstrap root share policies/file-log-queue-provisioning.json.
The policy covers management of exactly the two MMIV file-log queues, including
tagging on creation; it grants no queue-message consumption privileges.

The role must already have iam:GetRolePolicy and iam:PutRolePolicy for this step.
The bootstrap role definition attaches IAMFullAccess, but this does not prove the
live role has that attachment. If either action is denied, an AWS administrator
must attach the scoped policy or grant management of this named inline policy.
This step cannot bypass an IAM deny, permissions boundary or organization policy.
It does not recreate the OIDC provider, IAM roles, state bucket or bootstrap state.
Re-enabling bootstrap later manages the same inline-policy name with the same
shared template. IAM updates can take time to propagate; retry after propagation
using the same backend if SQS still returns AccessDenied.

After a partial application apply, retain application state and generate a fresh
plan. The supplied log reports EC2 i-04d016dccae83bc8e created successfully before
the queue failure. Verify aws_instance.mmiv is recorded in state; do not delete
state or apply the old tfplan. If its state record is unexpectedly absent, inspect
the backend and restore/import that existing instance instead of creating another.

## Normal operation

When intentionally enabling the full bootstrap job after bootstrap state has been migrated or recovered successfully, run MMIV Microservices CI/CD. The bootstrap job checks that baseline resources are tracked, then plans and applies bootstrap updates before application deployment. Routine deployments do not require imports.

If original bootstrap state is lost and these resources are not managed by another active state: commit the corrected main.tf and recovery workflow, run Actions > Recover bootstrap state > Run workflow > main, review its plan, then manually run MMIV Microservices CI/CD. Missing MMIVAppS3DeployPolicy is expected: normal bootstrap apply creates it after recovery.

The backend is bucket mmiv-terraform-state-275069781906, key bootstrap/terraform.tfstate. Application state uses a separate key. Recovery imports existing AWS resources and writes state; it does not apply infrastructure changes. Existing GitHub OIDC authentication and IAM/S3 permissions are required.

If the original state is available, migrate it instead with terraform init -migrate-state in its original working directory, preserving a protected backup. Do not commit state to Git.

Preserve backend.tf, all Terraform resource definitions, and the bootstrap job in cicd.yml. Do not remove the state-verification step to bypass missing state. This does not establish that recovery succeeded in AWS; enable normal deployment only after confirming bootstrap state is populated. If state is later lost, restore an appropriate S3 state version or recover it from a protected backup before applying changes.

Local verification: HCL parsing, recovery import-address matching, bootstrap variable-reference checks, workflow YAML and shell syntax, Terraform formatting. Full provider validation and AWS recovery were not executed locally.
