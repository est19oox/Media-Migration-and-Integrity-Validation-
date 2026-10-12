package mmiv.reporting.report;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import mmiv.reporting.contract.EventContractValidator;
import mmiv.reporting.contract.ValidatedEvent;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import mmiv.reporting.contract.EventEnvelope;

class ReportProjectorTest {

    private final ObjectMapper objectMapper =
            JsonMapper.builder().build();

    private final EventContractValidator validator =
            new EventContractValidator(objectMapper);

    private final AtomicReference<MigrationReportDocument>
            storedReport = new AtomicReference<>();

    private MigrationReportRepository repository;

    private ReportProjector projector;
    private VerificationCleanupGuard cleanup;

    @BeforeEach
    void setUp() {
        storedReport.set(null);

        repository = mock(
                MigrationReportRepository.class
        );

        when(repository.findById(any(String.class)))
                .thenAnswer(invocation -> {
                    String requestedId =
                            invocation.getArgument(0);

                    MigrationReportDocument current =
                            storedReport.get();

                    if (current == null
                            || !current.getId()
                            .equals(requestedId)) {
                        return Optional.empty();
                    }

                    return Optional.of(current);
                });

        when(repository.save(
                any(MigrationReportDocument.class)
        )).thenAnswer(invocation -> {
            MigrationReportDocument report =
                    invocation.getArgument(0);

            storedReport.set(report);
            return report;
        });

        cleanup = mock(VerificationCleanupGuard.class);
        projector = new ReportProjector(
                repository,
                new ReportStateCalculator(), cleanup
        );
    }

