# Media Migration and Integrity Validation

MMIV migrates configured media files from local sources to Amazon S3 and verifies transfer integrity.

## Jenkins

The project includes a reproducible local Jenkins environment and an automatic GitHub multibranch CI security pipeline. Jenkins checks out and records the exact GitHub SHA, runs tests plus Semgrep, Trivy, and ZAP gates where applicable, and archives the evidence for review.

Jenkins never pushes, merges, or approves pull requests. A human reviews and merges the pull request into `main` only after reviewing exact-SHA evidence from the corresponding pushed branch build. Contributor-owned application, Docker, Terraform, Ansible, AWS, and deployment failures go back to their owners; Jenkins owns orchestration and evidence rather than those subsystem implementations.

See [Local Jenkins Setup](docs/jenkins/local-setup.md) for prerequisites, GitHub credential configuration, branch discovery, gate behavior, the `scripts/cd/trigger-deployment.sh <full-commit-sha>` handoff, reports, local operation, and Docker-socket limitation.

## AWS Deployment

The GitHub Actions deployment workflow is `.github/workflows/cicd.yml` and runs on `main`.
Terraform runs first on every deployment and the microservice deployment job uses `needs: [terraform]`,
so Ansible cannot start unless Terraform succeeds.

The one-time bootstrap under `infrastructure/bootstrap` creates the GitHub OIDC provider and the
roles required by GitHub Actions. After bootstrap is applied, normal deployments use:

```text
Terraform → Build/Test → ECR → Ansible/SSM → Health Check → DAST → Smoke Test
```

The application bucket is `media-migration-dev-landing` and is shared consistently by Terraform,
EC2 IAM permissions, Ansible, and the Transfer Service.
