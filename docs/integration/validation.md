# Integration validation — 2026-10-12

- 464 Java tests passed across all seven services, including 9 User Access and 12 Discovery tests against real Oracle. No Java tests failed or were skipped in the final run.
- 91 Python deployment, configuration, authentication, permission-grant, and endpoint-runner regression tests passed.
- 10 browser tests passed, including destination permission selection and denied upload controls.
- The real Docker Compose rollout test passed: repeated deployment preserves containers, changed images roll out, and failed health checks restore the previous release.
- The generated production Compose configuration validated for all seven applications plus Oracle, RabbitMQ, and MongoDB.
- The production Mongo bootstrap script created its application user and authenticated successfully.

The live test ran all seven built JARs with real Oracle, RabbitMQ, and MongoDB. It verified a custom JWT issuer, denied destination access, rejection of arbitrary destination keys, metadata-directed transfer, actual destination-byte checksums, a completed report, immediate permission revocation for existing JWTs, logging from every service, delivery of a specific queued event after a broker outage, deduplication, and deletion of the generated account.

The S3 HTTP fixture implements AWS streaming chunk decoding and real SDK requests. This validates service integration locally; AWS IAM, EC2/SSM execution, and the full ZAP scan were not run from this workspace. Those remain GitHub Actions deployment checks.
