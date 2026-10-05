# Fixes and operating notes

Placeholder services remain disabled in the GitHub deployment matrix.

Transfer requests retain their JSON format. `sourcePath` must identify a file inside `/media` (relative paths are accepted). Stage media on EC2 under `/var/lib/mmiv/media`; Ansible mounts this directory read-only at `/media`. The API does not upload files from the client computer. Access to files and symbolic links outside the media directory is rejected. Keep the staged input directory trusted and unchanged during transfers.

Invalid requests return HTTP 400; storage failures return 502. Deployments serialize, use unique immutable image tags, wait for SSM readiness, install its plugin, and roll back only after a successful deployment followed by failed verification. Ansible handles failures within deployment itself.

ECR retains 20 images per service. Rollback still depends on the previous image remaining in ECR; increase retention for longer rollback windows.

Jenkins now builds/tests the root project and transfer service, builds its container, and deploys a temporary test container on the `mmiv-ci` Docker network. Jenkins Compose creates that network. This tests the exact checked-out commit without changing AWS. Test containers are removed in pipeline cleanup. If running Jenkins elsewhere, attach its agent to `mmiv-ci` and set MMIV_TEST_NETWORK if needed.

The AWS server intentionally has no public ingress. Use SSM port forwarding for manual API tests, or design authenticated ingress before making it publicly accessible. Example local command: `aws ssm start-session --target <instance-id> --document-name AWS-StartPortForwardingSession --parameters '{"portNumber":["8081"],"localPortNumber":["8081"]}'`.

Apply the bootstrap Terraform IAM changes before running deployment; editing the policy file alone does not update AWS. No AWS apply or deployment was performed by this repair.

## Verification performed

Passed: Terraform recursive formatting check; HCL parsing; both Ansible playbook syntax checks; inventory parsing; YAML/XML/shell syntax checks; repository structure check; POSIX Jenkins runtime helper test.

Java 25, Maven, Terraform and Ansible were installed for this repair. The earlier Maven download failure was resolved during the unit-test task: transfer-service compiles and all 57 tests now pass with Java 25 and Maven 3.9.11. Terraform init downloaded the AWS provider and providers lock verified the official checksum set, but validate rejected the cached provider checksum. Provider checksum verification was not bypassed. Docker builds and actual AWS plan/apply/SSM deployment were not run.

Run `.github/workflows/validate.yml` on GitHub to complete Maven tests, Terraform validation, Ansible checks and the Docker build without deploying. The transfer test suite now contains five focused test classes covering validation, strategy selection, orchestration, mocked S3 uploads and controller responses, plus the existing context test. No live S3 integration test was performed.

## OIDC trust alignment

Both GitHubActions-MMIV-Terraform and GitHubActions-MMIV-App now use the exact OIDC subject from the uploaded main branch: `repo:est19oox@70089857/Media-Migration-and-Integrity-Validation-@1359946801:ref:refs/heads/*`. The branch wildcard allows matching branch subjects for all branches, as in the uploaded main configuration. The deployment workflow itself remains triggered on main. GitHub must emit this subject for role assumption to succeed. Apply the bootstrap Terraform changes to update the AWS roles.
