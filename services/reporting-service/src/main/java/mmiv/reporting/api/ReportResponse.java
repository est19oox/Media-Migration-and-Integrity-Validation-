package mmiv.reporting.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import mmiv.reporting.audit.AuditEventDocument;
import mmiv.reporting.contract.EventType;
import mmiv.reporting.contract.SourceService;
import mmiv.reporting.report.FailureView;
import mmiv.reporting.report.FileFinalStatus;
import mmiv.reporting.report.FileReportEntry;
import mmiv.reporting.report.MigrationReportDocument;
import mmiv.reporting.report.MigrationReportStatus;
import mmiv.reporting.report.MigrationSource;
import mmiv.reporting.report.ReportSummary;

public record ReportResponse(
        String migrationId,
        MigrationReportStatus status,
        String initiatedBy,
        MigrationSource source,
        Instant startedAt,
        Instant completedAt,
        ReportSummary summary,
        FailureView failure
) {

    public static ReportResponse from(
            MigrationReportDocument report
    ) {
        return new ReportResponse(
                report.getId(),
                report.getStatus(),
                report.getInitiatedBy(),
                report.getSource(),
                report.getStartedAt(),
                report.getCompletedAt(),
                report.getSummary(),
                report.getFailure()
        );
    }

    public record FileResult(
            String fileId,
            String manifestMediaId,
            String fileName,
            String relativePath,
            String discoveryStatus,
            String transferStatus,
            String integrityStatus,
            FileFinalStatus finalStatus,
            FailureView failure
    ) {
        public static FileResult from(FileReportEntry file) {
            return new FileResult(
                    file.getFileId(),
                    file.getManifestMediaId(),
                    file.getFileName(),
                    file.getRelativePath(),
                    file.getDiscoveryStatus(),
                    file.getTransferStatus(),
                    file.getIntegrityStatus(),
                    file.getFinalStatus(),
                    file.getFailure()
            );
        }
    }

    public record AuditEvent(
            String eventId,
            EventType eventType,
            int schemaVersion,
            SourceService sourceService,
            String migrationId,
            String fileId,
            Instant occurredAt,
            Map<String, Object> data
    ) {
        public static AuditEvent from(
                AuditEventDocument audit
        ) {
            return new AuditEvent(
                    audit.getId(),
                    audit.getEventType(),
                    audit.getSchemaVersion(),
                    audit.getSourceService(),
                    audit.getMigrationId(),
                    audit.getFileId(),
                    audit.getOccurredAt(),
                    new LinkedHashMap<>(audit.getData())
            );
        }
    }
}
