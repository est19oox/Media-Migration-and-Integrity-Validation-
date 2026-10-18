# Transfer and Discovery aggregate boundaries

## Transfer

`Transfer` is the aggregate root for one synchronous attempt. It alone decides
the legal sequence `PREPARED → STORED → COMPLETED` or
`PREPARED → STORED → CLEANUP_REQUIRED → CLEANED_UP/CLEANUP_FAILED`.
`TransferService` coordinates storage, Integrity validation, cleanup, and event
publication; the returned receipts are recorded on the aggregate before the
next action. The aggregate is deliberately request-scoped: the current API does
not offer resuming or querying an attempt, so there is no separate Transfer
database or repository. Durable file assignments belong to Metadata, and
durable user-visible outcomes belong to Reporting. Adding a repository only to
save an otherwise unused copy of each attempt would create a second source of
truth without providing recovery.

`TransferAssignment` is Transfer's interpretation of Metadata's response. It
decides whether a source plan and verification run can be transferred; the
remote DTO remains in the integration layer. `FolderUploadManifest` owns the
invariants for one uploaded folder tree: one root, unique relative paths,
valid nesting and names, and the total size. `StoredFile` and `StoredFolder`
own storage-key and deletion-scope rules. Application services still perform
permission queries and I/O through their adapters.

## Discovery / Metadata

`MetadataPlan` is the persisted assignment aggregate. It owns destination
validation, relative paths, duplicate-name suffixing, media classification,
visibility, and verification-run association. `MetadataPlanRepository` owns
the Oracle plan and name-reservation tables; reservations are part of the
plan's atomic storage contract, not a second public domain aggregate.

`VerificationRun` is a separate persisted aggregate that acts as a tombstone
after verification cleanup. Only `VerificationRunRepository` owns its table
and saves its state. `MetadataAssignmentService` coordinates the two roots in
one database transaction: it locks the run, rejects a closed run, then asks
the plan repository to reserve a name and save the plan. Cleanup closes the
run before deleting its plans, so late allocations cannot be accepted.

`MigrationFileInventory` owns matching discovered files to manifest records.
Its inventory is reconstructed from the source directory per request; it has
no repository because the source filesystem is authoritative. Manifest
processing coordinates inventory and the separately persisted metadata and
validation-log roots.

Cross-context authorization, S3/file reads, HTTP calls and event delivery
remain in application or integration layers. They are not aggregate methods.
