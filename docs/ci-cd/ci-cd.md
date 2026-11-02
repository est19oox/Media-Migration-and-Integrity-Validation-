# MMIV CI/CD

GitHub Actions provides the hosted validation, service-verification, and deployment workflows. The workflow definitions are in [`.github/workflows/`](../../.github/workflows/); the main pipeline is [`cicd.yml`](../../.github/workflows/cicd.yml).

## Hosted workflows

- Repository validation and formatting checks run through GitHub Actions.
- Service build and verification workflows validate the Spring Boot services.
- The main CI/CD pipeline validates Terraform and runs CI/security gates, builds service images, deploys through AWS Systems Manager, and verifies the deployed application.
- Workflows are triggered by their configured branch/path filters or manual dispatch. Required repository secrets and cloud resources depend on the selected workflow.

The deployment sequence, runtime assumptions, and recovery behavior are documented in the [Docker deployment guide](docker-deployment.md). Do not run deployment workflows without verifying the AWS account, Terraform state, and target environment.

## Optional local Jenkins

Jenkins is an optional local demonstration environment, not a second authority for hosted CI or production deployment. See [local Jenkins setup](../jenkins/local-setup.md) for its Compose-based installation and usage.
