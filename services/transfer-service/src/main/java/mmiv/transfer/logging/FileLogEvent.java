package mmiv.transfer.logging;

/** Versioned SQS message. All timestamps are ISO-8601 UTC strings. */
public record FileLogEvent(int schemaVersion, String eventId, String operation,
        String transferId, String fileId, String fileName, String bucket, String key,
        Long sizeBytes, Long bytesServed, String sha256, String validationResult,
        String cleanupStatus, boolean success, String errorType,
        String startedAt, String completedAt) {}
