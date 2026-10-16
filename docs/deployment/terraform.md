# MMIV Terraform

[English](terraform.md) | [简体中文](terraform.zh-CN.md)

This directory is the active Terraform root used by the GitHub Actions deployment workflow.

The deployment workflow in `.github/workflows/cicd.yml` always runs Terraform first and only
starts shared host setup and stack deployment after Terraform succeeds. Image
builds can run concurrently with Terraform.

The one-time bootstrap in `infrastructure/bootstrap` must be applied before GitHub Actions can
assume the Terraform and application OIDC roles. The bootstrap creates the GitHub OIDC provider,
GitHub Actions roles, and Terraform state bucket.

The application S3 bucket is intentionally fixed to `media-migration-dev-landing` so Terraform,
Ansible, EC2 IAM permissions, and the Transfer Service all use the same bucket.

## Preserve the EC2 host

The EC2 resource ignores AMI, user-data and public-IP association drift and uses
`prevent_destroy`.
A newer Amazon Linux AMI is used only when creating a host, rather than replacing
the existing host on routine deployments. Public-IP association is configured
for new hosts but is not reconciled by replacing an existing host. In particular,
a stopped host can report `associate_public_ip_address = false` after its
automatic public IPv4 address is released; this must not block Terraform before
the workflow's existing start-instance step can run. This exception does not
repair a host that genuinely lacks outbound connectivity: that requires a separate
network configuration repair. Other changes requiring replacement
fail the plan instead of terminating the instance. Security patches and deliberate
host replacement remain separate maintenance operations.

After initialization, CI runs `scripts/cd/adopt-existing-ec2.sh` before planning:

- If an untracked, nonterminated `Name=MMIV-EC2` instance exists, import it into
  `aws_instance.mmiv` rather than creating another instance.
- If no matching instance exists, allow Terraform to create one.
- If multiple matching instances exist or state points to a different instance,
  stop and require state reconciliation. AWS/state access errors also stop CI.

The plan guard rejects EC2 deletions and replacements before apply. Deployment
uses `terraform output -raw mmiv_instance_id`, not the first instance returned by
a tag search. A stopped managed instance is started and reused.

Importing EC2 does not import its VPC, subnet, IAM resources, or other dependencies.
An existing host whose dependencies do not match this configuration can produce
a blocked replacement plan; reconcile those resources rather than bypassing the
guards or creating a duplicate host. Keep the existing S3 backend and its lock.
These checks protect this workflow, not manual AWS deletion or removal of the
Terraform resource configuration.
