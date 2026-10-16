# MMIV Event Contract v2

[English](README.md) | [简体中文](README.zh-CN.md)

This directory contains the shared, frozen message contract used by the Reporting Service. Discovery, Transfer, and Integrity Service publish independent outcome events; Reporting joins them using `migrationId` and `fileId`.

RabbitMQ transports events but does not orchestrate the migration workflow. Each business service must still perform its work through the existing APIs or business flow.

### Files

- `mmiv-event.schema.json`: the shared JSON Schema used by CI and Reporting Service to validate messages automatically.
- `examples/`: valid message examples that each publisher can follow directly.
- Developers should copy the closest example first and replace its sample values with real runtime data.
- Do not create and modify separate copies of the Schema in individual microservices, because those copies could drift apart.

### Publisher ownership

| Service   | Event                            | Routing key                      |
| --------- | -------------------------------- | -------------------------------- |
| Discovery | `MIGRATION_STARTED`              | `migration.started`              |
| Discovery | `FILE_DISCOVERY_COMPLETED`       | `discovery.file.completed`       |
| Discovery | `MIGRATION_DISCOVERY_COMPLETED`  | `discovery.migration.completed`  |
| Transfer  | `FILE_TRANSFER_COMPLETED`        | `transfer.file.completed`        |
| Integrity | `INTEGRITY_VALIDATION_COMPLETED` | `integrity.validation.completed` |

Publish every event as a persistent JSON message to the durable topic exchange `mmiv.events`, with:

```text
content_type = application/json
message_id = the eventId from the JSON body
```

### Examples for each service

Discovery Service:

- `examples/01-migration-started.json`
- `examples/02-file-discovery-accepted.json`
- `examples/03-file-discovery-rejected.json`
- `examples/04-migration-discovery-completed.json`

Transfer Service:

- `examples/05-file-transfer-completed.json`

Integrity Service:

- `examples/06-integrity-validation-completed.json`

### Identifier ownership

- Discovery creates one `migrationId` for a migration and one stable `fileId` for each file.
- Transfer and Integrity must reuse the `migrationId` and `fileId` supplied by Discovery; they must not generate replacements.
- Transfer creates the `transferId` for the transfer result, and Integrity must reuse that `transferId`.
- Each publisher creates a unique `eventId`. Retrying or redelivering the same logical event must reuse the original `eventId`.
- `manifestMediaId` is an optional external business reference and is not the same as `fileId`.

### Publishing rules

- Use `schemaVersion: 2` and the exact event/source pairing defined by the Schema.
- Use UTC ISO-8601 timestamps such as `2026-10-11T10:30:00Z`.
- Omit optional fields when unavailable; do not send meaningless `null` values.
- A successful status must omit `failure`.
- Failed, rejected, or error statuses must include sanitized `failure.code`, `failure.message`, and `failure.retryable` values.
- Publish only a normalized `/`-separated `relativePath` beneath the configured logical `rootId`.
- Do not add fields locally. An incompatible change requires a new Schema version.

### Security restrictions

Messages must not contain:

- absolute Windows, macOS, or Linux host paths;
- access keys, passwords, tokens, or other credentials;
- stack traces;
- actual source or destination checksum values.

`checksumAlgorithm` and the integrity result, such as `PASSED` or `FAILED`, are allowed.

### Relationship between the Schema and examples

`mmiv-event.schema.json` uses `oneOf` to define the five valid event types. Based on `eventType`, the Schema verifies:

- that `sourceService` is correct;
- that the required identifiers are present;
- that the fields inside `data` match the event;
- that status values are allowed;
- that successful and unsuccessful messages omit or include `failure` correctly;
- that the message contains no unknown fields, unsafe paths, or checksum values.

Publisher developers can work mainly from the examples. CI and Reporting Service use the Schema for strict automatic validation.
