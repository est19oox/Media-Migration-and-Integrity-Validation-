package mmiv.reporting.api;

import java.util.UUID;

public class ReportNotFoundException extends RuntimeException {

  public ReportNotFoundException(UUID migrationId) {
    super("Migration report not found: " + migrationId);
  }
}
