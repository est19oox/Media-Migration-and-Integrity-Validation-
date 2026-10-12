package mmiv.reporting.report;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReportStateCalculatorTest {

    private static final UUID MIGRATION_ID =
            UUID.fromString(
                    "c49f2f8f-ad97-45a8-8867-f3ca8a971d80"
            );

    private static final Instant STARTED_AT =
            Instant.parse("2026-10-11T10:00:00Z");

    private final ReportStateCalculator calculator =
            new ReportStateCalculator();

    @Test
    void completesAnExplicitlyEmptyMigration() {
        MigrationReportDocument report = newReport();

        report.recordDiscoveryCompleted(0);

        CalculatedReportState state =
                calculator.calculate(report);

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.COMPLETED,
                        state.status()
                ),
                () -> assertTrue(state.terminal()),
                () -> assertEquals(
                        ReportSummary.zero(),
                        state.summary()
                )
        );
    }

    @Test
    void calculatesOneSuccessfulFile() {
        MigrationReportDocument report = newReport();

        FileReportEntry file =
                report.getOrCreateFile(fileId(1));

        file.recordDiscoveryAccepted();
        file.recordTransferStored();
        file.recordIntegrityPassed();

        report.recordDiscoveryCompleted(1);

        CalculatedReportState state =
                calculator.calculate(report);

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.COMPLETED,
                        state.status()
                ),
                () -> assertTrue(state.terminal()),
                () -> assertEquals(1, state.summary().totalFiles()),
                () -> assertEquals(
                        1,
                        state.summary().discoveryAccepted()
                ),
                () -> assertEquals(
                        0,
                        state.summary().discoveryFailed()
                ),
                () -> assertEquals(
                        1,
                        state.summary().transferStored()
                ),
                () -> assertEquals(
                        0,
                        state.summary().transferFailed()
                ),
                () -> assertEquals(
                        1,
                        state.summary().integrityPassed()
                ),
                () -> assertEquals(
                        0,
                        state.summary().integrityFailed()
                ),
                () -> assertEquals(
                        1,
                        state.summary().successfulFiles()
                ),
                () -> assertEquals(
                        0,
                        state.summary().failedFiles()
                ),
                () -> assertEquals(
                        100.0,
                        state.summary().successRate()
                )
        );
    }

    @Test
    void keepsStoredFileProcessingUntilIntegrityResultArrives() {
        MigrationReportDocument report = newReport();

        FileReportEntry file =
                report.getOrCreateFile(fileId(1));

        file.recordDiscoveryAccepted();
        file.recordTransferStored();

        report.recordDiscoveryCompleted(1);

        CalculatedReportState state =
                calculator.calculate(report);

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.PROCESSING,
                        state.status()
                ),
                () -> assertFalse(state.terminal()),
                () -> assertEquals(
                        0,
                        state.summary().successfulFiles()
                ),
                () -> assertEquals(
                        0,
                        state.summary().failedFiles()
                )
        );
    }

    @Test
    void waitsForMigrationDiscoveryCompletion() {
        MigrationReportDocument report = newReport();

        FileReportEntry file =
                report.getOrCreateFile(fileId(1));

        file.recordDiscoveryAccepted();
        file.recordTransferStored();
        file.recordIntegrityPassed();

        CalculatedReportState state =
                calculator.calculate(report);

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.PROCESSING,
                        state.status()
                ),
                () -> assertFalse(state.terminal()),
                () -> assertEquals(
                        1,
                        state.summary().successfulFiles()
                )
        );
    }

    @Test
    void calculatesMixedResultsWithoutCountingPendingFile() {
        MigrationReportDocument report = newReport();

        for (int index = 1; index <= 7; index++) {
            FileReportEntry successful =
                    report.getOrCreateFile(fileId(index));

            successful.recordDiscoveryAccepted();
            successful.recordTransferStored();
            successful.recordIntegrityPassed();
        }

        FileReportEntry discoveryFailure =
                report.getOrCreateFile(fileId(8));

        discoveryFailure.recordDiscoveryFailed(
                failure(
                        "DISCOVERY",
                        "FILE_NOT_FOUND"
                )
        );

        FileReportEntry transferFailure =
                report.getOrCreateFile(fileId(9));

        transferFailure.recordDiscoveryAccepted();
        transferFailure.recordTransferFailed(
                failure(
                        "TRANSFER",
                        "S3_UPLOAD_FAILED"
                )
        );

        FileReportEntry pendingIntegrity =
                report.getOrCreateFile(fileId(10));

        pendingIntegrity.recordDiscoveryAccepted();
        pendingIntegrity.recordTransferStored();

        report.recordDiscoveryCompleted(10);

        CalculatedReportState state =
                calculator.calculate(report);

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.PROCESSING,
                        state.status()
                ),
                () -> assertFalse(state.terminal()),
                () -> assertEquals(
                        10,
                        state.summary().totalFiles()
                ),
                () -> assertEquals(
                        9,
                        state.summary().discoveryAccepted()
                ),
                () -> assertEquals(
                        1,
                        state.summary().discoveryFailed()
                ),
                () -> assertEquals(
                        8,
                        state.summary().transferStored()
                ),
                () -> assertEquals(
                        1,
                        state.summary().transferFailed()
                ),
                () -> assertEquals(
                        7,
                        state.summary().integrityPassed()
                ),
                () -> assertEquals(
                        0,
                        state.summary().integrityFailed()
                ),
                () -> assertEquals(
                        7,
                        state.summary().successfulFiles()
                ),
                () -> assertEquals(
                        2,
                        state.summary().failedFiles()
                ),
                () -> assertEquals(
                        70.0,
                        state.summary().successRate()
                )
        );
    }

    @Test
    void marksTerminalMixedResultsPartiallyCompleted() {
        MigrationReportDocument report = newReport();

        FileReportEntry successful =
                report.getOrCreateFile(fileId(1));

        successful.recordDiscoveryAccepted();
        successful.recordTransferStored();
        successful.recordIntegrityPassed();

        FileReportEntry failed =
                report.getOrCreateFile(fileId(2));

        failed.recordDiscoveryFailed(
                failure(
                        "DISCOVERY",
                        "FILE_NOT_FOUND"
                )
        );

        report.recordDiscoveryCompleted(2);

        CalculatedReportState state =
                calculator.calculate(report);

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.PARTIALLY_COMPLETED,
                        state.status()
                ),
                () -> assertTrue(state.terminal()),
                () -> assertEquals(
                        1,
                        state.summary().successfulFiles()
                ),
                () -> assertEquals(
                        1,
                        state.summary().failedFiles()
                ),
                () -> assertEquals(
                        50.0,
                        state.summary().successRate()
                )
        );
    }

    @Test
    void marksMigrationFailedWhenEveryFileFails() {
        MigrationReportDocument report = newReport();

        for (int index = 1; index <= 2; index++) {
            FileReportEntry failed =
                    report.getOrCreateFile(fileId(index));

            failed.recordDiscoveryFailed(
                    failure(
                            "DISCOVERY",
                            "FILE_NOT_FOUND"
                    )
            );
        }

        report.recordDiscoveryCompleted(2);

        CalculatedReportState state =
                calculator.calculate(report);

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.FAILED,
                        state.status()
                ),
                () -> assertTrue(state.terminal()),
                () -> assertEquals(
                        0,
                        state.summary().successfulFiles()
                ),
                () -> assertEquals(
                        2,
                        state.summary().failedFiles()
                ),
                () -> assertEquals(
                        0.0,
                        state.summary().successRate()
                )
        );
    }

    private MigrationReportDocument newReport() {
        return MigrationReportDocument.processing(
                MIGRATION_ID,
                STARTED_AT
        );
    }

    private FailureView failure(
            String stage,
            String code
    ) {
        return new FailureView(
                stage,
                code,
                "Test failure"
        );
    }

    private UUID fileId(int index) {
        return UUID.nameUUIDFromBytes(
                ("file-" + index)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }
}