# Integration validation — 2026-10-12

## Scoped verification cleanup

The affected Maven builds passed 341 executed tests across Discovery (75), Transfer (122), and Reporting (144), with zero failures/errors. Twelve optional Oracle tests were skipped. The cleanup helper, DAST tunnel, stack lifecycle and endpoint-runner Python suites passed 58 tests. Bash syntax and Terraform formatting checks passed.

The seven-service integration test with real Oracle, RabbitMQ and MongoDB and a local S3 HTTP fixture passed scoped cleanup: the tagged object's metadata, report and migration audits were deleted, an untagged object's data and another owner's same-run plan survived, closed-run allocations were rejected, and repeat cleanup succeeded. It also passed redirects, admin delegation, permission checks, transfer/integrity/reporting, all-service logs, broker recovery and generated-account deletion. Unit tests cover delayed-event and projection races and exact S3 version/delete-marker selection. Actual AWS S3, SSM, full ZAP scanning and EC2 deployment were not run for this change.

## Earlier integration baseline

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

## Transfer strategy and factory refactor — 2026-10-12

The Transfer Maven package build passed with 121 tests, zero failures/errors/skips. Strategy tests now exercise S3 storage receipts and exact cleanup references; source-preparation tests exercise media validation, identical bytes/checksums across local and multipart inputs, stable snapshots, bounded actual streams, and cleanup on read/close failures. Factory tests verify backend registration, duplicate rejection, and registry immutability. Existing controller, integrity retry, guarded cleanup, security, and file-read suites also passed.

The rebuilt Transfer JAR ran in the seven-service live stack with real Oracle, RabbitMQ, MongoDB, and the SDK S3 HTTP fixture. Metadata-directed transfer, integrity checks, completed reporting, immediate permission revocation, all-service logging, broker outage recovery, exact login redirects, and generated-account deletion passed. No AWS deployment was performed locally. Oracle Maven suites were not repeated for this Transfer-only refactor.

## Explicit strategy selection — 2026-10-12

The factory now injects the concrete S3 strategy and selects it with an explicit backend switch. Strategy-list registration is removed. The alternate Azure branch remains commented and requires an implementation plus compatible destination, integrity, and cleanup support before enabling. All 120 Transfer tests passed with zero failures/errors/skips, including Spring context startup, explicit factory selection without probing storage, controller uploads, and integrity retry/cleanup. No live-stack or AWS deployment was repeated for this selection-only change.

## Administrator login and delegated invitations — 2026-10-12

User Access packaged successfully with 115 tests and Gateway with 5 tests; zero failures/errors/skips. Authorization tests cover current database ADMIN role checks, demotion/inactive status with existing signed JWTs, ordinary-user denial, permission grant/downgrade/revocation, sanitized lookup responses, wrong-account/expired/reused invitations, preserved password hashes, and secret-free invitation audit events. Bootstrap tests cover existing history/admin preservation, occupied usernames, weak configured passwords, and hashing on initial creation.

All 41 deployment lifecycle tests, 3 operator-bootstrap tests, and 16 browser tests passed. The 3 private configuration staging, 5 deployment configuration, and 7 CD contract checks also passed. The deployment checks verify stable owner-only default-admin credentials, User-Access-only credential injection, no plaintext secrets in Compose/state, repeated rollout preservation, and refusal to regenerate missing credentials.

The real Oracle/RabbitMQ/MongoDB seven-service test passed default-admin password login, account-bound single-use delegation to an external account, and destination grants/revocation through that external administrator's authenticated API. The same run passed metadata-directed transfer, integrity, reporting, current permission revocation, login redirects, all-service logs, broker recovery, and verification-account cleanup. Oracle Maven suites and AWS SSM execution were not repeated; SSM command construction was checked locally. EC2 bootstrap remains a deployment action, and invitation email delivery is manual copy/share because no email provider is configured.
