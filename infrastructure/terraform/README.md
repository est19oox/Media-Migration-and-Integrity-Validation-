# MMIV Terraform

This directory is the active Terraform root used by the GitHub Actions deployment workflow.

The deployment workflow in `.github/workflows/cicd.yml` always runs Terraform first and only
starts the microservice deployment matrix after Terraform succeeds.

The one-time bootstrap in `infrastructure/bootstrap` must be applied before GitHub Actions can
assume the Terraform and application OIDC roles. The bootstrap creates the GitHub OIDC provider,
GitHub Actions roles, and Terraform state bucket.

The application S3 bucket is intentionally fixed to `media-migration-dev-landing` so Terraform,
Ansible, EC2 IAM permissions, and the Transfer Service all use the same bucket.
