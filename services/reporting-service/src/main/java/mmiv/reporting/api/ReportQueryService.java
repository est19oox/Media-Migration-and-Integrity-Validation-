package mmiv.reporting.api;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import mmiv.reporting.audit.AuditEventRepository;
import mmiv.reporting.report.FileFinalStatus;
import mmiv.reporting.report.MigrationReportDocument;
import mmiv.reporting.report.MigrationReportRepository;
import mmiv.reporting.report.MigrationReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class ReportQueryService {
  private static final Sort REPORT_ORDER = Sort.by(Sort.Direction.DESC, "startedAt");

  private final MigrationReportRepository reports;
  private final AuditEventRepository events;

  public ReportQueryService(MigrationReportRepository reports, AuditEventRepository events) {
    this.reports = Objects.requireNonNull(reports);
    this.events = Objects.requireNonNull(events);
  }

  public Page<ReportResponse> mine(
      String subject, MigrationReportStatus status, Pageable requested) {
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("subject must not be blank");
    }
    Pageable pageable = reportsPage(requested);
    Page<MigrationReportDocument> result =
        status == null
            ? reports.findByInitiatedBy(subject, pageable)
            : reports.findByInitiatedByAndStatus(subject, status, pageable);
    return result.map(ReportResponse::from);
  }

  public ReportResponse owned(String subject, UUID migrationId) {
    return ReportResponse.from(ownedDocument(subject, migrationId));
  }

  public Page<ReportResponse.FileResult> ownedFiles(
      String subject, UUID migrationId, boolean failedOnly, Pageable requested) {
    return files(ownedDocument(subject, migrationId), failedOnly, requested);
  }

  public Page<ReportResponse> all(
      MigrationReportStatus status, String initiatedBy, Pageable requested) {
    Pageable pageable = reportsPage(requested);
    boolean ownerFilter = initiatedBy != null;
    if (ownerFilter && initiatedBy.isBlank()) {
      throw new IllegalArgumentException("initiatedBy must not be blank");
    }

    Page<MigrationReportDocument> result;
    if (ownerFilter && status != null) {
      result = reports.findByInitiatedByAndStatus(initiatedBy, status, pageable);
    } else if (ownerFilter) {
      result = reports.findByInitiatedBy(initiatedBy, pageable);
    } else if (status != null) {
      result = reports.findByStatus(status, pageable);
    } else {
      result = reports.findAll(pageable);
    }
    return result.map(ReportResponse::from);
  }

  public ReportResponse any(UUID migrationId) {
    return ReportResponse.from(find(migrationId));
  }

  public Page<ReportResponse.FileResult> anyFiles(
      UUID migrationId, boolean failedOnly, Pageable requested) {
    return files(find(migrationId), failedOnly, requested);
  }

  public Page<ReportResponse.AuditEvent> events(UUID migrationId, Pageable requested) {
    find(migrationId);
    Pageable pageable = page(requested, Sort.by(Sort.Direction.ASC, "occurredAt"));
    return events
        .findByMigrationIdOrderByOccurredAtAsc(migrationId.toString(), pageable)
        .map(ReportResponse.AuditEvent::from);
  }

  public Page<UserTimelineResponse> ownedTimeline(
      String subject, UUID migrationId, Pageable requested) {
    ownedDocument(subject, migrationId);
    Pageable pageable =
        page(
            requested,
            Sort.by(Sort.Direction.ASC, "occurredAt")
                .and(Sort.by(Sort.Direction.ASC, "eventType")));
    Page<mmiv.reporting.audit.AuditEventDocument> result =
        events.findByMigrationIdOrderByOccurredAtAsc(migrationId.toString(), pageable);
    List<UserTimelineResponse> timeline =
        result.getContent().stream()
            .sorted(
                Comparator.comparing(mmiv.reporting.audit.AuditEventDocument::getOccurredAt)
                    .thenComparing(value -> value.getEventType().name())
                    .thenComparing(value -> Objects.toString(value.getFileId(), "")))
            .map(UserTimelineResponse::from)
            .toList();
    return new PageImpl<>(timeline, pageable, result.getTotalElements());
  }

  private MigrationReportDocument find(UUID migrationId) {
    Objects.requireNonNull(migrationId, "migrationId must not be null");
    return reports
        .findById(migrationId.toString())
        .orElseThrow(() -> new ReportNotFoundException(migrationId));
  }

  private MigrationReportDocument ownedDocument(String subject, UUID migrationId) {
    MigrationReportDocument report = find(migrationId);
    if (subject == null || !subject.equals(report.getInitiatedBy())) {
      throw new ReportNotFoundException(migrationId);
    }
    return report;
  }

  private Page<ReportResponse.FileResult> files(
      MigrationReportDocument report, boolean failedOnly, Pageable requested) {
    Pageable pageable = page(requested, Sort.by(Sort.Direction.ASC, "fileId"));
    List<ReportResponse.FileResult> values =
        report.getFiles().stream()
            .filter(value -> !failedOnly || value.getFinalStatus() == FileFinalStatus.FAILED)
            .sorted(Comparator.comparing(value -> value.getFileId()))
            .map(ReportResponse.FileResult::from)
            .toList();
    int start = Math.toIntExact(pageable.getOffset());
    if (start >= values.size()) {
      return new PageImpl<>(List.of(), pageable, values.size());
    }
    int end = Math.min(start + pageable.getPageSize(), values.size());
    return new PageImpl<>(values.subList(start, end), pageable, values.size());
  }

  private Pageable reportsPage(Pageable requested) {
    return page(requested, REPORT_ORDER);
  }

  private Pageable page(Pageable requested, Sort sort) {
    Objects.requireNonNull(requested, "pageable must not be null");
    if (requested.getPageNumber() < 0) {
      throw new IllegalArgumentException("page must not be negative");
    }
    if (requested.getPageSize() < 1 || requested.getPageSize() > 200) {
      throw new IllegalArgumentException("size must be between 1 and 200");
    }
    return PageRequest.of(requested.getPageNumber(), requested.getPageSize(), sort);
  }
}
