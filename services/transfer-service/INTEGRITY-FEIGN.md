# Transfer → Integrity via Feign and Eureka

Both services already register with the existing `service-registry` (port 8761).
Transfer now enables OpenFeign and Spring Cloud LoadBalancer. `@FeignClient(name =
"integrity-service")` resolves the registered service name; it does not call the
registry itself as the API endpoint.

## Contract

After S3 upload, Transfer calls `POST /api/integrity/validate`:

```json
{
  "transferId": "generated-uuid",
  "bucket": "media-migration-dev-landing",
  "objectKey": "uploads/generated-uuid/notes.txt",
  "sourceChecksum": "64-character-SHA-256-hex-string"
}
```

Integrity returns the same `transferId`, `bucket`, `objectKey`, `sourceChecksum`,
plus `algorithm: SHA-256`, `destinationChecksum`, `valid`, and
`validationResult: PASSED|FAILED`. Its existing S3 hashing and audit logic remain
unchanged. The small changes to its DTOs/controller echo transferId and reject
invalid input. Deploy these changes together with Transfer. Legacy requests
without a transferId still work; the updated Transfer requires a matching ID.

A source path is local to Transfer and cannot be read by a service on another
host/container. Transfer hashes the exact staged file sent to S3 and sends the
checksum instead. Integrity independently streams the S3 object and hashes its
contents. For server-path transfers, a staged snapshot avoids hashing one version
of a changing source file and uploading another.

## User outcomes

The transfer/upload response retains its existing file metadata and adds:
`transferId`, `validationResult`, `success`, and `message`.

| validationResult | success | Meaning |
| --- | --- | --- |
| PASSED | true | S3 upload completed and integrity verified |
| FAILED | false | S3 upload completed but checksums differ |
| UNAVAILABLE | false | S3 upload completed; timeout, HTTP error, missing service, or invalid response prevented confirmation |

HTTP 200 carries the completed workflow outcome; clients must inspect `success`
and `validationResult`. S3 upload failure retains the existing HTTP 502 response
and never triggers an integrity call. Folder success counts only PASSED files;
its failed count includes both failures and unconfirmed validations. Each file
retains its own result, metadata, and transferId. The upload page displays these
outcomes to the user. No email/push notification service is introduced.

After upload, Transfer makes up to **three validation attempts** (one initial
attempt and two retries) with a one-second delay. Both FAILED and UNAVAILABLE
are retried. All attempts reuse the same transferId, checksum and object key;
Transfer never uploads the file again during validation. A PASSED result stops
retries immediately and retains the uploaded object.

If validation is still unsuccessful, Transfer automatically deletes the uploaded
S3 version. It uses the versionId and ETag returned by PutObject; for an unversioned
object it uses conditional If-Match deletion. A changed ETag aborts cleanup rather
than deleting another upload. This deletes the new version, not earlier versions;
a previous version may become visible again. The EC2 role in
`infrastructure/terraform/main.tf` now grants `s3:DeleteObjectVersion` on the
existing bucket alongside its existing DeleteObject permission. Apply Terraform
before deploying; other credentials must also grant these permissions.

The response adds `cleanupStatus`: NOT_REQUIRED for verified transfers, DELETED
for successful cleanup, DELETE_FAILED if cleanup could not be confirmed. DELETE_FAILED
keeps `success: false` and tells the user manual cleanup is required. The browser
hides preview/download links for unsuccessful transfers. Folder processing applies
this policy independently to each file and continues after a failure.

Cleanup also allows three attempts, one second apart, for network errors and
transient S3 errors. Access denial, object-change conflicts and other permanent
client errors stop immediately. NoSuchKey is already clean. Application-level
cleanup attempts are separate from the AWS SDK's underlying request retries.

This implementation is synchronous: the HTTP request waits for retries and
cleanup. It has no durable job, retry endpoint or transfer-status database. A
process crash or deletion failure can still require manual cleanup. For large
videos/folders, use durable asynchronous jobs in a later change. Integrity's
current endpoint reads by key, so avoid concurrent overwrites during validation;
version-specific validation would need an Integrity contract extension.

## Run

Keep this project's existing Java 25 and Maven configuration.

```bash
mvn -B -f services/service-registry/pom.xml spring-boot:run
mvn -B -f services/integrity-service/pom.xml spring-boot:run
mvn -B -f services/transfer-service/pom.xml spring-boot:run
```

Both clients use `EUREKA_DEFAULT_ZONE` (default
`http://localhost:8761/eureka/`). Set it to the registry address reachable from
each host/container. Registered instance IPs and ports must be reachable by
Transfer. Set AWS credentials/region for both services and ensure Integrity has
S3 GetObject permission. Transfer keeps its configured bucket and PutObject
permission. Eureka registration is enabled by default.

Feign timeouts are configurable with `INTEGRITY_CONNECT_TIMEOUT_MS` (3000) and
`INTEGRITY_READ_TIMEOUT_MS` (30000). The read timeout covers the S3 read/hash and
audit operation, so tune it to the largest permitted files. Feign itself keeps its default no-retry behavior; the application explicitly
retries validation without repeating uploads.

| Environment variable | Default | Allowed range |
| --- | --- | --- |
| INTEGRITY_MAX_ATTEMPTS | 3 | 1–10 total attempts |
| INTEGRITY_RETRY_DELAY_MS | 1000 | 0–5000 milliseconds |
| CLEANUP_MAX_ATTEMPTS | 3 | 1–10 total attempts |
| CLEANUP_RETRY_DELAY_MS | 1000 | 0–5000 milliseconds |

Ensure proxy/client timeouts accommodate up to three Feign read timeouts plus
retry delays and cleanup time. Each attempt can create an Integrity audit entry;
transferId provides correlation, not persisted idempotency.

## Validation

```bash
mvn -B -f services/transfer-service/pom.xml clean verify
mvn -B -f services/integrity-service/pom.xml clean verify
```

Tests cover checksums, request/response correlation, mismatch, invalid responses,
HTTP failures, upload sequencing, folder accounting and temporary-file cleanup.
`IntegrityFeignContractTest` uses a local HTTP server and a discovered test
instance to exercise the real Feign encoder/decoder and LoadBalancer without AWS.
`IntegrityControllerContractTest` checks the matching Integrity JSON contract.

Validation for the retry/cleanup update: Maven clean verify using Java 25;
Transfer: 107 tests passed, zero failures/errors. Tests cover immediate success, eventual success, exhausted mismatch,
exhausted unavailability, deletion retries/failures, version-specific and
conditional deletion, server-path transfers, folders and temporary-file cleanup.
No live AWS transfers, S3 deletion, or Terraform apply were performed.
