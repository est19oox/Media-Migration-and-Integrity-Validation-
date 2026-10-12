# Asynchronous upload and download logs

Transfer saves a JSON event in a private local outbox; a background worker sends
it to the `mmiv-file-logs` SQS standard queue. Logging Service consumes the queue,
writes its database/log record, and deletes the SQS message only after the write
succeeds. No HTTP Logging Service call runs on the upload/download request thread.

## Functions

- `FileEventLogger.upload(result, startedAt)` records a multipart upload, including
  each successfully validated folder file.
- `FileEventLogger.transfer(result, startedAt)` records a filesystem-to-S3 transfer.
- `FileEventLogger.download(operation, fileId, file, bytesServed, success, errorType, startedAt)`
  records GET download/preview requests, including failed attempts.
- `FileEventLogger.failure(...)` records rejected uploads and transfer exceptions.
- `SqsLogOutbox.enqueue(event)` persists the JSON locally without contacting AWS.
- `SqsLogOutbox.flush()` publishes pending messages in the background and removes
  them only after SQS acknowledges receipt. It retries failures on the next run.

## Exact message variable names

| Variable | Type | Meaning |
| --- | --- | --- |
| `schemaVersion` | integer | Contract version, currently `1` |
| `eventId` | string | Unique event UUID; unchanged on publication retries |
| `operation` | string | `UPLOAD`, `TRANSFER`, `DOWNLOAD`, or `PREVIEW` |
| `transferId` | string or null | Existing upload/transfer validation ID; null for downloads or failures before an ID exists |
| `fileId` | string or null | Existing file identifier when available |
| `fileName` | string or null | File name when available |
| `bucket` | string or null | S3 bucket when known |
| `key` | string or null | S3 destination/object key when known |
| `sizeBytes` | integer or null | Full file size when known |
| `bytesServed` | integer or null | Download/preview bytes accepted by server output writes; null for uploads |
| `sha256` | string or null | Upload/transfer checksum; null for downloads |
| `validationResult` | string or null | Existing Integrity result; null when not applicable |
| `cleanupStatus` | string or null | Existing cleanup result; null when not applicable |
| `success` | boolean | Final operation outcome, including integrity validation for uploads |
| `errorType` | string or null | Exception class name; exception messages and stack traces are not sent |
| `startedAt` | string | Server operation start time in ISO-8601 UTC |
| `completedAt` | string | Server operation completion/failure time in ISO-8601 UTC |

Example upload message:

```json
{
  "schemaVersion": 1,
  "eventId": "631ee358-6efe-478c-b588-919d283674b1",
  "operation": "UPLOAD",
  "transferId": "ea31c0e7-f99b-423f-bf53-2ca635487171",
  "fileId": "dXBsb2Fkcy9leGFtcGxlL25vdGVzLnR4dA",
  "fileName": "notes.txt",
  "bucket": "media-migration-dev-landing",
  "key": "uploads/example/notes.txt",
  "sizeBytes": 10,
  "bytesServed": null,
  "sha256": "84d89877f0d4041efb6bf91a16f0248f2fd573e6af05c19f96bedb9f882f7882",
  "validationResult": "PASSED",
  "cleanupStatus": "NOT_REQUIRED",
  "success": true,
  "errorType": null,
  "startedAt": "2026-10-10T06:32:58Z",
  "completedAt": "2026-10-10T06:33:01Z"
}
```

A download uses `operation: DOWNLOAD`, its own `eventId`, `bytesServed`,
`startedAt`, and `completedAt`. Download `transferId`, checksum, validation and
cleanup fields are null. Range requests generate a record for each request;
`sizeBytes` is the full object size, while `bytesServed` is that request's byte
count. Preview GETs use `PREVIEW`; HEAD requests generate no download event.
A failed read/stream uses `success: false`, retaining bytes already served.

The upload start timestamp is taken when the service method begins, after Spring
has parsed the multipart request. Folder file timestamps refer to each individual
file operation. Download completion records server-side stream completion/flush,
not proof of receipt or saving on the user's device. Anonymous operations have no
`userId`; adding authenticated user identity requires the authentication contract.
Malformed requests rejected by Spring before invoking these methods, and folder
manifests rejected before any file transfer begins, do not create file events.

## Deployment

1. Apply the updated `infrastructure/bootstrap` once using an authorized bootstrap
   identity. This grants the existing Terraform workflow role scoped SQS provisioning
   permissions; without it, queue creation can fail with AccessDenied.
2. Commit the updated project and run the normal workflow. Terraform creates the
   encrypted source queue and dead-letter queue, grants EC2 `sqs:SendMessage`, and
   outputs `file_log_queue_url`/`file_log_queue_arn`. CD passes the URL automatically
   into the Transfer container as `MMIV_LOG_QUEUE_URL`. No new GitHub secret is needed.
3. The log service owner implements the consumer separately. Reporting is disabled
   and has no consumer or event store in this version. Grant that future consumer
   queue-scoped ReceiveMessage, DeleteMessage and GetQueueAttributes permissions
   (and ChangeMessageVisibility if it extends visibility). Persist and deduplicate
   each eventId before acknowledging SQS. Only SendMessage is currently enabled.

The queue retains messages for four days; consumer failures move to the DLQ after
five receives, and the DLQ retains them for fourteen days. Both queues use SQS
managed encryption and deny non-TLS requests.

For local runs, set `MMIV_LOG_QUEUE_URL` and use AWS credentials with SendMessage
permission. If unset, logging is disabled with a startup warning. Optional settings:
`MMIV_LOG_OUTBOX_DIRECTORY` (default `/var/lib/mmiv/log-outbox`) and
`MMIV_LOG_FLUSH_DELAY_MS` (default `5000`). The Linux/Docker outbox requires a
writable directory and supports one worker/container per outbox.

## Delivery and durability

The host directory `/var/lib/mmiv/transfer-log-outbox` is mounted into the Transfer
container at `/var/lib/mmiv/log-outbox`; recreate/rollback of the container does
not remove it. Events are fsynced and atomically renamed before publication. A
process restart can resume already committed records. No SQS send occurs until
the scheduled worker runs, so SQS latency does not block file operations.

Deduplicate by `eventId` in Logging Service, ideally with a database unique key,
and commit the record before DeleteMessage. SQS standard queues may duplicate or
reorder messages. A crash after SQS accepts a message but before the outbox file
is deleted can also resend the identical event. Use event timestamps, not arrival
order. Extend visibility for long consumer work and monitor the DLQ and outbox.

This is a file outbox, not a transaction with S3. A host/disk loss or a crash after
file completion but before the event is persisted can still lose a record. Disk
write failures emit an application ERROR and do not change an already completed
file operation. The outbox can grow during prolonged outages; monitor disk space.
SQS retention is finite, so Logging Service should consume within four days.
