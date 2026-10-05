# Bootstrap configuration and recovery

bootstrap/main.tf defines the Terraform state bucket and its public access block, encryption and versioning. bootstrap/github.tf defines GitHub OIDC and deployment roles/policies. IAM user/group configuration remains in infrastructure/terraform/modules/iam/main.tf and must not be copied into the bootstrap root module.

The uploaded bootstrap/main.tf incorrectly contained IAM user module code. This removed aws_s3_bucket.terraform_state and referenced undeclared module variables. Restoring the state-bucket configuration fixes the reported import address failure. All copied IAM resource definitions were confirmed present in the existing IAM module; that module was not changed.

The recovery workflow now runs terraform validate after init and before imports, so missing variable declarations and other configuration errors stop before any state writes.

If original bootstrap state is lost and these resources are not managed by another active state: commit the corrected main.tf and recovery workflow, run Actions > Recover bootstrap state > Run workflow > main, review its plan, then manually run MMIV Microservices CI/CD. Missing MMIVAppS3DeployPolicy is expected: normal bootstrap apply creates it after recovery.

The backend is bucket mmiv-terraform-state-275069781906, key bootstrap/terraform.tfstate. Application state uses a separate key. Recovery imports existing AWS resources and writes state; it does not apply infrastructure changes. Existing GitHub OIDC authentication and IAM/S3 permissions are required.

If the original state is available, migrate it instead with terraform init -migrate-state in its original working directory, preserving a protected backup. Do not commit state to Git.

Local verification: HCL parsing, recovery import-address matching, bootstrap variable-reference checks, workflow YAML and shell syntax, Terraform formatting. Full provider validation and AWS recovery were not executed locally.
