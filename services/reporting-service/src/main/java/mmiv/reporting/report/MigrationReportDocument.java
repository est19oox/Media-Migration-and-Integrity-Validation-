package mmiv.reporting.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@org.springframework.data.mongodb.core.mapping.Document(collection = "migration_reports")
@CompoundIndex(name = "status_started_idx", def = "{'status': 1, 'startedAt': -1}")
public class MigrationReportDocument {

  @Id private final String id;

  private MigrationReportStatus status;

  private Instant startedAt;

  private Instant latestEventAt;

  private String initiatedBy;

  private MigrationSource source;

  private FailureView failure;

  private boolean discoveryFailed;

  private Instant completedAt;

  private ReportSummary summary;

  private Long expectedTotalFiles;

  private final List<FileReportEntry> files;

  private MigrationReportDocument(
      String id,
      MigrationReportStatus status,
      Instant startedAt,
      Instant latestEventAt,
      String initiatedBy,
      MigrationSource source,
      FailureView failure,
      boolean discoveryFailed,
      Instant completedAt,
      ReportSummary summary,
      Long expectedTotalFiles,
      List<FileReportEntry> files) {
    this.id = Objects.requireNonNull(id, "id must not be null");

    this.status = Objects.requireNonNull(status, "status must not be null");

    this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");

    this.latestEventAt = Objects.requireNonNull(latestEventAt, "latestEventAt must not be null");

    this.initiatedBy = initiatedBy;
    this.source = source;
    this.failure = failure;
    this.discoveryFailed = discoveryFailed;

    this.completedAt = completedAt;

    this.summary = Objects.requireNonNull(summary, "summary must not be null");

    this.expectedTotalFiles = expectedTotalFiles;

    this.files = new ArrayList<>(Objects.requireNonNull(files, "files must not be null"));
  }

  public static MigrationReportDocument processing(UUID migrationId, Instant startedAt) {
    Objects.requireNonNull(migrationId, "migrationId must not be null");

    return new MigrationReportDocument(
        migrationId.toString(),
        MigrationReportStatus.PROCESSING,
        startedAt,
        startedAt,
        null,
        null,
        null,
        false,
        null,
        ReportSummary.zero(),
        null,
        List.of());
  }

  public Optional<FileReportEntry> findFile(UUID fileId) {
    Objects.requireNonNull(fileId, "fileId must not be null");

    String requestedId = fileId.toString();

    return files.stream().filter(file -> file.getFileId().equals(requestedId)).findFirst();
  }

  FileReportEntry getOrCreateFile(UUID fileId) {
    Objects.requireNonNull(fileId, "fileId must not be null");

    return findFile(fileId)
        .orElseGet(
            () -> {
              FileReportEntry created = FileReportEntry.processing(fileId);

              files.add(created);
              return created;
            });
  }

  void recordDiscoveryCompleted(long totalFiles) {
    if (totalFiles < 0) {
      throw new IllegalArgumentException("totalFiles must not be negative");
    }

    this.expectedTotalFiles = totalFiles;
    this.discoveryFailed = false;
    this.failure = null;
  }

  void recordDiscoveryFailed(FailureView failure) {
    this.discoveryFailed = true;
    this.expectedTotalFiles = null;
    this.failure = Objects.requireNonNull(failure, "failure must not be null");
  }

  void recordStarted(Instant startedAt, String initiatedBy, MigrationSource source) {
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
    this.initiatedBy = Objects.requireNonNull(initiatedBy, "initiatedBy must not be null");
    this.source = Objects.requireNonNull(source, "source must not be null");
  }

  void recordEventOccurredAt(Instant occurredAt) {
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    if (occurredAt.isAfter(latestEventAt)) {
      latestEventAt = occurredAt;
    }
  }

  public boolean isDiscoveryCompleted() {
    return expectedTotalFiles != null;
  }

  public boolean isDiscoveryFailed() {
    return discoveryFailed;
  }

  public Long getExpectedTotalFiles() {
    return expectedTotalFiles;
  }

  void replaceSummary(ReportSummary summary) {
    this.summary = Objects.requireNonNull(summary, "summary must not be null");
  }

  void markCompleted(MigrationReportStatus status, Instant completedAt) {
    Objects.requireNonNull(status, "status must not be null");

    if (status != MigrationReportStatus.COMPLETED
        && status != MigrationReportStatus.PARTIALLY_COMPLETED
        && status != MigrationReportStatus.FAILED) {
      throw new IllegalArgumentException("Completed report requires a terminal status");
    }

    this.status = status;

    this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
  }

  void markIncomplete(Instant completedAt) {
    this.status = MigrationReportStatus.INCOMPLETE;

    this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
  }

  public String getId() {
    return id;
  }

