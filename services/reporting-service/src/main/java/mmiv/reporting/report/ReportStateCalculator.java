package mmiv.reporting.report;

import org.springframework.stereotype.Component;

/** Compatibility adapter; report state is calculated by the aggregate itself. */
@Component
public class ReportStateCalculator {
  public CalculatedReportState calculate(MigrationReportDocument report) {
    return java.util.Objects.requireNonNull(report).calculateState();
  }
}
