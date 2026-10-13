package mmiv.reporting.timeout;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import mmiv.reporting.config.ReportingProperties;
import mmiv.reporting.report.MigrationReportRepository;
import mmiv.reporting.report.MigrationReportStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MigrationTimeoutScheduler {

  private final MigrationReportRepository repository;

  private final ReportingProperties properties;

  private final Clock clock;

  public MigrationTimeoutScheduler(
      MigrationReportRepository repository, ReportingProperties properties, Clock clock) {
    this.repository = Objects.requireNonNull(repository);
    this.properties = Objects.requireNonNull(properties);
    this.clock = Objects.requireNonNull(clock);
  }

  @Scheduled(fixedDelayString = "${mmiv.reporting.timeout-check-interval:1m}")
  public void markTimedOutMigrations() {
    Instant now = clock.instant();
    Instant cutoff = now.minus(properties.getMigrationTimeout());

    repository.findByStatusAndStartedAtBefore(MigrationReportStatus.PROCESSING, cutoff).stream()
        .filter(report -> report.getStatus() == MigrationReportStatus.PROCESSING)
        .forEach(
            report -> {
              report.markIncomplete(now);
              repository.save(report);
            });
  }
}
