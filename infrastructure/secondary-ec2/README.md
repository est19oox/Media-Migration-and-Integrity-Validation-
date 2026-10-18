# MMIV secondary EC2 (isolated Terraform state)

This folder is a separate Terraform root from `infrastructure/terraform`. It references the existing MMIV VPC, Internet Gateway and IAM instance profile through AWS data sources, without managing them.

- EC2: t3.medium, Amazon Linux 2023, 30 GiB encrypted gp3.
- Networking: subnet 10.0.2.0/24 in an AZ different from the primary, with public IPv4 for outbound HTTPS.
- Security group: **no inbound access**; outbound TCP 443 only.
- Management: SSM through existing `MMIV-EC2` IAM instance profile.
- State: S3 key `secondary-ec2/terraform.tfstate`, isolated from the primary `terraform.tfstate`.
- Cost: EC2, EBS, and public IPv4 charges apply. Stop the instance when unused; storage charges remain.
- This does not yet deploy Docker, an ALB, database replication or application services. It is **not full HA**.

## Safety checks

Do not run the normal `infrastructure/terraform` apply for this work. Do not merge secondary resources into the shared Terraform root.

Set `TF_VAR_primary_instance_id` to the existing EC2 instance ID before planning. The instance ID is not a secret.

```bash
cd infrastructure/secondary-ec2
terraform init
terraform fmt -check
terraform validate
terraform plan -out=secondary.tfplan
terraform show secondary.tfplan
```

Before applying, confirm that the plan contains **5 additions, 0 changes, 0 deletions** and that it refers to the intended VPC, primary AZ, IAM instance profile and security group. Never apply a plan generated from an unexpected or empty state. Apply only after explicit review:

```bash
terraform apply secondary.tfplan
```

`prevent_destroy` intentionally prevents accidental instance destruction. Stop/start the EC2 through AWS when not in use; Terraform does not schedule it.

The existing AWS OIDC trust policy may not authorize GitHub Actions from this fork. The initial workflow should therefore use local CloudShell credentials for a read-only plan until authorization is separately reviewed.

## GitHub Actions (recommended)

`.github/workflows/secondary-ec2.yml` installs Terraform on a GitHub runner and provides two manually triggered operations: `plan` and `apply`. It never runs on normal pushes and never touches the primary Terraform root.

**Before it can access AWS:** create a dedicated GitHub OIDC role for the `est19oox` repository, with the minimum permissions for the isolated secondary resources and its state key, then configure the repository Actions variable `SECONDARY_TERRAFORM_ROLE_ARN`. Do not reuse a broad production Terraform role without review. The existing AWS role's OIDC trust may not authorize this fork.

**Before allowing apply:** create the GitHub Environment `secondary-ec2-apply`, restrict it to `feature/secondary-ec2-isolated`, and configure required reviewers. Without these protection rules, the environment name alone does not provide approval.

From Actions, choose **Secondary EC2 Infrastructure (isolated)** > **Run workflow** on the feature branch. Enter primary instance ID `i-0674efed441197be5` and choose `plan` first. The workflow checks that exactly five resources are created and none are updated or deleted. To provision, choose `apply` and enter `PROVISION-SECONDARY-EC2`; the workflow regenerates and checks the plan in the approval-gated job before applying. **An apply launches a billable running EC2 instance.**

This initial-create-only workflow deliberately refuses subsequent apply runs, updates, or deletions. Stop the EC2 through AWS when not in use. Review the actual plan and costs before first apply.
