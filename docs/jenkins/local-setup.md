# Local Jenkins Setup

## Purpose

This guide starts the MMIV Jenkins environment on Docker Desktop. Jenkins is configured from files in this repository and creates a GitHub-backed multibranch job named `mmiv-ci`.

The environment is intended only for project development and demonstration. It is not a production Jenkins deployment.

## Prerequisites

- Windows, macOS, or Linux with Docker Desktop or a compatible Linux Docker Engine.
- Docker Compose, included with current Docker Desktop releases.
- PowerShell 7 or Windows PowerShell 5.1 when using the provided helper scripts.
- At least 6 GB of free memory for the first image build and security tools.

Confirm Docker Desktop is running before continuing:

```powershell
docker info
docker compose version
```

## First-Time Configuration

From the repository root, create the local environment file:

```powershell
Copy-Item jenkins/.env.example jenkins/.env
```

On macOS or Linux, the equivalent command is:

```sh
cp jenkins/.env.example jenkins/.env
```

Edit `jenkins/.env` and replace the example password with a strong password used only for this local Jenkins instance. The `.env` file is ignored by Git and must never be committed.

## Start Jenkins

Run:

```powershell
./scripts/jenkins-up.ps1
```

The first build downloads Jenkins, Terraform, Semgrep, Checkov, Trivy, and Jenkins plugins, so it can take several minutes. When the health check passes, open [http://localhost:8080](http://localhost:8080) and sign in with the values from `jenkins/.env`.

Open `mmiv-ci`. Jenkins scans GitHub automatically and creates a child job for every remote branch that contains a root `Jenkinsfile`. Select **Scan Multibranch Pipeline Now** when an immediate demonstration is preferable to waiting for the next scheduled scan.

## Automatic GitHub CI

The local trigger flow is:

```text
GitHub push -> Jenkins scans within two minutes -> branch job checks out commit
-> tests and security gates run -> reports are retained in the build
```

SCM polling is used because GitHub cannot call a Jenkins service bound to `localhost`. The computer hosting Jenkins must remain online with Docker running and must be able to reach GitHub. Other contributors need only Git and GitHub access; they do not need Docker merely to push code and trigger this Jenkins instance.

A branch is discoverable only after it contains the root `Jenkinsfile`. In normal team workflow, new feature branches inherit it from `main` after the Jenkins feature is merged.

## Pipeline Stages

The initial pipeline runs these stages in order:

1. Check out the exact GitHub branch revision into an isolated Jenkins workspace.
2. Validate the repository structure and reject tracked secret/state files.
3. Run application unit tests when a supported project manifest exists.
4. Run Semgrep SAST when supported source code exists.
5. Run Trivy SCA when a supported dependency manifest exists.
6. Format-check and validate Terraform when `.tf` files exist.
7. Run Checkov against Terraform configuration.
8. Build the application image when a root `Dockerfile` or compatibility `dockerfile` exists.
9. Run Trivy against the built image.
10. Archive available scanner reports.

## SAST Ownership and Quality Gate

The security contributor owns the Semgrep rule content and false-positive tuning in `security/semgrep/semgrep.yml`. Jenkins owns the pinned Semgrep installation, invocation, failure handling, and retention of `.ci/reports/semgrep.json`.

When the team rule file exists, the pipeline uses it instead of the temporary maintained Java ruleset fallback. Semgrep configuration errors, scanner errors, and findings raised as blocking errors fail the branch build. Because an ERROR rule can block every active branch, changes to the team rule file require security review.

This version deliberately does not run `terraform apply`, access AWS, deploy an application, or perform DAST.

## Stop Jenkins

Stop the container while preserving Jenkins jobs and build history:

```powershell
./scripts/jenkins-down.ps1
```

To erase the local Jenkins volume and start again from a clean state, explicitly use:

```powershell
./scripts/jenkins-down.ps1 -Purge
```

The purge option deletes the `mmiv_jenkins_home` Docker volume and cannot recover its Jenkins configuration or build history.

## Troubleshooting

If the startup script reports that Docker is unavailable, start Docker Desktop and wait until the engine is ready. Check container state and logs with:

```powershell
docker compose --env-file jenkins/.env -f jenkins/docker-compose.yml ps
docker compose --env-file jenkins/.env -f jenkins/docker-compose.yml logs jenkins
```

If port 8080 is already used, stop the conflicting service or change only the host side of the port mapping in `jenkins/docker-compose.yml`.

If scanner downloads fail during the first image build, verify internet access and rebuild with:

```powershell
docker compose --env-file jenkins/.env -f jenkins/docker-compose.yml build --no-cache
```

## Security Boundary

The Jenkins container runs as root and mounts the Docker socket so it can build and inspect application images. Access to the Docker socket is effectively administrative access to the local Docker engine and host-managed containers. The Jenkins web port is therefore bound only to `127.0.0.1`, anonymous access is disabled, and this environment must not be exposed to an untrusted network.

For a production design, use isolated, short-lived Jenkins agents with restricted permissions instead of mounting the host Docker socket into the controller.
