# Local Jenkins Setup and Operating Contract

## Scope and prerequisites

This is a reproducible, local development and demonstration Jenkins controller. It is not a production Jenkins deployment. The Jenkins host needs Docker Desktop (or a compatible Linux Docker Engine), Docker Compose, PowerShell 7 or Windows PowerShell 5.1, internet access for the initial image and scanner downloads, and at least 6 GB free memory.

Confirm that Docker Desktop is ready before starting:

```powershell
docker info
docker compose version
```

Create the ignored local configuration file from the repository root:

```powershell
Copy-Item jenkins/.env.example jenkins/.env
```

Set a strong local `JENKINS_ADMIN_PASSWORD`, `GITHUB_USERNAME`, and `GITHUB_TOKEN` in `jenkins/.env`. The GitHub token is a fine-grained, read-only repository token. It must be able to read this private repository, but needs no push, administration, workflow, or pull-request approval permission. Do not commit or share `jenkins/.env`.

## Start, smoke test, stop, and persistence

Start and smoke-test the configured controller:

```powershell
./scripts/jenkins-up.ps1
```

The helper builds the image, starts Jenkins, and verifies both `http://localhost:8080/login` and the `mmiv-ci` job. Sign in at [http://localhost:8080](http://localhost:8080) with the credentials in `jenkins/.env`.

Stop Jenkins while retaining jobs, credentials, configuration, and build history in the named `mmiv_jenkins_home` Docker volume:

```powershell
./scripts/jenkins-down.ps1
```

For a deliberately clean local controller, use the irreversible purge option:

```powershell
./scripts/jenkins-down.ps1 -Purge
```

`-Purge` deletes `mmiv_jenkins_home` and its Jenkins configuration and build history. For container state or startup diagnostics, use:

```powershell
docker compose --env-file jenkins/.env -f jenkins/docker-compose.yml ps
docker compose --env-file jenkins/.env -f jenkins/docker-compose.yml logs jenkins
```

## GitHub multibranch configuration and discovery

Configuration as code creates `mmiv-ci` as a GitHub multibranch pipeline and stores the read-only token as the `github-pat` Jenkins credential. It indexes the configured repository URL, discovers remote branches that contain the root `Jenkinsfile`, and checks every two minutes. A GitHub push is therefore normally discovered within two minutes; use **Scan Multibranch Pipeline Now** in `mmiv-ci` for an immediate scan.

Jenkins runs the branch job from GitHub SCM, not from a local project snapshot. The Checkout stage uses `checkout scm`, records `TESTED_COMMIT` with `git rev-parse HEAD`, and retains that full SHA as evidence. A job tests the exact GitHub revision checked out into its isolated workspace, even when later commits arrive on the branch.

Feature branches should inherit `Jenkinsfile` from `main`; without that root file they are not discoverable. The deployment, verification, and DAST gates run for pushed `main` and `feature/*` branch jobs. Pull requests are not separately discovered by this branch-only configuration. Other discovered branches still run the common validation, test, and security stages.

## Pipeline gates and merge authority

The pipeline validates repository hygiene, runs supported application tests, Semgrep SAST, Trivy filesystem SCA, Terraform formatting/validation and Checkov when Terraform exists, builds and scans a supported container image with Trivy, requests a test deployment, verifies its tested SHA, and runs ZAP baseline DAST. A missing supported manifest or source type is reported as `SKIPPED`; a configured gate failure fails the build.

Jenkins never pushes, merges, or approves pull requests. A human reviews and merges the pull request into `main` only after reviewing the exact-SHA evidence from the corresponding pushed branch job. Jenkins does not fix, rewrite, or bypass failed contributor-owned application, Docker, Terraform, Ansible, or AWS work to make a gate pass.

## Contributor-owned deployment contract

The deployment implementation is owned outside the Jenkins CI files. Jenkins invokes this exact interface after all preceding gates pass:

```text
scripts/cd/trigger-deployment.sh <full-commit-sha>
```

The deployment owner must write a nonempty `.ci/deployment.env` containing exactly one occurrence each of these required keys; additional entries are permitted:

```text
DEPLOYED_COMMIT=<full-commit-sha>
DAST_TARGET_URL=<http-or-https-url>
```

`DEPLOYED_COMMIT` must equal `TESTED_COMMIT` and be a full 40-character lowercase Git SHA. `DAST_TARGET_URL` must be an HTTP or HTTPS URL reachable by Jenkins. Jenkins verifies both values before supplying the URL to ZAP; the trigger's output is retained as `.ci/reports/deployment.log`.

## Evidence and report retrieval

Open the pushed branch build in `mmiv-ci`, then use its **Console Output** for stage messages and **Artifacts** for retained evidence. Jenkins archives `.ci/reports/**/*` and Maven Surefire XML under `**/target/surefire-reports/**/*`, including:

- `.ci/reports/build-metadata.json` — branch, `tested_commit`, build URL, timestamp, and result.
- `.ci/reports/semgrep.json` — Semgrep SAST results when supported source is present.
- `.ci/reports/trivy-sca.json` — Trivy filesystem dependency scan results when a supported manifest is present.
- `.ci/reports/checkov.json` — Terraform/Checkov results when Terraform is present.
- `.ci/reports/trivy-image.json` — Trivy container-image results when an image was built.
- `.ci/reports/deployment.log` — contributor deployment trigger output.
- `.ci/reports/zap.log`, `.ci/reports/zap-report.json`, and `.ci/reports/zap-report.html` — ZAP DAST evidence.

The Jenkins **Test Result** view presents any retained Maven Surefire results. Use `build-metadata.json` and the console's recorded checkout to review the exact SHA before a human reviews and merges the corresponding pull request into `main`.

## Gate-to-owner troubleshooting

| Gate or symptom | Owner and next action |
| --- | --- |
| Application test failure or unsupported application command | Application code owner: correct tests, source, or the project's declared test interface. |
| Semgrep rule error, false positive, or blocking finding | Security owner: tune `security/semgrep/semgrep.yml` or remediate the finding. |
| Trivy SCA finding | Application/dependency owner: upgrade or replace the affected dependency. |
| Docker build or image-scan failure | Docker owner: correct the Dockerfile, build inputs, or image vulnerability. |
| Terraform format, validation, or Checkov failure | Terraform owner: correct infrastructure configuration; Jenkins does not run `terraform apply`. |
| Ansible command, playbook, or inventory failure | Ansible owner: correct the automation; do not add Ansible deployment logic to Jenkins. |
| AWS credentials, resource, or deployment failure | AWS owner: correct the AWS-side deployment interface; Jenkins does not receive AWS deployment credentials. |
| Missing trigger, nonzero trigger exit, missing/invalid `.ci/deployment.env`, SHA mismatch, or unreachable DAST URL | Deployment owner: implement or repair `scripts/cd/trigger-deployment.sh <full-commit-sha>` and its two-key handoff. |
| ZAP finding or failed baseline scan | Security and deployment owners: remediate the exposed test environment or security issue. |
| Branch indexing authentication failure | Jenkins host owner: check the read-only `github-pat` token and repository access in `jenkins/.env`, then restart Jenkins. |

Failures in contributor-owned commands are returned to the relevant owner with the archived report and exact tested SHA. The Jenkins owner maintains CI orchestration, credentials, host operation, and evidence retention; they do not fix contributor-owned application, Docker, Terraform, Ansible, or AWS code in Jenkins files.

## Docker socket security limitation

The controller runs as root and mounts the host Docker socket to build and inspect images. Docker socket access is effectively administrative access to the local Docker engine and host-managed containers. The Jenkins web port is bound only to `127.0.0.1`, anonymous access is disabled, and this controller must not be exposed to an untrusted network. A production design needs isolated, short-lived agents with restricted permissions instead of a controller-mounted Docker socket.
