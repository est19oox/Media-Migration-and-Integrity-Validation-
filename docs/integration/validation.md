# Integration validation — 2026-10-12

- 464 Java tests passed across all seven services, including 9 User Access and 12 Discovery tests against real Oracle. No Java tests failed or were skipped in the final run.
- 91 Python deployment, configuration, authentication, permission-grant, and endpoint-runner regression tests passed.
- 10 browser tests passed, including destination permission selection and denied upload controls.
- The real Docker Compose rollout test passed: repeated deployment preserves containers, changed images roll out, and failed health checks restore the previous release.
- The generated production Compose configuration validated for all seven applications plus Oracle, RabbitMQ, and MongoDB.
- The production Mongo bootstrap script created its application user and authenticated successfully.

The live test ran all seven built JARs with real Oracle, RabbitMQ, and MongoDB. It verified a custom JWT issuer, denied destination access, rejection of arbitrary destination keys, metadata-directed transfer, actual destination-byte checksums, a completed report, immediate permission revocation for existing JWTs, logging from every service, delivery of a specific queued event after a broker outage, deduplication, and deletion of the generated account.

The S3 HTTP fixture implements AWS streaming chunk decoding and real SDK requests. This validates service integration locally; AWS IAM, EC2/SSM execution, and the full ZAP scan were not run from this workspace. Those remain GitHub Actions deployment checks.

## CI security and login redirect correction — 2026-10-12

All seven services now resolve RabbitMQ Java client `5.34.0`. Trivy `0.74.0` scanned all seven Maven manifests with a freshly downloaded vulnerability database and the populated local Maven dependency cache: zero HIGH/CRITICAL findings. No vulnerability suppressions or security gates were changed.

Anonymous `/upload.html` requests now return `302` with the exact origin-relative `Location: /login.html`, both directly from Transfer and through Gateway. The live stack verifies both responses without following redirects. The endpoint runner retains `Location` in its report when that header is asserted, including failed assertions; it does not capture cookies or authentication headers. All eight endpoint-runner regression tests passed.

The rebuilt stack also passed metadata-directed transfer, integrity verification, completed reporting, current permission revocation, logging from all seven services, RabbitMQ outage recovery, and generated-account deletion. Deployment on EC2 still requires the GitHub Actions rollout.