    @Test
    void ignoresDelayedEventsForDeletedTestReports() throws Exception {
        var event=event("01-migration-started.json");
        when(cleanup.isDeleted(event.envelope().migrationId())).thenReturn(true);
        projector.apply(event);
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void removesReportIfCleanupRacesWithProjection() throws Exception {
        var event=event("01-migration-started.json");
        when(cleanup.isDeleted(event.envelope().migrationId())).thenReturn(false,true);
        projector.apply(event);
        org.mockito.Mockito.verify(repository).deleteById(event.envelope().migrationId().toString());
    }

    @Test
    void projectsHappyPathInNormalOrder() throws Exception {
        for (ValidatedEvent event : happyPathEvents()) {
            projector.apply(event);
        }

        assertCompletedHappyPath(storedReport.get());
    }

    @Test
    void projectsHappyPathInExactReverseOrder()
            throws Exception {
        List<ValidatedEvent> reversed =
                new ArrayList<>(happyPathEvents());

        Collections.reverse(reversed);

        for (ValidatedEvent event : reversed) {
            projector.apply(event);
        }

        assertCompletedHappyPath(storedReport.get());
        assertEquals(
                Instant.parse("2026-10-11T10:30:00Z"),
                storedReport.get().getCompletedAt()
        );
    }

    @Test
    void applyingEveryEventTwiceDoesNotDoubleCount()
            throws Exception {
        for (ValidatedEvent event : happyPathEvents()) {
            projector.apply(event);
            projector.apply(event);
        }

        MigrationReportDocument report =
                storedReport.get();

        assertCompletedHappyPath(report);

        assertAll(
                () -> assertEquals(
                        1,
                        report.getFiles().size()
                ),
                () -> assertEquals(
                        1,
                        report.getSummary().totalFiles()
                ),
                () -> assertEquals(
                        1,
                        report.getSummary().successfulFiles()
                )
        );
    }

    private void assertCompletedHappyPath(
            MigrationReportDocument report
    ) {
        FileReportEntry file =
                report.getFiles().getFirst();

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.COMPLETED,
                        report.getStatus()
                ),
                () -> assertEquals(
                        1,
                        report.getSummary().totalFiles()
                ),
                () -> assertEquals(
                        1,
                        report.getSummary().successfulFiles()
                ),
                () -> assertEquals(
                        0,
                        report.getSummary().failedFiles()
                ),
                () -> assertEquals(
                        100.0,
                        report.getSummary().successRate()
                ),
                () -> assertEquals(
                        1,
                        report.getFiles().size()
                ),
                () -> assertEquals(
                        "ACCEPTED",
                        file.getDiscoveryStatus()
                ),
                () -> assertEquals(
                        "STORED",
                        file.getTransferStatus()
                ),
                () -> assertEquals(
                        "PASSED",
                        file.getIntegrityStatus()
                ),
                () -> assertEquals(
                        FileFinalStatus.SUCCEEDED,
                        file.getFinalStatus()
                )
        );
    }

    private List<ValidatedEvent> happyPathEvents()
            throws Exception {
        return List.of(
                event("01-migration-started.json"),
                event("02-file-discovery-accepted.json"),
                event(
                        "04-migration-discovery-completed.json"
                ),
                event("05-file-transfer-completed.json"),
                event(
                        "06-integrity-validation-completed.json"
                )
        );
    }

    private ValidatedEvent transferFailureEvent()
            throws Exception {
        ValidatedEvent original =
                event("05-file-transfer-completed.json");

        EventEnvelope envelope = original.envelope();

        return new ValidatedEvent(
                new EventEnvelope(
                        envelope.eventId(),
                        envelope.eventType(),
                        envelope.schemaVersion(),
                        envelope.sourceService(),
                        envelope.migrationId(),
                        envelope.fileId(),
                        envelope.occurredAt(),
                        objectMapper.readTree(
                                """
                                {
                                  "transferId":
                                    "344cd216-dac8-4bd2-a350-1dc891c9f4cb",
                                  "status": "FAILED",
                                  "durationMs": 2300,
                                  "failure": {
                                    "code": "S3_UPLOAD_FAILED",
                                    "message":
                                      "Could not upload the file to S3",
                                    "retryable": true
                                  }
                                }
                                """
                        )
                ),
                original.originalBody(),
                original.payloadSha256()
        );
    }

    private ValidatedEvent integrityFailureEvent()
            throws Exception {
        ValidatedEvent original =
                event(
                        "06-integrity-validation-completed.json"
                );

        EventEnvelope envelope = original.envelope();

        return new ValidatedEvent(
                new EventEnvelope(
                        envelope.eventId(),
                        envelope.eventType(),
                        envelope.schemaVersion(),
                        envelope.sourceService(),
                        envelope.migrationId(),
                        envelope.fileId(),
                        envelope.occurredAt(),
                        objectMapper.readTree(
                                """
                                {
                                  "transferId":
                                    "344cd216-dac8-4bd2-a350-1dc891c9f4cb",
                                  "status": "FAILED",
                                  "checksumAlgorithm": "SHA-256",
                                  "durationMs": 3200,
                                  "failure": {
                                    "code": "CHECKSUM_MISMATCH",
                                    "message":
                                      "Source and destination checksums do not match",
                                    "retryable": false
                                  }
                                }
                                """
                        )
                ),
                original.originalBody(),
                original.payloadSha256()
        );
    }

    private ValidatedEvent event(String fileName)
            throws Exception {
        Path path = repositoryRoot()
                .resolve("contracts/events/v2/examples")
                .resolve(fileName);

        return validator.validate(
                Files.readAllBytes(path)
        );
    }

    private static Path repositoryRoot() {
        Path current = Path.of("")
                .toAbsolutePath()
                .normalize();

        while (current != null) {
            if (Files.isDirectory(
                    current.resolve("contracts")
            )
                    && Files.isDirectory(
                    current.resolve("services")
            )) {
                return current;
            }

            current = current.getParent();
        }

        throw new IllegalStateException(
                "Could not locate repository root"
        );
    }

    @Test
    void projectsDiscoveryRejectionAsTerminalFailure()
            throws Exception {
        projector.apply(
                event("01-migration-started.json")
        );

        projector.apply(
                event("03-file-discovery-rejected.json")
        );

        projector.apply(
                event(
                        "04-migration-discovery-completed.json"
                )
        );

        MigrationReportDocument report =
                storedReport.get();

        FileReportEntry file =
                report.getFiles().getFirst();

        FailureView failure = file.getFailure();

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.FAILED,
                        report.getStatus()
                ),
                () -> assertEquals(
                        1,
                        report.getSummary().totalFiles()
                ),
                () -> assertEquals(
                        1,
                        report.getSummary().discoveryFailed()
                ),
                () -> assertEquals(
                        1,
                        report.getSummary().failedFiles()
                ),
                () -> assertEquals(
                        0.0,
                        report.getSummary().successRate()
                ),
                () -> assertEquals(
                        "REJECTED",
                        file.getDiscoveryStatus()
                ),
                () -> assertEquals(
                        FileFinalStatus.FAILED,
                        file.getFinalStatus()
                ),
                () -> assertEquals(
                        "DISCOVERY",
                        failure.stage()
                ),
                () -> assertEquals(
                        "AMBIGUOUS_FILE_NAME",
                        failure.code()
                ),
                () -> assertEquals(
                        "Multiple source files have the same file name",
                        failure.message()
                )
        );
    }

    @Test
    void projectsTransferFailureAsTerminalFailure()
            throws Exception {
        projector.apply(
                event("01-migration-started.json")
        );

        projector.apply(
                event("02-file-discovery-accepted.json")
        );

        projector.apply(
                event(
                        "04-migration-discovery-completed.json"
                )
        );

        projector.apply(transferFailureEvent());

        MigrationReportDocument report =
                storedReport.get();

        FileReportEntry file =
                report.getFiles().getFirst();

        FailureView failure = file.getFailure();

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.FAILED,
                        report.getStatus()
                ),
                () -> assertEquals(
                        1,
                        report.getSummary().transferFailed()
                ),
                () -> assertEquals(
                        1,
                        report.getSummary().failedFiles()
                ),
                () -> assertEquals(
                        "ACCEPTED",
                        file.getDiscoveryStatus()
                ),
                () -> assertEquals(
                        "FAILED",
                        file.getTransferStatus()
                ),
                () -> assertEquals(
                        FileFinalStatus.FAILED,
                        file.getFinalStatus()
                ),
                () -> assertEquals(
                        "TRANSFER",
                        failure.stage()
                ),
                () -> assertEquals(
                        "S3_UPLOAD_FAILED",
                        failure.code()
                ),
                () -> assertEquals(
                        "Could not upload the file to S3",
                        failure.message()
                )
        );
    }

    @Test
    void projectsIntegrityFailureAsTerminalFailure()
            throws Exception {
        projector.apply(
                event("01-migration-started.json")
        );
        projector.apply(
                event("02-file-discovery-accepted.json")
        );
        projector.apply(
                event(
                        "04-migration-discovery-completed.json"
                )
        );
        projector.apply(
                event("05-file-transfer-completed.json")
        );
        projector.apply(integrityFailureEvent());

        MigrationReportDocument report =
                storedReport.get();
        FileReportEntry file =
                report.getFiles().getFirst();
        FailureView failure = file.getFailure();

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.FAILED,
                        report.getStatus()
                ),
                () -> assertEquals(
                        1,
                        report.getSummary().integrityFailed()
                ),
                () -> assertEquals(
                        1,
                        report.getSummary().failedFiles()
                ),
                () -> assertEquals(
                        "ACCEPTED",
                        file.getDiscoveryStatus()
                ),
                () -> assertEquals(
                        "STORED",
                        file.getTransferStatus()
                ),
                () -> assertEquals(
                        "FAILED",
                        file.getIntegrityStatus()
                ),
                () -> assertEquals(
                        FileFinalStatus.FAILED,
                        file.getFinalStatus()
                ),
                () -> assertEquals(
                        "INTEGRITY",
                        failure.stage()
                ),
                () -> assertEquals(
                        "CHECKSUM_MISMATCH",
                        failure.code()
                ),
                () -> assertEquals(
                        "Source and destination checksums do not match",
                        failure.message()
                )
        );
    }

    @Test
    void preservesIntegrityFailureWhenEarlierStagesArriveLater()
            throws Exception {
        projector.apply(
                event(
                        "04-migration-discovery-completed.json"
                )
        );
        projector.apply(integrityFailureEvent());
        projector.apply(
                event("05-file-transfer-completed.json")
        );
        projector.apply(
                event("02-file-discovery-accepted.json")
        );

        MigrationReportDocument report =
                storedReport.get();
        FileReportEntry file =
                report.getFiles().getFirst();

        assertAll(
                () -> assertEquals(
                        "ACCEPTED",
                        file.getDiscoveryStatus()
                ),
                () -> assertEquals(
                        "STORED",
                        file.getTransferStatus()
                ),
                () -> assertEquals(
                        "FAILED",
                        file.getIntegrityStatus()
                ),
                () -> assertEquals(
                        FileFinalStatus.FAILED,
                        file.getFinalStatus()
                ),
                () -> assertEquals(
                        "INTEGRITY",
                        file.getFailure().stage()
                ),
                () -> assertEquals(
                        MigrationReportStatus.FAILED,
                        report.getStatus()
                )
        );
    }

    @Test
    void lateIntegrityEventRecoversTimedOutReport()
            throws Exception {
        projector.apply(
                event("01-migration-started.json")
        );
        projector.apply(
                event("02-file-discovery-accepted.json")
        );
        projector.apply(
                event(
                        "04-migration-discovery-completed.json"
                )
        );
        projector.apply(
                event("05-file-transfer-completed.json")
        );

        storedReport.get().markIncomplete(
                java.time.Instant.parse(
                        "2026-10-11T11:00:00Z"
                )
        );

        projector.apply(
                event(
                        "06-integrity-validation-completed.json"
                )
        );

        MigrationReportDocument report = storedReport.get();

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.COMPLETED,
                        report.getStatus()
                ),
                () -> assertEquals(
                        FileFinalStatus.SUCCEEDED,
                        report.getFiles().getFirst()
                                .getFinalStatus()
                ),
                () -> assertEquals(
                        1,
                        report.getSummary().successfulFiles()
                )
        );
    }

    @Test
    void projectsMigrationAndFileMetadata() throws Exception {
        projector.apply(
                event("01-migration-started.json")
        );
        projector.apply(
                event("02-file-discovery-accepted.json")
        );

        MigrationReportDocument report = storedReport.get();
        FileReportEntry file = report.getFiles().getFirst();

        assertAll(
                () -> assertEquals(
                        "user-123",
                        report.getInitiatedBy()
                ),
                () -> assertEquals(
                        "MANIFEST",
                        report.getSource().type()
                ),
                () -> assertEquals(
                        "legacy-media",
                        report.getSource().rootId()
                ),
                () -> assertEquals(
                        "semester-1-media.csv",
                        report.getSource().manifestName()
                ),
                () -> assertEquals(
                        "MEDIA-001",
                        file.getManifestMediaId()
                ),
                () -> assertEquals(
                        "report.pdf",
                        file.getFileName()
                ),
                () -> assertEquals(
                        "department-a/report.pdf",
                        file.getRelativePath()
                )
        );
    }

    @Test
    void lateMigrationStartedCorrectsProvisionalStartTime()
            throws Exception {
        projector.apply(
                event(
                        "06-integrity-validation-completed.json"
                )
        );
        projector.apply(
                event("01-migration-started.json")
        );

        assertEquals(
                Instant.parse("2026-10-11T10:00:00Z"),
                storedReport.get().getStartedAt()
        );
    }

    @Test
    void projectsMigrationDiscoveryFailureAsTerminalFailure()
            throws Exception {
        projector.apply(
                event("01-migration-started.json")
        );
        projector.apply(migrationDiscoveryFailureEvent());

        MigrationReportDocument report = storedReport.get();

        assertAll(
                () -> assertEquals(
                        MigrationReportStatus.FAILED,
                        report.getStatus()
                ),
                () -> assertEquals(
                        "DISCOVERY",
                        report.getFailure().stage()
                ),
                () -> assertEquals(
                        "INVALID_MANIFEST",
                        report.getFailure().code()
                )
        );
    }

    @Test
    void preservesDiscoveryErrorStatus() throws Exception {
        projector.apply(discoveryErrorEvent());

        FileReportEntry file = storedReport.get()
                .getFiles().getFirst();

        assertAll(
                () -> assertEquals(
                        "ERROR",
                        file.getDiscoveryStatus()
                ),
                () -> assertEquals(
                        FileFinalStatus.FAILED,
                        file.getFinalStatus()
                )
        );
    }

    @Test
    void preservesIntegrityErrorStatus() throws Exception {
        projector.apply(integrityErrorEvent());

        FileReportEntry file = storedReport.get()
                .getFiles().getFirst();

        assertAll(
                () -> assertEquals(
                        "ERROR",
                        file.getIntegrityStatus()
                ),
                () -> assertEquals(
                        FileFinalStatus.FAILED,
                        file.getFinalStatus()
                )
        );
    }

    private ValidatedEvent discoveryErrorEvent()
            throws Exception {
        ValidatedEvent original = event(
                "03-file-discovery-rejected.json"
        );
        EventEnvelope envelope = original.envelope();

        return new ValidatedEvent(
                new EventEnvelope(
                        envelope.eventId(),
                        envelope.eventType(),
                        envelope.schemaVersion(),
                        envelope.sourceService(),
                        envelope.migrationId(),
                        envelope.fileId(),
                        envelope.occurredAt(),
                        objectMapper.readTree(
                                """
                                {
                                  "status": "ERROR",
                                  "fileName": "report.pdf",
                                  "failure": {
                                    "code": "DISCOVERY_ERROR",
                                    "message": "Discovery failed unexpectedly",
                                    "retryable": true
                                  }
                                }
                                """
                        )
                ),
                original.originalBody(),
                original.payloadSha256()
        );
    }

    private ValidatedEvent integrityErrorEvent()
            throws Exception {
        ValidatedEvent original = event(
                "06-integrity-validation-completed.json"
        );
        EventEnvelope envelope = original.envelope();

        return new ValidatedEvent(
                new EventEnvelope(
                        envelope.eventId(),
                        envelope.eventType(),
                        envelope.schemaVersion(),
                        envelope.sourceService(),
                        envelope.migrationId(),
                        envelope.fileId(),
                        envelope.occurredAt(),
                        objectMapper.readTree(
                                """
                                {
                                  "transferId":
                                    "344cd216-dac8-4bd2-a350-1dc891c9f4cb",
                                  "status": "ERROR",
                                  "checksumAlgorithm": "SHA-256",
                                  "durationMs": 3200,
                                  "failure": {
                                    "code": "VALIDATION_ERROR",
                                    "message": "Validation failed unexpectedly",
                                    "retryable": true
                                  }
                                }
                                """
                        )
                ),
                original.originalBody(),
                original.payloadSha256()
        );
    }

    private ValidatedEvent migrationDiscoveryFailureEvent()
            throws Exception {
        ValidatedEvent original = event(
                "04-migration-discovery-completed.json"
        );
        EventEnvelope envelope = original.envelope();

        return new ValidatedEvent(
                new EventEnvelope(
                        envelope.eventId(),
                        envelope.eventType(),
                        envelope.schemaVersion(),
                        envelope.sourceService(),
                        envelope.migrationId(),
                        envelope.fileId(),
                        envelope.occurredAt(),
                        objectMapper.readTree(
                                """
                                {
                                  "status": "FAILED",
                                  "failure": {
                                    "code": "INVALID_MANIFEST",
                                    "message": "Manifest is invalid",
                                    "retryable": false
                                  }
                                }
                                """
                        )
                ),
                original.originalBody(),
                original.payloadSha256()
        );
    }

}
