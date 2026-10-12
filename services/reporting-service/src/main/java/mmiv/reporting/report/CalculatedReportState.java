package mmiv.reporting.report;

import java.util.Objects;

public record CalculatedReportState(
        ReportSummary summary,
        MigrationReportStatus status,
        boolean terminal
) {

    public CalculatedReportState {
        Objects.requireNonNull(
                summary,
                "summary must not be null"
        );

        Objects.requireNonNull(
                status,
                "status must not be null"
        );
    }
}