# Automated bootstrap updates

The deployment workflow runs Bootstrap IAM update, application Terraform, then transfer-service deployment. GitHubActions-MMIV-Terraform must already exist and trust the repository. It must have permissions to manage the bootstrap resources and access their remote state.

Bootstrap state is stored in bucket mmiv-terraform-state-275069781906 at bootstrap/terraform.tfstate. Application state uses the separate terraform.tfstate key. Keep the state and its S3 version history; never commit state to Git.

bootstrap/main.tf defines the state bucket, public access block, encryption and versioning. bootstrap/github.tf defines GitHub OIDC and deployment roles/policies. IAM user/group configuration belongs in infrastructure/terraform/modules/iam/main.tf.

## Normal operation

After bootstrap state has been migrated or recovered successfully, run MMIV Microservices CI/CD. The bootstrap job checks that baseline resources are tracked, then plans and applies bootstrap updates before application deployment. Routine deployments do not require imports.

The one-time Recover bootstrap state workflow and scripts/bootstrap/recover-state.py have been removed. This cleanup does not establish that recovery succeeded in AWS; enable normal deployment only after confirming bootstrap state is populated. If state is later lost, restore an appropriate S3 state version or recover it from a protected backup before applying changes.

Preserve backend.tf, all Terraform resource definitions, and the bootstrap job in cicd.yml. Do not remove the state-verification step to bypass missing state.
