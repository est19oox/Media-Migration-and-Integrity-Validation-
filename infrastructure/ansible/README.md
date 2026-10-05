# MMIV Ansible Deployment

Ansible is the common deployment layer for all MMIV microservices. Every service uses the same
`microservice` role and is selected through variables supplied by GitHub Actions.

## Deployment process

1. GitHub Actions runs Terraform first.
2. The deployment job waits for Terraform to succeed (`needs: [terraform]`).
3. Each microservice is built and tested.
4. The image is pushed to ECR using the Git commit SHA.
5. The running MMIV EC2 instance is discovered by its `Name=MMIV-EC2` tag.
6. GitHub Actions generates the AWS Systems Manager inventory with the EC2 instance ID and S3 bucket.
7. Ansible connects through AWS Systems Manager (`amazon.aws.aws_ssm`).
8. The current container image is recorded for rollback.
9. The new image is pulled and started.
10. The service health endpoint is checked.
11. If deployment or health checking fails, Ansible restores the previous image.
12. GitHub Actions can also invoke the rollback playbook after a post-deployment DAST failure.

## SSM inventory

The CI inventory is generated at runtime. No static host IP or `inventory.ini.example` is required.
The `media-migration-dev-landing` bucket is used by the AWS SSM Ansible connection for temporary
module transfer artifacts.
