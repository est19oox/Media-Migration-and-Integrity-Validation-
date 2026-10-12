package mmiv.reporting.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;

@org.springframework.data.mongodb.core.mapping.Document(
        collection = "migration_reports"
)
@CompoundIndex(
        name = "status_started_idx",
        def = "{'status': 1, 'startedAt': -1}"
)
public class MigrationReportDocument {

    @Id
    private final String id;

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
            List<FileReportEntry> files
    ) {
        this.id = Objects.requireNonNull(
                id,
                "id must not be null"
        );

        this.status = Objects.requireNonNull(
                status,
                "status must not be null"
        );

        this.startedAt = Objects.requireNonNull(
                startedAt,
                "startedAt must not be null"
        );

        this.latestEventAt = Objects.requireNonNull(
                latestEventAt,
                "latestEventAt must not be null"
        );

        this.initiatedBy = initiatedBy;
        this.source = source;
        this.failure = failure;
        this.discoveryFailed = discoveryFailed;

        this.completedAt = completedAt;

        this.summary = Objects.requireNonNull(
                summary,
                "summary must not be null"
        );

        this.expectedTotalFiles = expectedTotalFiles;

        this.files = new ArrayList<>(
                Objects.requireNonNull(
                        files,
                        "files must not be null"
                )
        );
    }

    public static MigrationReportDocument processing(
            UUID migrationId,
            Instant startedAt
    ) {
        Objects.requireNonNull(
                migrationId,
                "migrationId must not be null"
        );

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
                List.of()
        );
    }

    public Optional<FileReportEntry> findFile(UUID fileId) {
        Objects.requireNonNull(
                fileId,
                "fileId must not be null"
        );

        String requestedId = fileId.toString();

        return files.stream()
                .filter(file ->
                        file.getFileId().equals(requestedId)
                )
                .findFirst();
    }

    public FileReportEntry getOrCreateFile(UUID fileId) {
        Objects.requireNonNull(
                fileId,
                "fileId must not be null"
        );

        return findFile(fileId)
                .orElseGet(() -> {
                    FileReportEntry created =
                            FileReportEntry.processing(fileId);

                    files.add(created);
                    return created;
                });
    }

    public void recordDiscoveryCompleted(long totalFiles) {
        if (totalFiles < 0) {
            throw new IllegalArgumentException(
                    "totalFiles must not be negative"
            );
        }

        this.expectedTotalFiles = totalFiles;
        this.discoveryFailed = false;
        this.failure = null;
    }

    public void recordDiscoveryFailed(FailureView failure) {
        this.discoveryFailed = true;
        this.expectedTotalFiles = null;
        this.failure = Objects.requireNonNull(
                failure,
                "failure must not be null"
        );
    }

    public void recordStarted(
            Instant startedAt,
            String initiatedBy,
            MigrationSource source
    ) {
        this.startedAt = Objects.requireNonNull(
                startedAt,
                "startedAt must not be null"
        );
        this.initiatedBy = Objects.requireNonNull(
                initiatedBy,
                "initiatedBy must not be null"
        );
        this.source = Objects.requireNonNull(
                source,
                "source must not be null"
        );
    }

    public void recordEventOccurredAt(Instant occurredAt) {
        Objects.requireNonNull(
                occurredAt,
                "occurredAt must not be null"
        );
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

    public void replaceSummary(ReportSummary summary) {
        this.summary = Objects.requireNonNull(
                summary,
                "summary must not be null"
        );
    }

    public void markCompleted(
            MigrationReportStatus status,
            Instant completedAt
    ) {
        Objects.requireNonNull(
                status,
                "status must not be null"
        );

        if (status != MigrationReportStatus.COMPLETED
                && status
                != MigrationReportStatus.PARTIALLY_COMPLETED
                && status != MigrationReportStatus.FAILED) {
            throw new IllegalArgumentException(
                    "Completed report requires a terminal status"
            );
        }

        this.status = status;

        this.completedAt = Objects.requireNonNull(
                completedAt,
                "completedAt must not be null"
        );
    }

    public void markIncomplete(Instant completedAt) {
        this.status = MigrationReportStatus.INCOMPLETE;

        this.completedAt = Objects.requireNonNull(
                completedAt,
                "completedAt must not be null"
        );
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
}
