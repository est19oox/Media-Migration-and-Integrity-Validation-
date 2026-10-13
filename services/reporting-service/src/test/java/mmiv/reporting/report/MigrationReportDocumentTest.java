package mmiv.reporting.report;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MigrationReportDocumentTest {

  private static final UUID MIGRATION_ID = UUID.fromString("c49f2f8f-ad97-45a8-8867-f3ca8a971d80");

  private static final UUID FILE_ID = UUID.fromString("9eceb6d4-8a39-48da-8abe-1238ba8c7a67");

  private static final Instant STARTED_AT = Instant.parse("2026-10-11T10:00:00Z");

  private static final Instant COMPLETED_AT = Instant.parse("2026-10-11T10:35:00Z");

  @Test
  void createsProcessingReportWithZeroSummary() {
    MigrationReportDocument report = MigrationReportDocument.processing(MIGRATION_ID, STARTED_AT);

    assertAll(
        () -> assertEquals(MIGRATION_ID.toString(), report.getId()),
        () -> assertEquals(MigrationReportStatus.PROCESSING, report.getStatus()),
        () -> assertEquals(STARTED_AT, report.getStartedAt()),
        () -> assertNull(report.getCompletedAt()),
        () -> assertEquals(ReportSummary.zero(), report.getSummary()),
        () -> assertEquals(0, report.getFiles().size()));
  }

  @Test
  void createsOneProcessingFileAndReusesIt() {
    MigrationReportDocument report = MigrationReportDocument.processing(MIGRATION_ID, STARTED_AT);

    FileReportEntry first = report.getOrCreateFile(FILE_ID);

    FileReportEntry second = report.getOrCreateFile(FILE_ID);

    assertAll(
        () -> assertSame(first, second),
        () -> assertEquals(FILE_ID.toString(), first.getFileId()),
        () -> assertEquals(FileFinalStatus.PROCESSING, first.getFinalStatus()),
        () -> assertEquals(1, report.getFiles().size()),
        () -> assertEquals(first, report.findFile(FILE_ID).orElseThrow()));
  }

  @Test
  void returnsEmptyWhenFileDoesNotExist() {
    MigrationReportDocument report = MigrationReportDocument.processing(MIGRATION_ID, STARTED_AT);

    assertFalse(report.findFile(FILE_ID).isPresent());
  }

  @Test
  void doesNotExposeMutableFileList() {
    MigrationReportDocument report = MigrationReportDocument.processing(MIGRATION_ID, STARTED_AT);

    FileReportEntry file = report.getOrCreateFile(FILE_ID);

    assertThrows(UnsupportedOperationException.class, () -> report.getFiles().add(file));
  }

  @Test
  void replacesSummaryAndMarksReportCompleted() {
    MigrationReportDocument report = MigrationReportDocument.processing(MIGRATION_ID, STARTED_AT);

    ReportSummary summary = new ReportSummary(10, 9, 1, 8, 1, 7, 1, 7, 3, 70.0);

    report.replaceSummary(summary);

    report.markCompleted(MigrationReportStatus.PARTIALLY_COMPLETED, COMPLETED_AT);

    assertAll(
        () -> assertEquals(summary, report.getSummary()),
        () -> assertEquals(MigrationReportStatus.PARTIALLY_COMPLETED, report.getStatus()),
        () -> assertEquals(COMPLETED_AT, report.getCompletedAt()));
  }

  @Test
  void marksProcessingReportIncomplete() {
    MigrationReportDocument report = MigrationReportDocument.processing(MIGRATION_ID, STARTED_AT);

    report.markIncomplete(COMPLETED_AT);

    assertAll(
        () -> assertEquals(MigrationReportStatus.INCOMPLETE, report.getStatus()),
        () -> assertEquals(COMPLETED_AT, report.getCompletedAt()));
  }
}
