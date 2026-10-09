# Merge notes and validation

Inputs: Media-Migration-and-Integrity-Validation--main(4).zip and
Media-Migration-and-Integrity-Validation-(20261009-025726).zip.

Main was used as the baseline. Transfer, Gateway, service-registry, deployment
workflows, Ansible, related API checks and infrastructure additions come from
the user's ZIP. Files present only in main are retained, except the obsolete
Integrity S3Config location replaced by mmiv/config/S3Config.java.

The entire real Discovery Service is preserved byte-for-byte from main.
Its automatic deployment is commented out pending Oracle and media configuration;
it has no Eureka registration yet. Local Compose makes it an optional profile.

Port 8080: Terraform permits public IPv4 TCP 8080. Ansible publishes Gateway
on 0.0.0.0; other containers publish on 127.0.0.1. Rescue and rollback share
the same binding defaults. No authentication is configured on the gateway.

The user's t3.medium EC2 setting is retained (main used t3.micro).
The supplied Java 25 settings are retained. Terraform runs before host setup,
then registry, backend services, and Gateway. Redis and Transfer's Integrity
invocation remain disabled as in the user's ZIP.

Validation completed:
- YAML, JSON and Maven XML parsing; Bash syntax: 63 files passed.
- Workflow dependency and local reusable-workflow reference checks passed.
- Main Discovery byte-for-byte preservation check passed.
- Deployment/rescue/rollback Docker command tests passed (2 test methods).
- Repository structure and tracked-file policy checks passed.
- POSIX Jenkins lifecycle helper tests passed.

The CI runtime fixture tests passed after updating their SCA invocation
expectation to include the user's existing --skip-version-check flag.
These tests use mocked tools; they do not run actual security scanners.

Not executed: Maven builds, Terraform fmt/validate/plan/apply, Ansible CLI syntax
checks, Docker runtime, live AWS deployment, SAST/SCA/DAST scanners. This runtime
has only Java 17 and lacks Maven, Terraform and Ansible. The packaged workflows
retain their gates, but successful production deployment is not verified here.

No remote Git branch was merged or pushed, and no AWS resource was modified.
Apply Terraform and deploy the containers before using:
http://<EC2-public-IP>:8080/upload.html
http://<EC2-public-IP>:8080/files.html
