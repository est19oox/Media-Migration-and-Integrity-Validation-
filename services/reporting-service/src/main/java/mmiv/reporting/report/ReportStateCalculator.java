package mmiv.reporting.report;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReportStateCalculator {

  public CalculatedReportState calculate(MigrationReportDocument report) {
    Objects.requireNonNull(report, "report must not be null");

    List<FileReportEntry> files = report.getFiles();

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

    long totalFiles = report.isDiscoveryCompleted() ? report.getExpectedTotalFiles() : files.size();

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
        report.isDiscoveryFailed()
            || report.isDiscoveryCompleted() && successfulFiles + failedFiles == totalFiles;

    MigrationReportStatus status =
        report.isDiscoveryFailed()
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
}
