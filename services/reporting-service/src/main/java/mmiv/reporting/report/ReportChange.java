package mmiv.reporting.report;

import java.time.Instant;
import java.util.UUID;

/** Domain input translated from the published event contract, without JSON dependencies. */
public record ReportChange(
    Kind kind,
    Instant occurredAt,
    UUID fileId,
    String status,
    String initiatedBy,
    MigrationSource source,
    long totalFiles,
    String manifestMediaId,
    String fileName,
    String relativePath,
    FailureView failure) {
  public enum Kind {
    STARTED,
    DISCOVERY_COMPLETED,
    FILE_DISCOVERED,
    FILE_TRANSFERRED,
    FILE_VALIDATED
  }
}
