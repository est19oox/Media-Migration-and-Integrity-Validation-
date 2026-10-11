# Reporting / log service placeholder

Owned by another developer. This folder retains only its original Spring Boot
application, health endpoint, Eureka configuration and smoke test. No SQS
consumer, event store or log API is implemented here. Production CI/CD leaves the
8084 matrix entry commented out. Local Compose enables this placeholder only
with `--profile reporting`.

Transfer already publishes version 1 events to Terraform's SQS queue. See
`../transfer-service/SQS-LOGGING.md` for the message fields and timestamps.
The log service owner should implement persistence, deduplication by `eventId`,
receive/delete handling, tests and queue-scoped consumer IAM permissions before
enabling this service. Persist successfully before deleting an SQS message.

If an earlier consumer image was already deployed, commenting the matrix entry
does not stop its existing container. Stop that old container separately and retain
its data for the new service owner. This update does not delete stored log data.
