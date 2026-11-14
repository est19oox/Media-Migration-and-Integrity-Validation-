# MMIV Next ALB — Terraform adoption candidate

This folder captures the **existing** ALB configuration exported from AWS on 14 November 2026. It is a **standalone, non-deployed Terraform root**, intentionally outside `infrastructure/terraform/` because pushes to that active directory trigger CI/CD and Terraform apply.

## Observed infrastructure
- ALB: `mmiv-next-alb` (internet-facing, Singapore, two AZs)
- HTTP listener: port 80, forwards to `mmiv-next-failover-tg`
- ALB security group: `sg-00376f4471ed9f83a`, inbound HTTP:80 and outbound TCP:8080 to VPC CIDR
- Failover target group: instance targets, HTTP:8080, health path `/actuator/health`, accepted `200-399`
- Registered targets in the uploaded export: `i-0c0494f6549a85727:8080` and `i-0dda783e05c2d95c8:8080`, both healthy
- Separate primary target group: `mmiv-primary-tg` (default port 80, not associated with an ALB in the export)

## Critical precautions
1. **Do not run `terraform apply` here yet.** The existing AWS resources are live and the active Terraform state uses an S3 backend. The import blocks are a proposal, not evidence of state adoption.
2. This root does **not** configure an S3 backend or touch the active Terraform state. Before integration, inspect the existing state for these resource IDs and reconcile VPC/subnet/security-group ownership. Never import the same resource into two independent active states.
3. `main.tf` intentionally does **not** declare `aws_lb_target_group_attachment` resources yet. The exported target-health JSON identifies the two registrations, but import ownership and lifecycle must be decided before managing them in Terraform.
4. AWS export showed inbound port 80 from both `137.132.26.214/32` and `0.0.0.0/0` in one rule. The narrower CIDR is redundant; this configuration represents effective access with `0.0.0.0/0`. Review the planned change carefully.
5. The original `infrastructure/terraform/main.tf` still allows public TCP:8080 to the EC2 host. Review tightening it to ALB-only ingress as a separate, validated change.
6. The fallback target group contains both healthy targets during normal operation, so the ALB can route to either the MMIV app or static fallback. This is health-based load balancing, **not** strict primary-preferred active/passive failover.
7. The snapshot contains no evidence of EC2 Auto Scaling or ECS Fargate configurations; those are separate IaC workstreams.

## Safe next steps
- Confirm resource ownership in the existing S3-backed Terraform state with `terraform state list` and `terraform state show` as needed.
- Validate syntax with `terraform fmt -check` and `terraform init -backend=false && terraform validate`.
- Compare every proposed resource to the live AWS settings, including ALB attributes not included in the supplied export.
- Choose a single state owner, move the reconciled configuration into the active root, and import existing resources under a reviewed change.
- Require an explicit plan review confirming **no unintended replacements or destructive changes** before any apply.

Source: user-provided AWS CLI export (`listeners.json`, `load-balancer.json`, `security-group.json`, `target-groups.json`, `target-health.json`).
