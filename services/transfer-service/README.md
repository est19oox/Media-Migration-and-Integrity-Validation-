# MMIV Transfer Service

Transfer executes authorized uploads and transfers to Amazon S3. It resolves migration plans through Discovery, validates stored objects through Integrity, and publishes lifecycle events for Reporting.

## API

- `POST /api/v1/transfers` with JSON `{"metadataId":"<plan-id>"}` executes a transfer for an assigned metadata plan.
- `POST /api/v1/transfers` with multipart form data accepts a `file` and optional `destinationId`.
- Folder uploads and portal operations are documented by their controllers under `src/main/java/mmiv/transfer/controller/`.

Requests are authenticated and authorized by the service; the browser frontend is not an authorization boundary.

## Build and test

From the repository root, with JDK 25:

```sh
./services/user-access-service/mvnw -B -f services/transfer-service/pom.xml clean verify
```

## Configuration

The service listens on port 8081 by default. `AWS_REGION` configures the AWS region and `MMIV_BUCKET_NAME` configures the S3 bucket. The AWS SDK default credential chain supports an EC2 instance profile; do not store AWS keys in the repository.

Transfer also requires the configured User Access and Discovery APIs, Integrity, RabbitMQ, and a writable event outbox. See [service communication](../../docs/architecture/service-communication.md) for the implemented boundaries and [SQS logging](SQS-LOGGING.md) for the separate operational file-log queue.
