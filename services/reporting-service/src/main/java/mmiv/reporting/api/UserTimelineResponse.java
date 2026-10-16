package mmiv.reporting.api;

import java.time.Instant;
import java.util.Set;
import mmiv.reporting.audit.AuditEventDocument;
import org.bson.Document;

public record UserTimelineResponse(
    String stage, String status, String fileId, Instant occurredAt, String message) {
  private static final Set<String> SAFE_STATUSES =
      Set.of("STARTED", "ACCEPTED", "REJECTED", "ERROR", "COMPLETED", "FAILED", "STORED", "PASSED");

  public static UserTimelineResponse from(AuditEventDocument event) {
    Presentation presentation = presentation(event);
    return new UserTimelineResponse(
        presentation.stage(),
        presentation.status(),
        event.getFileId(),
        event.getOccurredAt(),
        presentation.message());
  }

  private static Presentation presentation(AuditEventDocument event) {
    return switch (event.getEventType()) {
      case MIGRATION_STARTED -> new Presentation("MIGRATION", "STARTED", "Migration started");
      case FILE_DISCOVERY_COMPLETED ->
          new Presentation("DISCOVERY", safeStatus(event.getData()), "File discovery completed");
      case MIGRATION_DISCOVERY_COMPLETED ->
          new Presentation("DISCOVERY", safeStatus(event.getData()), "Discovery stage completed");
      case FILE_TRANSFER_COMPLETED ->
          new Presentation("TRANSFER", safeStatus(event.getData()), "File transfer completed");
      case INTEGRITY_VALIDATION_COMPLETED ->
          new Presentation(
              "INTEGRITY", safeStatus(event.getData()), "File integrity validation completed");
      default -> new Presentation("MIGRATION", "UNKNOWN", "Migration status updated");
    };
  }

  private static String safeStatus(Document data) {
    Object value = data.get("status");
    if (!(value instanceof String status) || !SAFE_STATUSES.contains(status)) {
      return "UNKNOWN";
    }
    return status;
  }

  private record Presentation(String stage, String status, String message) {}
}
