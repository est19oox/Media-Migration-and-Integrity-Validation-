# MMIV Ansible Deployment

This Ansible deployment installs Docker/AWS CLI and deploys the Transfer Service container to the MMIV EC2 instance.

## Connection model

The EC2 security group does not expose SSH. GitHub Actions therefore connects through the
`amazon.aws.aws_ssm` Ansible connection plugin and AWS Systems Manager.

## CI/CD

`.github/workflows/cicd.yml`:

1. Builds and tests `services/transfer-service`.
2. Builds and pushes the Transfer Service image to ECR using the Git commit SHA.
3. Discovers the running `MMIV-EC2` instance.
4. Runs Ansible against the instance through SSM.
5. Runs a health smoke test through SSM.
