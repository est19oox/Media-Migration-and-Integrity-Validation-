package mmiv.reporting.timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import mmiv.reporting.config.ReportingProperties;
import mmiv.reporting.report.MigrationReportDocument;
import mmiv.reporting.report.MigrationReportRepository;
import mmiv.reporting.report.MigrationReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MigrationTimeoutSchedulerTest {

  private static final Instant NOW = Instant.parse("2026-10-11T11:00:00Z");

  private MigrationReportRepository repository;

  private MigrationTimeoutScheduler scheduler;

  @BeforeEach
  void setUp() {
    repository = mock(MigrationReportRepository.class);

    ReportingProperties properties = new ReportingProperties();
    properties.setMigrationTimeout(Duration.ofMinutes(30));

    scheduler =
        new MigrationTimeoutScheduler(repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void migrationAt29MinutesRemainsProcessing() {
    when(repository.findByStatusAndStartedAtBefore(
            eq(MigrationReportStatus.PROCESSING), any(Instant.class)))
        .thenReturn(List.of());

    scheduler.markTimedOutMigrations();

    verify(repository, never()).save(any(MigrationReportDocument.class));
  }

  @Test
  void migrationAt31MinutesBecomesIncomplete() {
    MigrationReportDocument report = reportStartedAt(NOW.minus(Duration.ofMinutes(31)));
    when(repository.findByStatusAndStartedAtBefore(
            MigrationReportStatus.PROCESSING, NOW.minus(Duration.ofMinutes(30))))
        .thenReturn(List.of(report));

    scheduler.markTimedOutMigrations();

    assertEquals(MigrationReportStatus.INCOMPLETE, report.getStatus());
    assertEquals(NOW, report.getCompletedAt());
    verify(repository).save(report);
  }

  @Test
  void terminalReportIsNeverChangedEvenIfReturned() {
    MigrationReportDocument report = reportStartedAt(NOW.minus(Duration.ofMinutes(31)));
    report.recordDiscoveryCompleted(0);
    report.markCompleted(MigrationReportStatus.COMPLETED, NOW.minusSeconds(1));

    when(repository.findByStatusAndStartedAtBefore(
            eq(MigrationReportStatus.PROCESSING), any(Instant.class)))
        .thenReturn(List.of(report));

    scheduler.markTimedOutMigrations();

    assertEquals(MigrationReportStatus.COMPLETED, report.getStatus());
    verify(repository, never()).save(report);
  }

  private MigrationReportDocument reportStartedAt(Instant startedAt) {
    return MigrationReportDocument.processing(UUID.randomUUID(), startedAt);
  }
}
