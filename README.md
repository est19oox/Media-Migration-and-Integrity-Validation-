# Media Migration and Integrity Validation

MMIV migrates configured media files from local sources to Amazon S3 and verifies transfer integrity.

## Jenkins

The project includes a reproducible local Jenkins environment and an automatic GitHub multibranch CI security pipeline. Jenkins checks out and records the exact GitHub SHA, runs tests plus Semgrep, Trivy, and ZAP gates where applicable, and archives the evidence for review.

Jenkins never pushes, merges, or approves pull requests. A human reviews and merges the pull request into `main` only after reviewing exact-SHA evidence from the corresponding pushed branch build. Contributor-owned application, Docker, Terraform, Ansible, AWS, and deployment failures go back to their owners; Jenkins owns orchestration and evidence rather than those subsystem implementations.

See [Local Jenkins Setup](docs/jenkins/local-setup.md) for prerequisites, GitHub credential configuration, branch discovery, gate behavior, the `scripts/cd/trigger-deployment.sh <full-commit-sha>` handoff, reports, local operation, and Docker-socket limitation.
