package mmiv.reporting.report;

import java.util.Objects;
import mmiv.reporting.contract.EventEnvelope;
import mmiv.reporting.contract.ValidatedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ReportProjector {

  private final MigrationReportRepository repository;

  private final ReportStateCalculator stateCalculator;
  private final VerificationCleanupGuard cleanup;

  public ReportProjector(
      MigrationReportRepository repository,
      ReportStateCalculator stateCalculator,
      VerificationCleanupGuard cleanup) {
    this.cleanup = Objects.requireNonNull(cleanup);
    this.repository = Objects.requireNonNull(repository, "repository must not be null");

    this.stateCalculator =
        Objects.requireNonNull(stateCalculator, "stateCalculator must not be null");
  }

  public void apply(ValidatedEvent validatedEvent) {
    Objects.requireNonNull(validatedEvent, "validatedEvent must not be null");

    EventEnvelope event = validatedEvent.envelope();
    if (cleanup.isDeleted(event.migrationId())) return;

    MigrationReportDocument report =
        repository
            .findById(event.migrationId().toString())
            .orElseGet(
                () -> MigrationReportDocument.processing(event.migrationId(), event.occurredAt()));

    report.apply(translate(event));

    repository.save(report);
    if (cleanup.isDeleted(event.migrationId()))
      repository.deleteById(event.migrationId().toString());
  }

  /** Translation only: business transitions belong to MigrationReportDocument. */
  private ReportChange translate(EventEnvelope event) {
    var data = event.data();
    var kind =
        switch (event.eventType()) {
          case MIGRATION_STARTED -> ReportChange.Kind.STARTED;
          case MIGRATION_DISCOVERY_COMPLETED -> ReportChange.Kind.DISCOVERY_COMPLETED;
          case FILE_DISCOVERY_COMPLETED -> ReportChange.Kind.FILE_DISCOVERED;
          case FILE_TRANSFER_COMPLETED -> ReportChange.Kind.FILE_TRANSFERRED;
          case INTEGRITY_VALIDATION_COMPLETED -> ReportChange.Kind.FILE_VALIDATED;
        };
    var source = data.path("source");
    MigrationSource migrationSource =
        kind == ReportChange.Kind.STARTED
            ? new MigrationSource(
                source.path("type").stringValue(),
                source.path("rootId").stringValue(),
                optionalText(source, "manifestName"))
            : null;
    String stage =
        switch (kind) {
          case FILE_TRANSFERRED -> "TRANSFER";
          case FILE_VALIDATED -> "INTEGRITY";
          default -> "DISCOVERY";
        };
    var failure = data.path("failure");
    FailureView view =
        data.has("failure")
            ? new FailureView(
                stage, failure.path("code").stringValue(), failure.path("message").stringValue())
            : null;
    return new ReportChange(
        kind,
        event.occurredAt(),
        event.fileId(),
        optionalText(data, "status"),
        optionalText(data, "initiatedBy"),
        migrationSource,
        data.has("totalFiles") ? data.path("totalFiles").longValue() : 0,
        optionalText(data, "manifestMediaId"),
        optionalText(data, "fileName"),
        optionalText(data, "relativePath"),
        view);
  }

  private String optionalText(JsonNode data, String field) {
    var value = data.get(field);
    return value == null ? null : value.stringValue();
  }
}
