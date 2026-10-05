# MMIV Ansible Deployment

Ansible is the deployment layer for all MMIV microservices. Transfer Service is currently enabled;
future services use the same role contract and process.

## Deployment process

1. GitHub Actions builds and tests the selected microservice.
2. The image is pushed to ECR using the Git commit SHA.
3. The running MMIV EC2 instance is discovered by its `Name=MMIV-EC2` tag.
4. Ansible connects through AWS Systems Manager (`amazon.aws.aws_ssm`).
5. The current container image is recorded for rollback.
6. The new image is pulled and started.
7. The service health endpoint is checked.
8. If deployment or health checking fails, Ansible restores the previous image.
9. GitHub Actions can also invoke the rollback playbook after a post-deployment DAST failure.

## Future microservices

The following roles are templates only and are disabled until their services are implemented:

- integrity-service
- user-access-service
- reporting-service
- discovery-service

They deliberately use the same deployment contract as Transfer Service so deployment and CD remain consistent.

## SSM inventory

The CI inventory is generated at runtime from the EC2 instance ID. No static host IP or
`inventory.ini.example` is required. The configured S3 bucket is used by the AWS SSM Ansible
connection for temporary module transfer artifacts.