  public MigrationReportStatus getStatus() {
    return status;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getLatestEventAt() {
    return latestEventAt;
  }

  public boolean belongsTo(String subject) {
    return subject != null && subject.equals(initiatedBy);
  }

  public String getInitiatedBy() {
    return initiatedBy;
  }

  public MigrationSource getSource() {
    return source;
  }

  public FailureView getFailure() {
    return failure;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public ReportSummary getSummary() {
    return summary;
  }

  public List<FileReportEntry> getFiles() {
    return List.copyOf(files);
  }

  public CalculatedReportState calculateState() {
    List<FileReportEntry> files = getFiles();

    long discoveryAccepted = countStatus(files, "ACCEPTED", Stage.DISCOVERY);

    long discoveryFailed =
        files.stream()
            .filter(
                file ->
                    "REJECTED".equals(file.getDiscoveryStatus())
                        || "ERROR".equals(file.getDiscoveryStatus()))
            .count();

    long transferStored = countStatus(files, "STORED", Stage.TRANSFER);

    long transferFailed = countStatus(files, "FAILED", Stage.TRANSFER);

    long integrityPassed = countStatus(files, "PASSED", Stage.INTEGRITY);

    long integrityFailed =
        files.stream()
            .filter(
                file ->
                    "FAILED".equals(file.getIntegrityStatus())
                        || "ERROR".equals(file.getIntegrityStatus()))
            .count();

    long successfulFiles =
        files.stream().filter(file -> file.getFinalStatus() == FileFinalStatus.SUCCEEDED).count();

    long failedFiles =
        files.stream().filter(file -> file.getFinalStatus() == FileFinalStatus.FAILED).count();

    long totalFiles = isDiscoveryCompleted() ? getExpectedTotalFiles() : files.size();

    double successRate = totalFiles == 0 ? 0.0 : successfulFiles * 100.0 / totalFiles;

    ReportSummary summary =
        new ReportSummary(
            totalFiles,
            discoveryAccepted,
            discoveryFailed,
            transferStored,
            transferFailed,
            integrityPassed,
            integrityFailed,
            successfulFiles,
            failedFiles,
            successRate);

    boolean terminal =
        isDiscoveryFailed()
            || isDiscoveryCompleted() && successfulFiles + failedFiles == totalFiles;

    MigrationReportStatus status =
        isDiscoveryFailed()
            ? MigrationReportStatus.FAILED
            : determineStatus(terminal, totalFiles, successfulFiles, failedFiles);

    return new CalculatedReportState(summary, status, terminal);
  }

  private MigrationReportStatus determineStatus(
      boolean terminal, long totalFiles, long successfulFiles, long failedFiles) {
    if (!terminal) {
      return MigrationReportStatus.PROCESSING;
    }

    if (totalFiles == 0 || successfulFiles == totalFiles) {
      return MigrationReportStatus.COMPLETED;
    }

    if (failedFiles == totalFiles) {
      return MigrationReportStatus.FAILED;
    }

    return MigrationReportStatus.PARTIALLY_COMPLETED;
  }

  private long countStatus(List<FileReportEntry> files, String expectedStatus, Stage stage) {
    return files.stream()
        .map(file -> statusFor(file, stage))
        .filter(expectedStatus::equals)
        .count();
  }

  private String statusFor(FileReportEntry file, Stage stage) {
    return switch (stage) {
      case DISCOVERY -> file.getDiscoveryStatus();

      case TRANSFER -> file.getTransferStatus();

      case INTEGRITY -> file.getIntegrityStatus();
    };
  }

  private enum Stage {
    DISCOVERY,
    TRANSFER,
    INTEGRITY
  }

  /** Apply a complete event atomically through the aggregate root. */
  public void apply(ReportChange change) {
    recordEventOccurredAt(change.occurredAt());
    switch (change.kind()) {
      case STARTED -> recordStarted(change.occurredAt(), change.initiatedBy(), change.source());
      case DISCOVERY_COMPLETED -> {
        if ("FAILED".equals(change.status())) recordDiscoveryFailed(change.failure());
        else recordDiscoveryCompleted(change.totalFiles());
      }
      case FILE_DISCOVERED -> {
        var file = getOrCreateFile(change.fileId());
        file.recordDiscoveryMetadata(
            change.manifestMediaId(), change.fileName(), change.relativePath());
        switch (change.status()) {
          case "ACCEPTED" -> file.recordDiscoveryAccepted();
          case "REJECTED", "ERROR" -> file.recordDiscoveryFailed(change.status(), change.failure());
          default ->
              throw new IllegalArgumentException(
                  "Unsupported discovery status: " + change.status());
        }
      }
      case FILE_TRANSFERRED -> {
        var file = getOrCreateFile(change.fileId());
        switch (change.status()) {
          case "STORED" -> file.recordTransferStored();
          case "FAILED" -> file.recordTransferFailed(change.failure());
          default ->
              throw new IllegalArgumentException("Unsupported transfer status: " + change.status());
        }
      }
      case FILE_VALIDATED -> {
        var file = getOrCreateFile(change.fileId());
        switch (change.status()) {
          case "PASSED" -> file.recordIntegrityPassed();
          case "FAILED", "ERROR" -> file.recordIntegrityFailed(change.status(), change.failure());
          default ->
              throw new IllegalArgumentException(
                  "Unsupported integrity status: " + change.status());
        }
      }
    }
    var calculated = calculateState();
    replaceSummary(calculated.summary());
    if (calculated.terminal()) markCompleted(calculated.status(), latestEventAt);
  }

  public boolean timeOut(Instant now) {
    if (status != MigrationReportStatus.PROCESSING) return false;
    markIncomplete(now);
    return true;
  }
}
