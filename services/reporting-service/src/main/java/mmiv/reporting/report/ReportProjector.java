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

    report.recordEventOccurredAt(event.occurredAt());

    applyEvent(report, event);

    CalculatedReportState state = stateCalculator.calculate(report);

    report.replaceSummary(state.summary());

    if (state.terminal()) {
      report.markCompleted(state.status(), report.getLatestEventAt());
    }

    repository.save(report);
    if (cleanup.isDeleted(event.migrationId()))
      repository.deleteById(event.migrationId().toString());
  }

  private void applyEvent(MigrationReportDocument report, EventEnvelope event) {
    switch (event.eventType()) {
      case MIGRATION_STARTED -> applyMigrationStarted(report, event);

      case FILE_DISCOVERY_COMPLETED -> applyDiscoveryEvent(report, event);

      case MIGRATION_DISCOVERY_COMPLETED -> applyDiscoveryCompleted(report, event);

      case FILE_TRANSFER_COMPLETED -> applyTransferEvent(report, event);

      case INTEGRITY_VALIDATION_COMPLETED -> applyIntegrityEvent(report, event);
    }
  }

  private void applyMigrationStarted(MigrationReportDocument report, EventEnvelope event) {
    JsonNode source = event.data().path("source");
    report.recordStarted(
        event.occurredAt(),
        event.data().path("initiatedBy").stringValue(),
        new MigrationSource(
            source.path("type").stringValue(),
            source.path("rootId").stringValue(),
            optionalText(source, "manifestName")));
  }

  private void applyDiscoveryCompleted(MigrationReportDocument report, EventEnvelope event) {
    if ("FAILED".equals(statusOf(event.data()))) {
      report.recordDiscoveryFailed(failureOf("DISCOVERY", event.data()));
      return;
    }

    report.recordDiscoveryCompleted(event.data().path("totalFiles").longValue());
  }

  private void applyDiscoveryEvent(MigrationReportDocument report, EventEnvelope event) {
    FileReportEntry file = report.getOrCreateFile(event.fileId());

    file.recordDiscoveryMetadata(
        optionalText(event.data(), "manifestMediaId"),
        event.data().path("fileName").stringValue(),
        optionalText(event.data(), "relativePath"));

    String status = statusOf(event.data());

    if ("ACCEPTED".equals(status)) {
      file.recordDiscoveryAccepted();
      return;
    }

    if ("REJECTED".equals(status) || "ERROR".equals(status)) {
      file.recordDiscoveryFailed(status, failureOf("DISCOVERY", event.data()));
      return;
    }

    throw new IllegalArgumentException("Unsupported discovery status: " + status);
  }

  private void applyTransferEvent(MigrationReportDocument report, EventEnvelope event) {
    FileReportEntry file = report.getOrCreateFile(event.fileId());

    String status = statusOf(event.data());

    if ("STORED".equals(status)) {
      file.recordTransferStored();
      return;
    }

    if ("FAILED".equals(status)) {
      file.recordTransferFailed(failureOf("TRANSFER", event.data()));
      return;
    }

    throw new IllegalArgumentException("Unsupported transfer status: " + status);
  }

  private void applyIntegrityEvent(MigrationReportDocument report, EventEnvelope event) {
    FileReportEntry file = report.getOrCreateFile(event.fileId());

    String status = statusOf(event.data());

    if ("PASSED".equals(status)) {
      file.recordIntegrityPassed();
      return;
    }

    if ("FAILED".equals(status) || "ERROR".equals(status)) {
      file.recordIntegrityFailed(status, failureOf("INTEGRITY", event.data()));
      return;
    }

    throw new IllegalArgumentException("Unsupported integrity status: " + status);
  }

  private FailureView failureOf(String stage, JsonNode data) {
    JsonNode failure = data.path("failure");

    return new FailureView(
        stage, failure.path("code").stringValue(), failure.path("message").stringValue());
  }

  private String statusOf(JsonNode data) {
    return data.path("status").stringValue();
  }

  private String optionalText(JsonNode data, String fieldName) {
    JsonNode value = data.get(fieldName);
    return value == null ? null : value.stringValue();
  }
}
